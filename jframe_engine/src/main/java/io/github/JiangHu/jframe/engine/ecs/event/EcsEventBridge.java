package io.github.JiangHu.jframe.engine.ecs.event;

import cn.nukkit.event.Cancellable;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.EventHandlerInfo;
import io.github.JiangHu.jframe.engine.ecs.registry.ResolverRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SystemEventRegistry;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 事件桥（自包含 Nukkit Listener，零依赖 listener 模块）— 事件到 ECS 的统一过滤分发。
 *
 * <h3>分发链路（先系统后组件；组内 order 升序，平级注册序）</h3>
 * <ol>
 *   <li><b>事件类型级</b>：组件订阅需「有解析器(宽匹配)」；系统订阅有订阅即可（全局处理器无需解析器）</li>
 *   <li><b>系统处理器</b>（全局，每事件一次，按 order 升序）：filter（host=null）→ ignoreCancelled → 调用；
 *       声明 {@code truncate} 且正常返回 → 更低 order 系统跳过 + 整个组件层不分发（含惰性收养）</li>
 *   <li><b>宿主级</b>：解析器提取候选（Entity/BlockEntity/Position/Block/EcsEntity）→
 *       引擎换算并查绑定索引（未跟踪候选会被<b>惰性收养</b>，见 EcsEngine.resolveCandidate）；
 *       同事件同实体去重</li>
 *   <li><b>组件级</b>：仅宿主当前已挂组件中声明了匹配事件类型的方法（挂载即订阅、卸载即退订），
 *       按 order 升序执行；声明截断 → 同实体更低 order 跳过（其他宿主不受影响）</li>
 *   <li><b>方法级</b>：filter 过滤器（统一机制，无 SpEL）/ ignoreCancelled</li>
 * </ol>
 *
 * <h3>注册与激活</h3>
 * <ul>
 *   <li>插件绑定前（Spring 容器阶段）的注册请求暂存，绑定后统一刷新（模式同 EventEngine）</li>
 *   <li>{@code stop()} 后 {@code active=false}，已注册监听变为空操作</li>
 *   <li>单个 handler 异常捕获记录，不影响其余 handler</li>
 * </ul>
 *
 * @see EcsEventResolver
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent
 */
public class EcsEventBridge implements Listener {

    /** 引擎（宿主换算与实体访问） */
    private final EcsEngine engine;

    /** 组件注册表（事件 → 处理器表） */
    private final ComponentRegistry componentRegistry;

    /** 解析器注册表 */
    private final ResolverRegistry resolverRegistry;

    /** 系统事件注册表（全局处理器） */
    private final SystemEventRegistry systemEventRegistry;

    /** 已向 Nukkit 注册的事件类型 */
    private final Set<Class<? extends Event>> registered = new HashSet<>();

    /** 已告警过"无解析器覆盖"的事件类型（按类型一次性，防刷屏） */
    private final Set<Class<? extends Event>> unresolvedWarned = new HashSet<>();

    /** 绑定前暂存的待注册事件类型 */
    private final List<Class<? extends Event>> pending = new java.util.ArrayList<>();

    /** 关联插件（Nukkit 注册用） */
    private Plugin plugin;

    /** 激活标志（stop 后分发为空操作） */
    private volatile boolean active;

    /**
     * 构造事件桥。
     *
     * @param engine              引擎
     * @param componentRegistry   组件注册表
     * @param resolverRegistry    解析器注册表
     * @param systemEventRegistry 系统事件注册表
     */
    public EcsEventBridge(EcsEngine engine, ComponentRegistry componentRegistry,
                          ResolverRegistry resolverRegistry,
                          SystemEventRegistry systemEventRegistry) {
        this.engine = engine;
        this.componentRegistry = componentRegistry;
        this.resolverRegistry = resolverRegistry;
        this.systemEventRegistry = systemEventRegistry;
    }

    /**
     * 绑定插件并刷新暂存注册。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return;
        }
        this.plugin = plugin;
        if (plugin != null && !pending.isEmpty()) {
            for (Class<? extends Event> eventType : pending) {
                doRegister(eventType);
            }
            pending.clear();
        }
    }

    /**
     * 激活分发（引擎 start 时调用）。
     */
    public void activate() {
        this.active = true;
        syncRegistrations();
    }

    /**
     * 停止分发（引擎 stop 时调用；已注册监听变为空操作）。
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 按需同步 Nukkit 监听注册：
     * <ul>
     *   <li>组件订阅的事件类型：需同时存在宽匹配解析器（宿主解析是组件分发的前提）；</li>
     *   <li><b>系统订阅的事件类型：有订阅即注册</b>——全局处理器无需解析器。</li>
     * </ul>
     * <p>随组件/解析器/系统注册与定义加载动态调用。
     */
    public synchronized void syncRegistrations() {
        for (Class<? extends Event> handlerType : componentRegistry.handlerEventTypes()) {
            if (registered.contains(handlerType)) {
                continue;
            }
            if (resolverRegistry.resolversFor(handlerType).isEmpty()) {
                // 链路异常可见化：组件订阅了无解析器覆盖的事件类型 → 永远收不到（一次性告警）
                if (unresolvedWarned.add(handlerType)) {
                    EcsLog.warning("组件订阅了事件 " + handlerType.getSimpleName()
                            + "，但无宿主解析器覆盖——该事件不会路由到组件。"
                            + "请注册对应的 EcsEventResolver，或改用系统处理器（无需解析器）");
                }
                continue;
            }
            registered.add(handlerType);
            if (plugin == null) {
                pending.add(handlerType);
            } else {
                doRegister(handlerType);
            }
        }
        for (Class<? extends Event> handlerType : systemEventRegistry.handlerEventTypes()) {
            if (registered.contains(handlerType)) {
                continue;
            }
            registered.add(handlerType);
            if (plugin == null) {
                pending.add(handlerType);
            } else {
                doRegister(handlerType);
            }
        }
    }

    /**
     * 已触发"无解析器覆盖"告警的事件类型快照（测试断言用）。
     *
     * @return 事件类型集合
     */
    synchronized Set<Class<? extends Event>> unresolvedWarned() {
        return Set.copyOf(unresolvedWarned);
    }

    /**
     * 向 Nukkit 注册单事件类型监听（固定 LOWEST，参考 EventEngine）。
     */
    private void doRegister(Class<? extends Event> eventType) {
        cn.nukkit.Server.getInstance().getPluginManager().registerEvent(
                eventType, this, EventPriority.LOWEST,
                (listener, event) -> dispatch(eventType, event),
                plugin, false);
    }

    /**
     * 分发入口（Nukkit 监听回调；测试可直接调用以模拟事件触发）。
     *
     * <p>顺序与截断（确定性契约，详见 {@code OnEcsEvent}）：
     * <ol>
     *   <li><b>先系统（全局）后组件（局部）</b>——系统代表全局观察者/前置加工，
     *       系统若取消事件，后续带 {@code ignoreCancelled} 的处理器可感知</li>
     *   <li>系统组/组件组<b>各自</b>按 {@code order} 升序（平级按注册序）执行</li>
     *   <li>系统处理器声明 {@code truncate} 且正常返回 → 更低 order 系统跳过 +
     *       <b>整个组件层不分发</b>（含宿主解析与惰性收养）；组件处理器声明截断 →
     *       仅同实体更低 order 跳过（其他宿主不受影响）</li>
     * </ol>
     *
     * @param eventType 注册的事件类型
     * @param event     事件对象
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void dispatch(Class<? extends Event> eventType, Event event) {
        if (!active) {
            return;
        }
        // ① 系统处理器（全局，每事件一次）：无需宿主解析——必须在 resolvers 早退之前执行；
        //    系统层截断 → 整个组件层不分发
        if (dispatchToSystems(eventType, event)) {
            return;
        }
        // ② 组件分发（宿主级 + 组件级 + 方法级）
        List<EcsEventResolver> resolvers = resolverRegistry.resolversFor(eventType);
        if (resolvers.isEmpty()) {
            return;
        }
        Set<EcsEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EcsEventResolver resolver : resolvers) {
            List<Object> candidates;
            try {
                candidates = resolver.extractHosts(event);
            } catch (Exception e) {
                EcsLog.error("解析器 " + resolver.getClass().getSimpleName() + " 提取宿主异常", e);
                continue;
            }
            if (candidates == null) {
                continue;
            }
            for (Object candidate : candidates) {
                EcsEntity entity = candidate == null ? null : engine.resolveCandidate(candidate);
                if (entity == null) {
                    if (candidate != null) {
                        // 链路异常可见化：解析器给出了候选但引擎无法换算（形状不认识/位置无方块实体）
                        EcsLog.warning("解析器 " + resolver.getClass().getSimpleName()
                                + " 的候选无法换算为实体而被丢弃: " + candidate.getClass().getName());
                    }
                    continue;
                }
                if (!seen.add(entity)) {
                    continue; // 同事件同实体去重
                }
                dispatchToEntity(entity, eventType, event);
            }
        }
    }

    /**
     * 分发到系统处理器（全局）：按 order 组推进；ignoreCancelled → invoke；单个异常隔离。
     *
     * @return true 表示系统层声明截断（更低 order 系统已跳过，组件层不应分发）
     */
    private boolean dispatchToSystems(Class<? extends Event> eventType, Event event) {
        List<SystemEventRegistry.SystemEventHandlerInfo> handlers =
                systemEventRegistry.handlersFor(eventType);
        int i = 0;
        while (i < handlers.size()) {
            // 组边界推进：同 order 平级全部执行，再检查本组是否声明截断
            int currentOrder = handlers.get(i).order();
            boolean groupTruncated = false;
            while (i < handlers.size() && handlers.get(i).order() == currentOrder) {
                SystemEventRegistry.SystemEventHandlerInfo handler = handlers.get(i);
                i++;
                if (!handler.eventType().isInstance(event)) {
                    // 类型守卫：实际事件不是处理器声明类型的实例（基类事件经 HandlerList
                    // 继承链进入子类型监听——MOT 无独立 HandlerList 的子类事件常态），
                    // 该处理器本就不该收到此事件，静默跳过
                    continue;
                }
                if (handler.ignoreCancelled() && event instanceof Cancellable cancellable
                        && cancellable.isCancelled()) {
                    continue;
                }
                boolean returnedNormally;
                try {
                    handler.method().invoke(handler.system(), event);
                    returnedNormally = true;
                } catch (Exception e) {
                    EcsLog.error("系统事件处理器执行异常: " + handler, e);
                    returnedNormally = false; // 异常 → 不截断（仅记录，异常隔离不变）
                }
                if (returnedNormally && handler.truncate()) {
                    groupTruncated = true;
                }
            }
            if (groupTruncated) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分发到单个宿主实体上已挂组件的匹配处理器（组件级 + 方法级 ignoreCancelled；
     * 方法级条件过滤由处理器方法内首行守卫承担）。
     *
     * <p>按 order 组推进：同 order 平级全部执行，本组声明截断（且正常返回）→
     * 跳过<b>同实体</b>更低 order 的处理器；其他宿主实体不受影响。
     */
    private void dispatchToEntity(EcsEntity entity, Class<? extends Event> eventType, Event event) {
        List<EventHandlerInfo> handlers = componentRegistry.handlersFor(eventType);
        int i = 0;
        while (i < handlers.size()) {
            int currentOrder = handlers.get(i).order();
            boolean groupTruncated = false;
            while (i < handlers.size() && handlers.get(i).order() == currentOrder) {
                EventHandlerInfo handler = handlers.get(i);
                i++;
                if (!handler.eventType().isInstance(event)) {
                    // 类型守卫：实际事件不是处理器声明类型的实例——该处理器不该收到此事件，
                    // 静默跳过（防 HandlerList 继承链把基类事件送进子类型处理器导致
                    // invoke 参数不匹配异常，旧 wrapper 引擎的基类参数防御同源）
                    continue;
                }
                Component component = entity.get(handler.componentClass());
                if (component == null) {
                    continue; // 组件级：挂载即订阅、卸载即退订
                }
                if (handler.ignoreCancelled() && event instanceof Cancellable cancellable
                        && cancellable.isCancelled()) {
                    continue;
                }
                boolean returnedNormally;
                try {
                    handler.method().invoke(component, event);
                    returnedNormally = true;
                } catch (Exception e) {
                    EcsLog.error("事件处理器执行异常: " + handler, e);
                    returnedNormally = false; // 异常 → 不截断
                }
                if (returnedNormally && handler.truncate()) {
                    groupTruncated = true;
                }
            }
            if (groupTruncated) {
                return; // 截断：同实体更低 order 跳过（其他宿主实体不受影响）
            }
        }
    }
}
