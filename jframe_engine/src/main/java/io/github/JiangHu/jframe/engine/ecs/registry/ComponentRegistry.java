package io.github.JiangHu.jframe.engine.ecs.registry;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组件注册表 — 组件 id ↔ 类，并在注册时扫描 {@code @OnEcsEvent} 方法建立事件分发表。
 *
 * <p>处理器按 {@code (order, 注册序)} 稳定排序存放——{@link #handlersFor} 返回的即最终执行序。
 *
 * <h3>注册期校验（fail fast）</h3>
 * <ul>
 *   <li>id 重复 → {@link EcsConfigException}</li>
 *   <li>缺少可访问无参构造 → {@link EcsConfigException}</li>
 *   <li>{@code @OnEcsEvent} 方法非 public / static / 参数数不为 1 / 参数非 Event 子类 → 拒绝</li>
 * </ul>
 */
public class ComponentRegistry {

    /** 组件 id → 组件类 */
    private final Map<String, Class<? extends Component>> byId = new LinkedHashMap<>();

    /** 事件类型 → 处理器列表（该事件类型上的全部分发入口） */
    private final Map<Class<? extends Event>, List<EventHandlerInfo>> handlersByEvent = new HashMap<>();

    /** 池化组件类集合（@EcsComponent(pooled=true) 或显式 pooled=true 登记） */
    private final Set<Class<?>> pooledTypes = new HashSet<>();

    /**
     * 注册组件类（池化属性取 {@code @EcsComponent(pooled)} 注解，未标注视为 false）。
     *
     * @param id   组件 id（定义文件 components[].id 引用）
     * @param type 组件类（须实现 Component、可访问无参构造）
     * @throws EcsConfigException 校验失败
     */
    public synchronized void register(String id, Class<? extends Component> type) {
        register(id, type, isAnnotatedPooled(type));
    }

    /**
     * 注册组件类并显式指定池化属性（手动注册路径；与注解声明等价）。
     *
     * @param id     组件 id
     * @param type   组件类（须实现 Component、可访问无参构造）
     * @param pooled 是否参与实例池化（见 {@link EcsComponent#pooled()}）
     * @throws EcsConfigException 校验失败
     */
    public synchronized void register(String id, Class<? extends Component> type, boolean pooled) {
        if (id == null || id.isBlank()) {
            throw new EcsConfigException("组件 id 不得为空");
        }
        if (byId.containsKey(id)) {
            throw new EcsConfigException("组件 id 重复注册: '" + id + "'（已有 " + byId.get(id).getName() + "）");
        }
        requireNoArgConstructor(type);

        List<EventHandlerInfo> infos = scanHandlers(id, type);
        byId.put(id, type);
        if (pooled) {
            pooledTypes.add(type);
        } else {
            pooledTypes.remove(type);
        }
        for (EventHandlerInfo info : infos) {
            List<EventHandlerInfo> list = handlersByEvent.computeIfAbsent(info.eventType(), k -> new ArrayList<>());
            list.add(info);
            // 稳定排序：按 order 升序，平级保持注册顺序（文档化契约）
            list.sort(Comparator.comparingInt(EventHandlerInfo::order));
        }
    }

    /**
     * 组件类是否登记为池化（未登记的类一律 false）。
     *
     * @param type 组件类
     * @return 池化 true
     */
    public synchronized boolean isPooled(Class<? extends Component> type) {
        return pooledTypes.contains(type);
    }

    /**
     * 读 {@code @EcsComponent} 注解的 pooled 属性。
     */
    private static boolean isAnnotatedPooled(Class<? extends Component> type) {
        EcsComponent anno = type.getAnnotation(EcsComponent.class);
        return anno != null && anno.pooled();
    }

    /**
     * 按 id 取组件类（不存在即抛异常）。
     *
     * @param id 组件 id
     * @return 组件类
     * @throws EcsConfigException 未注册
     */
    public synchronized Class<? extends Component> require(String id) {
        Class<? extends Component> type = byId.get(id);
        if (type == null) {
            throw new EcsConfigException("未注册的组件 id: '" + id + "'（已注册: " + byId.keySet() + "）");
        }
        return type;
    }

    /**
     * 组件 id 是否已注册。
     *
     * @param id 组件 id
     * @return 已注册 true
     */
    public synchronized boolean contains(String id) {
        return byId.containsKey(id);
    }

    /**
     * 指定事件类型上的全部处理器。
     *
     * @param eventType 事件类型
     * @return 处理器列表（不可变；无则空）
     */
    public synchronized List<EventHandlerInfo> handlersFor(Class<? extends Event> eventType) {
        List<EventHandlerInfo> list = handlersByEvent.get(eventType);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * 有处理器的全部事件类型（事件桥按需注册监听用）。
     *
     * @return 事件类型集合快照
     */
    public synchronized List<Class<? extends Event>> handlerEventTypes() {
        return List.copyOf(handlersByEvent.keySet());
    }

    /**
     * 已注册组件快照。
     *
     * @return id → 类
     */
    public synchronized Map<String, Class<? extends Component>> all() {
        return Map.copyOf(byId);
    }

    /**
     * 校验无参构造可访问（public 或包私有均可，注册时 trySetAccessible）。
     *
     * @param type 目标类
     * @throws EcsConfigException 缺少无参构造或不可访问
     */
    static void requireNoArgConstructor(Class<?> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
        } catch (NoSuchMethodException e) {
            throw new EcsConfigException(type.getName() + " 缺少无参构造，无法反射实例化");
        } catch (SecurityException e) {
            throw new EcsConfigException(type.getName() + " 无参构造不可访问", e);
        }
    }

    /**
     * 扫描组件类（含父类）上的 {@code @OnEcsEvent} 方法并完成全部注册期校验。
     *
     * @param id   组件 id
     * @param type 组件类
     * @return 处理器元数据列表
     * @throws EcsConfigException 校验失败
     */
    private static List<EventHandlerInfo> scanHandlers(String id, Class<? extends Component> type) {
        List<EventHandlerInfo> result = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                OnEcsEvent anno = method.getAnnotation(OnEcsEvent.class);
                if (anno == null) {
                    continue;
                }
                result.add(buildInfo(id, type, method, anno));
            }
        }
        return result;
    }

    /**
     * 构建单个处理器元数据（签名校验共享自 {@link HandlerValidation}）。
     */
    private static EventHandlerInfo buildInfo(String id, Class<? extends Component> type,
                                              Method method, OnEcsEvent anno) {
        String where = type.getSimpleName() + "#" + method.getName();
        Class<? extends Event> eventType = HandlerValidation.requireHandlerSignature(method, where);
        method.setAccessible(true);
        return new EventHandlerInfo(id, type, eventType, method,
                anno.ignoreCancelled(), anno.order(), anno.truncate());
    }
}
