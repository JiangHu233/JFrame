package io.github.JiangHu.jframe.event.routing;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.event.EventConsumer;
import io.github.JiangHu.jframe.event.EventEngine;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 核心路由引擎：统一管理对象处理器的注册、身份匹配、优先级分发。
 * <p>
 * 替代旧版 {@code ObjectEventRouter}。核心改进：
 * <ul>
 *   <li><b>类级注册</b>：{@code register(Class)} 而非 {@code register(Class, identity)}</li>
 *   <li><b>强制身份提取</b>：每个包装类必须声明 {@link KeyExtractor}，无提取器则无法路由</li>
 *   <li><b>工厂模式</b>：实例在分发时通过 {@link InstanceProvider} 或默认缓存按需创建</li>
 *   <li><b>跨优先级独占</b>：高优先级 exclusive=true 停止所有低优先级处理器</li>
 *   <li><b>多槽位提取</b>：同一事件类型可有多个 {@link KeyExtractor}，OR 语义</li>
 * </ul>
 *
 * <h3>分发流程</h3>
 * <pre>
 * 事件到达
 *   ↓
 * ① 遍历每个 WrapperRegistration：
 *   └── 调用其 @KeyExtractor（static）提取身份 → 获取/创建实例
 *   ↓
 * ② 收集所有匹配的 (instance, HandlerTemplate) 对
 *   ↓
 * ③ 按优先级降序排序（HIGHEST → LOWEST）
 *   ↓
 * ④ 分组分发 + 跨优先级独占检查
 * </pre>
 *
 * @see HandlerTemplate
 * @see EventAPI
 */

public class HandlerRegistry {

    /**
     * 类注册信息：一个包装类的完整元数据。
     * 同一 Registration 会被引用在多个事件类型的列表中。
     *
     * @param wrapperClass      包装类
     * @param instanceProvider  @InstanceProvider 方法（null = 默认缓存）
     * @param defaultCache      默认缓存（instanceProvider == null 时使用）
     * @param extractors        @KeyExtractor 方法（按事件类型分组）
     * @param handlers          HandlerTemplate（按事件类型分组）
     */
    record WrapperRegistration(
            Class<?> wrapperClass,
            Method instanceProvider,
            ConcurrentHashMap<Object, Object> defaultCache,
            Map<Class<? extends Event>, List<Method>> extractors,
            Map<Class<? extends Event>, List<HandlerTemplate>> handlers,
            HandlerMode mode,
            Object singletonInstance
    ) {}

    /** 已绑定的处理器：实例 + 模板 */
    private record BoundHandler(Object instance, HandlerTemplate template) {}

    /** STATIC 模式注册记录：事件类型 + 消费者（用于注销） */
    private record StaticConsumer(Class<? extends Event> eventType, EventConsumer<?> consumer) {}

    /** 核心存储：事件类型 → [WrapperRegistration]（仅 OBJECT/SINGLETON） */
    private final Map<Class<? extends Event>, CopyOnWriteArrayList<WrapperRegistration>> registry =
            new ConcurrentHashMap<>();

    /** 类 → Registration 反向索引（OBJECT/SINGLETON，用于注销） */
    private final Map<Class<?>, WrapperRegistration> classToReg = new ConcurrentHashMap<>();

    /** STATIC 模式：类 → [StaticConsumer]（用于注销） */
    private final Map<Class<?>, List<StaticConsumer>> staticConsumers = new ConcurrentHashMap<>();

    /** 已订阅的事件类型集合 */
    private final Set<Class<? extends Event>> subscribed = ConcurrentHashMap.newKeySet();

    private final EventEngine engine;

    public HandlerRegistry(EventEngine engine) {
        this.engine = engine;
    }

    // ========== 注册 ==========

    /**
     * 注册包装类。
     * <p>
     * 扫描类中的 {@link KeyExtractor}、{@link InstanceProvider}、{@link EventHandler} 方法，
     * 按 <b>handler 级别</b>（而非类级别）分流注册：
     * <ul>
     *   <li><b>static handler</b> → 始终走 {@link #registerStatic}（EventEngine 兜底链），
     *       与类内是否存在实例 handler 无关，保证 STATIC 的兜底语义不被吞。</li>
     *   <li><b>实例 handler</b> → 有 {@link KeyExtractor} 走 {@link #registerObject}（OBJECT），
     *       否则走 {@link #registerSingleton}（SINGLETON）。</li>
     * </ul>
     * 因此一个类可同时产生「STATIC +（OBJECT 或 SINGLETON）」两类注册：
     * 例如「实例 handler + static handler」的混合写法，static 部分兜底最后执行，
     * 实例部分按身份/单例正常参与优先级排序。
     * <p>
     * <b>内部 API</b>：用户应通过 {@link EventAPI#register} 调用，本类作为内部实现。
     *
     * @param wrapperClass 包装类
     * @param <T>          包装类型
     */
    public <T> void register(Class<T> wrapperClass) {
        // 扫描 @KeyExtractor 方法（static）
        Map<Class<? extends Event>, List<Method>> extractors = scanExtractors(wrapperClass);

        // 扫描 @EventHandler + @EventRoute 方法
        Map<Class<? extends Event>, List<HandlerTemplate>> handlers = scanHandlers(wrapperClass, wrapperClass);
        if (handlers.isEmpty()) {
            JFrameLog.warning("HandlerRegistry",
                    "类 " + wrapperClass.getName() + " 无 @EventHandler 方法，注册无效果。");
            return;
        }

        // 按 static 拆分 handler：static handler 始终走兜底链，实例 handler 走 OBJECT/SINGLETON。
        // 这样「实例 handler + static handler」的混合写法不会再把 static 误判为 SINGLETON。
        Map<Class<? extends Event>, List<HandlerTemplate>> staticHandlers = new HashMap<>();
        Map<Class<? extends Event>, List<HandlerTemplate>> instanceHandlers = new HashMap<>();
        partitionHandlers(handlers, staticHandlers, instanceHandlers);

        // ① static handler：始终走 EventEngine 兜底链（tailConsumers），
        //    与类内是否存在实例 handler 无关，保证 STATIC 的兜底语义不被吞。
        if (!staticHandlers.isEmpty()) {
            registerStatic(wrapperClass, staticHandlers);
        }

        // ② 实例 handler：有 @KeyExtractor → OBJECT，否则 → SINGLETON。
        if (!instanceHandlers.isEmpty()) {
            if (!extractors.isEmpty()) {
                registerObject(wrapperClass, extractors, instanceHandlers);
            } else {
                registerSingleton(wrapperClass, instanceHandlers);
            }
        }
    }

    /**
     * 将 handler 按是否 static 拆分到两个集合。
     * <p>
     * static handler → {@code staticHandlers}（走兜底链）；
     * 实例 handler → {@code instanceHandlers}（走 OBJECT/SINGLETON）。
     *
     * @param handlers        全部 handler（按事件类型分组）
     * @param staticHandlers  接收 static handler（输出参数）
     * @param instanceHandlers 接收实例 handler（输出参数）
     */
    private void partitionHandlers(Map<Class<? extends Event>, List<HandlerTemplate>> handlers,
                                   Map<Class<? extends Event>, List<HandlerTemplate>> staticHandlers,
                                   Map<Class<? extends Event>, List<HandlerTemplate>> instanceHandlers) {
        for (Map.Entry<Class<? extends Event>, List<HandlerTemplate>> entry : handlers.entrySet()) {
            Class<? extends Event> eventType = entry.getKey();
            for (HandlerTemplate template : entry.getValue()) {
                Map<Class<? extends Event>, List<HandlerTemplate>> target =
                        template.isHandlerStatic() ? staticHandlers : instanceHandlers;
                target.computeIfAbsent(eventType, k -> new ArrayList<>()).add(template);
            }
        }
    }

    /**
     * OBJECT 模式注册：有 @KeyExtractor，按身份提取实例。
     */
    private void registerObject(Class<?> wrapperClass,
                                Map<Class<? extends Event>, List<Method>> extractors,
                                Map<Class<? extends Event>, List<HandlerTemplate>> handlers) {
        // 检测：每个 handler 的事件类型必须有对应的 @KeyExtractor，否则该 handler 永远不会执行（静默失败）
        warnMissingExtractors(wrapperClass, extractors, handlers);

        Method instanceProvider = scanInstanceProvider(wrapperClass);
        ConcurrentHashMap<Object, Object> defaultCache =
                instanceProvider == null ? new ConcurrentHashMap<>() : null;

        WrapperRegistration reg = new WrapperRegistration(
                wrapperClass, instanceProvider, defaultCache,
                extractors, handlers, HandlerMode.OBJECT, null
        );
        classToReg.put(wrapperClass, reg);
        addToRegistry(reg);
    }

    /**
     * 检测 OBJECT 模式下，handler 处理的某些事件类型缺少对应的 @KeyExtractor。
     * <p>
     * 缺少 extractor 的事件类型，其 handler 在 dispatch 时会被 collectObjectHandlers 静默跳过
     * （因为无法提取身份路由到实例）。此处提前打 WARNING 日志，帮助开发者发现配置错误。
     */
    private void warnMissingExtractors(Class<?> wrapperClass,
                                       Map<Class<? extends Event>, List<Method>> extractors,
                                       Map<Class<? extends Event>, List<HandlerTemplate>> handlers) {
        for (Map.Entry<Class<? extends Event>, List<HandlerTemplate>> entry : handlers.entrySet()) {
            Class<? extends Event> eventType = entry.getKey();
            if (!extractors.containsKey(eventType)) {
                JFrameLog.warning("HandlerRegistry",
                        "类 " + wrapperClass.getName()
                                + " 处理 " + eventType.getSimpleName()
                                + " 的 @EventHandler 方法缺少对应的 @KeyExtractor，这些 handler 将永远不会被执行。"
                                + "请为 " + eventType.getSimpleName()
                                + " 添加 @KeyExtractor，或将这些 handler 移到独立的 SINGLETON/STATIC wrapper。");
            }
        }
    }

    /**
     * SINGLETON 模式注册：无 @KeyExtractor，创建唯一单例实例。
     */
    private void registerSingleton(Class<?> wrapperClass,
                                   Map<Class<? extends Event>, List<HandlerTemplate>> handlers) {
        Object singleton = createSingleton(wrapperClass);

        WrapperRegistration reg = new WrapperRegistration(
                wrapperClass, null, null,
                Collections.emptyMap(), handlers, HandlerMode.SINGLETON, singleton
        );
        classToReg.put(wrapperClass, reg);
        addToRegistry(reg);
    }

    /**
     * STATIC 模式注册：全 static handler，走 EventEngine 兜底链。
     * <p>
     * 不进入 registry，直接为每个 static handler 生成 EventConsumer 走 engine.subscribeTail。
     * STATIC 不参与优先级排序和独占逻辑，始终兜底最后执行。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerStatic(Class<?> wrapperClass,
                                Map<Class<? extends Event>, List<HandlerTemplate>> handlers) {
        List<StaticConsumer> consumers = new ArrayList<>();

        for (Map.Entry<Class<? extends Event>, List<HandlerTemplate>> entry : handlers.entrySet()) {
            Class<? extends Event> eventType = entry.getKey();

            for (HandlerTemplate template : entry.getValue()) {
                // STATIC 忽略 priority/exclusive，提示日志
                if (template.getPriority() != EventPriority.NORMAL || template.isExclusive()) {
                    JFrameLog.info("HandlerRegistry",
                            "STATIC 模式忽略 priority/exclusive 属性: "
                                    + wrapperClass.getName() + "." + template.getMethod().getName()
                                    + "（STATIC 始终兜底最后执行，不参与优先级/独占）");
                }

                EventConsumer consumer = event -> {
                    template.handle(null, event); // static 方法无需 receiver
                    return false;
                };
                engine.subscribeTail(eventType, consumer);
                consumers.add(new StaticConsumer(eventType, consumer));
            }
        }

        staticConsumers.put(wrapperClass, consumers);
    }

    // ========== 注销 ==========

    /**
     * 注销整个包装类的所有处理器。
     * <p>
     * <b>内部 API</b>：用户应通过 {@link EventAPI#unregister} 调用。
     *
     * @param wrapperClass 要注销的包装类
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unregister(Class<?> wrapperClass) {
        // OBJECT/SINGLETON
        WrapperRegistration reg = classToReg.remove(wrapperClass);
        if (reg != null) {
            // 从所有事件类型列表中移除
            for (List<WrapperRegistration> regs : registry.values()) {
                regs.removeIf(r -> r == reg);
            }

            // 清理默认缓存
            if (reg.defaultCache() != null) {
                reg.defaultCache().clear();
            }
        }

        // STATIC：注销兜底消费者
        List<StaticConsumer> statics = staticConsumers.remove(wrapperClass);
        if (statics != null) {
            for (StaticConsumer sc : statics) {
                engine.unsubscribe((Class) sc.eventType(), (EventConsumer) sc.consumer());
            }
        }
    }

    /**
     * 从默认缓存中驱逐特定身份的实例。
     * <p>
     * 仅对使用默认缓存（无 @InstanceProvider）的包装类有效。
     * 使用自定义 @InstanceProvider 的包装类需自行管理缓存清理。
     * <p>
     * <b>内部 API</b>：用户应通过 {@link EventAPI#evict} 调用。
     *
     * @param wrapperClass 包装类
     * @param identity     要驱逐的身份标识
     */
    public void evict(Class<?> wrapperClass, Object identity) {
        WrapperRegistration reg = classToReg.get(wrapperClass);
        if (reg != null && reg.defaultCache() != null) {
            reg.defaultCache().remove(identity);
        }
    }

    // ========== 分发 ==========

    /**
     * 分发事件给所有匹配的处理器。
     * <p>
     * 由 {@link EventAPI} 在 Nukkit 事件触发时回调。
     *
     * @param eventType 事件类型
     * @param event     事件对象
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatch(Class<? extends Event> eventType, Event event) {
        CopyOnWriteArrayList<WrapperRegistration> regs = registry.get(eventType);
        if (regs == null || regs.isEmpty()) return;

        // ① 收集所有匹配的 (instance, template) 对
        List<BoundHandler> matched = new ArrayList<>();
        Set<Object> dispatched = Collections.newSetFromMap(new IdentityHashMap<>());

        for (WrapperRegistration reg : regs) {
            // 对象处理器：提取身份 → 获取/创建实例
            collectObjectHandlers(matched, dispatched, reg, eventType, event);
        }

        if (matched.isEmpty()) return;

        // ② 按优先级降序排序（HIGHEST 在前）
        matched.sort(Comparator.comparing(
                (BoundHandler bh) -> bh.template().getPriority(),
                Comparator.reverseOrder()
        ));

        // ③ 分组分发 + 跨优先级独占
        EventPriority currentGroup = null;
        boolean exclusiveClaimed = false;

        for (BoundHandler bh : matched) {
            EventPriority handlerPriority = bh.template().getPriority();

            // 检查是否进入新的优先级组
            if (currentGroup != null && handlerPriority != currentGroup) {
                if (exclusiveClaimed) {
                    break; // 上一组有独占声明，停止所有低优先级
                }
                currentGroup = handlerPriority;
                exclusiveClaimed = false;
            }
            if (currentGroup == null) {
                currentGroup = handlerPriority;
            }

            try {
                if (bh.template().handle(bh.instance(), event)) {
                    exclusiveClaimed = true;
                }
            } catch (Exception e) {
                // 用 declaringClass 而非 instance.getClass()，避免 instance 为 null 时二次 NPE，
                // 同时保持错误定位信息一致（指向声明处理器的类）。
                JFrameLog.error("HandlerRegistry",
                        "事件处理器异常: " + bh.template().getDeclaringClass().getName()
                                + "." + bh.template().getMethod().getName(), e);
            }
        }
    }

    /**
     * 收集对象处理器的匹配 handler。
     * 支持多槽位（多个 @KeyExtractor，OR 语义）。
     */
    private void collectObjectHandlers(List<BoundHandler> matched, Set<Object> dispatched,
                                       WrapperRegistration reg,
                                       Class<? extends Event> eventType, Event event) {
        // SINGLETON 模式：直接用单例实例，按 reg 去重
        if (reg.mode() == HandlerMode.SINGLETON) {
            Object instance = reg.singletonInstance();
            if (instance != null && dispatched.add(reg)) {
                addHandlers(matched, instance, reg, eventType);
            }
            return;
        }

        // OBJECT 模式：提取身份 → 获取/创建实例，按 instance 去重
        List<Method> extractors = reg.extractors().get(eventType);
        if (extractors == null || extractors.isEmpty()) {
            return; // 该事件类型无 @KeyExtractor，无法路由到此 wrapper
        }

        // 提取器：逐个尝试，每个产生不同实例都可能匹配（OR 语义）
        for (Method extractor : extractors) {
            try {
                Object key = extractor.invoke(null, event); // static 方法
                if (key == null) continue;

                Object instance = getOrCreateInstance(reg, key);
                if (instance != null && dispatched.add(instance)) {
                    addHandlers(matched, instance, reg, eventType);
                }
            } catch (Exception e) {
                JFrameLog.error("HandlerRegistry",
                        "KeyExtractor 调用失败: " + reg.wrapperClass().getName()
                                + "." + extractor.getName(), e);
            }
        }
    }

    /**
     * 将 Registration 中该事件类型的所有 handler 模板绑定到实例，加入 matched 列表。
     */
    private void addHandlers(List<BoundHandler> matched, Object instance,
                             WrapperRegistration reg, Class<? extends Event> eventType) {
        List<HandlerTemplate> templates = reg.handlers().get(eventType);
        if (templates != null) {
            for (HandlerTemplate template : templates) {
                matched.add(new BoundHandler(instance, template));
            }
        }
    }

    // ========== 实例获取/创建 ==========

    /**
     * 获取或创建实例。
     * <p>
     * 有 {@link InstanceProvider} → 调用工厂方法。
     * 无 → 使用默认缓存（构造函数 + ConcurrentHashMap）。
     *
     * @param reg      类注册信息
     * @param identity 身份标识
     * @return 实例，或 null（工厂返回 null 或创建失败）
     */
    private Object getOrCreateInstance(WrapperRegistration reg, Object identity) {
        if (reg.instanceProvider() != null) {
            // 自定义工厂
            try {
                return reg.instanceProvider().invoke(null, identity);
            } catch (Exception e) {
                JFrameLog.error("HandlerRegistry",
                        "InstanceProvider 调用失败: " + reg.wrapperClass().getName(), e);
                return null;
            }
        }

        // 默认缓存
        Object cached = reg.defaultCache().get(identity);
        if (cached != null) return cached;

        try {
            Object instance = constructViaConstructor(reg.wrapperClass(), identity);
            if (instance != null) {
                reg.defaultCache().put(identity, instance);
            }
            return instance;
        } catch (Exception e) {
            JFrameLog.error("HandlerRegistry",
                    "默认实例创建失败: " + reg.wrapperClass().getName(), e);
            return null;
        }
    }

    /**
     * 通过构造函数创建实例。
     * <p>
     * 查找第一个参数兼容身份类型的构造函数。
     * 额外参数：{@link EventEngine} / {@link HandlerRegistry} 类型自动注入
     * （用户应优先注入 {@code EventEngine} 作为内部引擎入口）。
     */
    private Object constructViaConstructor(Class<?> wrapperClass, Object identity) throws Exception {
        Constructor<?> matched = null;
        for (Constructor<?> ctor : wrapperClass.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length >= 1 && params[0].isInstance(identity)) {
                matched = ctor;
                break;
            }
        }

        if (matched == null) {
            throw new IllegalStateException(
                    "在 " + wrapperClass.getName() + " 中找不到兼容 "
                            + identity.getClass().getName() + " 的构造函数。"
                            + "请提供 @InstanceProvider 或添加匹配的构造函数。");
        }

        matched.setAccessible(true);
        Object[] args = new Object[matched.getParameterCount()];
        args[0] = identity;

        // 注入额外依赖
        for (int i = 1; i < args.length; i++) {
            Class<?> paramType = matched.getParameterTypes()[i];
            if (paramType == EventEngine.class) {
                args[i] = engine;
            } else if (paramType == HandlerRegistry.class) {
                args[i] = this;
            }
            // 其他类型暂留 null（可扩展 Spring DI）
        }

        return matched.newInstance(args);
    }

    /**
     * 创建 SINGLETON 模式的单例实例。
     * <p>
     * 优先使用无参 @InstanceProvider（参数数为 0 的 static 工厂方法），
     * 其次使用无参构造函数。
     *
     * @param wrapperClass 包装类
     * @return 单例实例
     * @throws IllegalStateException 如果无法创建（无无参构造且无无参工厂）
     */
    private Object createSingleton(Class<?> wrapperClass) {
        // 优先：无参 @InstanceProvider
        Method provider = scanNoArgInstanceProvider(wrapperClass);
        if (provider != null) {
            try {
                Object instance = provider.invoke(null);
                if (instance != null) return instance;
                JFrameLog.warning("HandlerRegistry",
                        "SINGLETON InstanceProvider 返回 null: " + wrapperClass.getName()
                                + "，回退到无参构造");
            } catch (Exception e) {
                JFrameLog.error("HandlerRegistry",
                        "SINGLETON InstanceProvider 调用失败: " + wrapperClass.getName(), e);
            }
        }

        // 无参构造
        try {
            Constructor<?> ctor = wrapperClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "SINGLETON 模式需要无参构造函数或无参 @InstanceProvider: "
                            + wrapperClass.getName(), e);
        }
    }

    /**
     * 扫描无参 @InstanceProvider 方法（static，参数数为 0）。
     *
     * @return 无参工厂方法，或 null
     */
    private Method scanNoArgInstanceProvider(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(InstanceProvider.class)) continue;
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException(
                            "@InstanceProvider 方法必须是 static: " + current.getName()
                                    + "." + method.getName());
                }
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    // ========== 注解扫描 ==========

    /**
     * 扫描类中的 @KeyExtractor 方法（static）。
     *
     * @return 事件类型 → 提取方法列表
     */
    private Map<Class<? extends Event>, List<Method>> scanExtractors(Class<?> clazz) {
        Map<Class<? extends Event>, List<Method>> result = new HashMap<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(KeyExtractor.class)) continue;
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException(
                            "@KeyExtractor 方法必须是 static: " + current.getName()
                                    + "." + method.getName());
                }
                method.setAccessible(true);
                Class<? extends Event> eventType = resolveEventTypeFromParam(method);
                result.computeIfAbsent(eventType, k -> new ArrayList<>()).add(method);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    /**
     * 扫描类中的 @InstanceProvider 方法（static）。
     *
     * @return 工厂方法，或 null（无 @InstanceProvider）
     */
    private Method scanInstanceProvider(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(InstanceProvider.class)) continue;
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException(
                            "@InstanceProvider 方法必须是 static: " + current.getName()
                                    + "." + method.getName());
                }
                method.setAccessible(true);
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 扫描类中的 @EventHandler + @EventRoute 方法，创建 HandlerTemplate。
     *
     * @param clazz       要扫描的类
     * @param targetClass 声明处理器的类（用于 filter 方法查找）
     * @return 事件类型 → HandlerTemplate 列表
     */
    private Map<Class<? extends Event>, List<HandlerTemplate>> scanHandlers(
            Class<?> clazz, Class<?> targetClass) {
        Map<Class<? extends Event>, List<HandlerTemplate>> result = new HashMap<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                EventHandler handlerAnno = method.getAnnotation(EventHandler.class);
                if (handlerAnno == null) continue;

                EventRoute routeAnno = method.getAnnotation(EventRoute.class);
                if (routeAnno == null) {
                    routeAnno = DEFAULT_ROUTE; // 无 @EventRoute 时用默认值
                }

                HandlerTemplate template = new HandlerTemplate(targetClass, method, routeAnno, handlerAnno);
                result.computeIfAbsent(template.getEventType(), k -> new ArrayList<>()).add(template);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    // ========== 工具方法 ==========

    /**
     * 将 Registration 添加到核心存储，并为每个事件类型订阅 EventService。
     */
    private void addToRegistry(WrapperRegistration reg) {
        // 收集所有涉及的事件类型（handlers 和 extractors 的并集）
        Set<Class<? extends Event>> allEventTypes = ConcurrentHashMap.newKeySet();
        allEventTypes.addAll(reg.handlers().keySet());
        allEventTypes.addAll(reg.extractors().keySet());

        for (Class<? extends Event> eventType : allEventTypes) {
            registry.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(reg);
            ensureSubscribed(eventType);
        }
    }

    /**
     * 确保已向 EventService 订阅指定事件类型。
     * 每个事件类型只订阅一次。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ensureSubscribed(Class<? extends Event> eventType) {
        if (!subscribed.add(eventType)) return;

        EventConsumer consumer = event -> {
            dispatch(eventType, event);
            return false; // EventService 层面非独占，独占逻辑由 HandlerRegistry 内部管理
        };
        engine.subscribe(eventType, (EventConsumer) consumer);
    }

    /**
     * 从方法参数推断事件类型。
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Event> resolveEventTypeFromParam(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || !Event.class.isAssignableFrom(params[0])) {
            throw new IllegalArgumentException(
                    "@KeyExtractor 方法 " + method.getName()
                            + " 的第一个参数必须是 Event 子类。");
        }
        return (Class<? extends Event>) params[0];
    }

    /** 默认 @EventRoute 实例（无 @EventRoute 注解时使用） */
    private static final EventRoute DEFAULT_ROUTE = new EventRoute() {
        @Override public Class<? extends Event> value() { return Event.class; }
        @Override public String condition() { return ""; }
        @Override public String filter() { return ""; }
        @Override public Class<? extends EventRoute> annotationType() { return EventRoute.class; }
    };
}
