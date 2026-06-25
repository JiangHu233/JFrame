package io.github.JiangHu.jframe.core.event.routing;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.core.event.EventService;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 对象事件路由器（L3 核心引擎）。
 * <p>
 * 将 Nukkit 事件按"身份Key"路由到对应的包装类实例。支持两种注册模式：
 *
 * <h3>1. 全局处理器（Spring 单例 Bean）</h3>
 * 接收该事件类型的<b>所有</b>实例，不按Key过滤。
 * <pre>{@code
 * @Component
 * public class GlobalChatLogger {
 *     @NukkitEvent(PlayerChatEvent.class)
 *     public void onChat(PlayerChatEvent event) { ... }
 * }
 * // EventBeanPostProcessor 自动扫描注册，无需手动调用
 * }</pre>
 *
 * <h3>2. 对象级处理器（动态包装类实例）</h3>
 * 只接收与绑定Key匹配的事件。
 * <pre>{@code
 * public class PlayerWrapper {
 *     private final Player player;
 *
 *     @NukkitEvent(PlayerMoveEvent.class)
 *     public void onMove(PlayerMoveEvent event) { ... }
 * }
 *
 * // 玩家上线时注册
 * PlayerWrapper wrapper = new PlayerWrapper(player);
 * router.register(wrapper, player);  // Key = Player 对象
 *
 * // 玩家下线时注销
 * router.unregister(wrapper);
 * }</pre>
 *
 * <h3>3. 自定义路由处理器（per-wrapper 自定义 Key 提取器）</h3>
 * 当需要按非默认维度路由时（如按物品、按区域），使用 {@link RoutingSpec} 指定自定义提取器。
 * <pre>{@code
 * // 按"物品"路由 PlayerInteractEvent（而非默认的"玩家"）
 * RoutingSpec spec = RoutingSpec.create()
 *         .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);
 * router.register(magicSwordWrapper, swordItem, spec);
 * }</pre>
 *
 * <h3>路由流程</h3>
 * <pre>
 * Nukkit 事件触发
 *     ↓
 * EventService 调用 ObjectEventRouter.dispatch(eventType, priority, event)
 *     ↓
 *     ├─ ① 查找全局处理器 → 逐个调用
 *     ├─ ② 全局提取器提取 Key（如 Player）→ 查找 Key 绑定的处理器 → 逐个调用
 *     └─ ③ 自定义提取器逐条提取 Key → 匹配则调用（支持 per-wrapper 自定义维度）
 * </pre>
 *
 * <h3>线程安全</h3>
 * 所有内部集合使用 {@link ConcurrentHashMap} 和 {@link CopyOnWriteArrayList}，
 * 支持在任意线程注册/注销，在主线程分发。
 *
 * @see KeyExtractorRegistry
 * @see AnnotatedHandler
 * @see EventService
 */
@Component
public class ObjectEventRouter {

    private final KeyExtractorRegistry keyExtractorRegistry;
    private final EventService eventService;

    /**
     * 全局处理器：事件类型 → 处理器列表（含所有优先级）。
     */
    private final Map<Class<? extends Event>, CopyOnWriteArrayList<AnnotatedHandler>> globalHandlers =
            new ConcurrentHashMap<>();

    /**
     * 对象级处理器：事件类型 → (Key → 处理器列表)。
     */
    private final Map<Class<? extends Event>, ConcurrentHashMap<Object, CopyOnWriteArrayList<AnnotatedHandler>>> keyedHandlers =
            new ConcurrentHashMap<>();

    /**
     * 反向索引：包装类实例 → 其所有处理器（用于按实例注销）。
     */
    private final Map<Object, List<AnnotatedHandler>> wrapperToHandlers = new ConcurrentHashMap<>();

    /**
     * 已向 EventService 注册的 (事件类型 + 优先级) 组合，避免重复注册。
     */
    private final Set<String> subscribedCombos = ConcurrentHashMap.newKeySet();

    /**
     * 自定义提取器的对象级处理器：事件类型 → [CustomRoutingEntry]。
     * <p>
     * 当 wrapper 使用 {@link RoutingSpec} 注册时，其处理器存放在此，
     * 而非 {@link #keyedHandlers}（后者使用全局提取器）。
     */
    private final Map<Class<? extends Event>, CopyOnWriteArrayList<CustomRoutingEntry>> customKeyedHandlers =
            new ConcurrentHashMap<>();

    public ObjectEventRouter(KeyExtractorRegistry keyExtractorRegistry, EventService eventService) {
        this.keyExtractorRegistry = keyExtractorRegistry;
        this.eventService = eventService;
    }

    // ==================== 注册 ====================

    /**
     * 注册全局处理器（扫描 Bean 中所有 {@link NukkitEvent} 方法）。
     * <p>
     * 通常由 {@code EventBeanPostProcessor} 自动调用，无需手动注册。
     *
     * @param bean Spring Bean 实例
     */
    public void registerGlobal(Object bean) {
        List<AnnotatedHandler> handlers = scanHandlers(bean);
        for (AnnotatedHandler handler : handlers) {
            globalHandlers
                    .computeIfAbsent(handler.getEventType(), k -> new CopyOnWriteArrayList<>())
                    .add(handler);
            ensureSubscribed(handler.getEventType(), handler.getPriority());
        }
    }

    /**
     * 注册对象级处理器，绑定到指定 Key。
     * <p>
     * 扫描包装类实例中所有 {@link NukkitEvent} 方法，将它们注册为
     * 仅接收与 {@code key} 匹配的事件。
     *
     * @param wrapper 包装类实例
     * @param key     身份Key（如 Player、Block 对象）
     */
    public void register(Object wrapper, Object key) {
        register(wrapper, key, null);
    }

    /**
     * 注册对象级处理器，绑定到指定 Key，可选使用自定义 Key 提取器。
     * <p>
     * 扫描包装类实例中所有 {@link NukkitEvent} 方法。对于 {@code spec} 中
     * 指定了自定义提取器的事件类型，处理器将使用自定义提取器路由（存入
     * {@link #customKeyedHandlers}）；其余使用全局提取器（存入 {@link #keyedHandlers}）。
     * <p>
     * 这使得不同 wrapper 可以按不同维度路由同一事件类型：
     * <ul>
     *   <li>wrapper A 按 Player 路由 PlayerInteractEvent（全局提取器）</li>
     *   <li>wrapper B 按 Item 路由 PlayerInteractEvent（自定义提取器）</li>
     * </ul>
     *
     * @param wrapper 包装类实例
     * @param key     身份Key（如 Player、Item 对象，或区域 ID 字符串）
     * @param spec    自定义路由规格（null 或空则全部使用全局提取器）
     */
    public void register(Object wrapper, Object key, RoutingSpec spec) {
        List<AnnotatedHandler> handlers = scanHandlers(wrapper);
        if (handlers.isEmpty()) {
            return;
        }
        wrapperToHandlers.computeIfAbsent(wrapper, k -> new CopyOnWriteArrayList<>()).addAll(handlers);
        for (AnnotatedHandler handler : handlers) {
            EventKeyExtractor<?> customExtractor =
                    (spec != null) ? spec.getExtractor(handler.getEventType()) : null;
            if (customExtractor != null) {
                // 使用自定义提取器
                customKeyedHandlers
                        .computeIfAbsent(handler.getEventType(), k -> new CopyOnWriteArrayList<>())
                        .add(new CustomRoutingEntry(handler, key, customExtractor));
            } else {
                // 使用全局提取器
                keyedHandlers
                        .computeIfAbsent(handler.getEventType(), k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(handler);
            }
            ensureSubscribed(handler.getEventType(), handler.getPriority());
        }
    }

    // ==================== 注销 ====================

    /**
     * 按包装类实例注销其所有处理器。
     *
     * @param wrapper 包装类实例
     */
    public void unregister(Object wrapper) {
        List<AnnotatedHandler> handlers = wrapperToHandlers.remove(wrapper);
        if (handlers == null) {
            return;
        }
        for (AnnotatedHandler handler : handlers) {
            removeHandlerFromGlobal(handler);
            removeHandlerFromKeyed(handler);
            removeHandlerFromCustom(handler);
        }
    }

    /**
     * 按 Key 注销所有绑定到该 Key 的处理器。
     *
     * @param key 身份Key（如 Player 对象）
     */
    public void unregisterByKey(Object key) {
        for (ConcurrentHashMap<Object, CopyOnWriteArrayList<AnnotatedHandler>> keyMap : keyedHandlers.values()) {
            List<AnnotatedHandler> handlers = keyMap.remove(key);
            if (handlers == null) {
                continue;
            }
            for (AnnotatedHandler handler : handlers) {
                List<AnnotatedHandler> wrapperHandlers = wrapperToHandlers.get(handler.getTarget());
                if (wrapperHandlers != null) {
                    wrapperHandlers.remove(handler);
                }
            }
        }
    }

    /**
     * 注销所有处理器（全局 + 对象级）。
     */
    public void unregisterAll() {
        globalHandlers.clear();
        keyedHandlers.clear();
        customKeyedHandlers.clear();
        wrapperToHandlers.clear();
    }

    // ==================== 分发 ====================

    /**
     * 分发事件给匹配的处理器。
     * <p>
     * 由 {@link EventService} 的消费者回调，按以下顺序调用：
     * <ol>
     *   <li>全局处理器（匹配事件类型 + 优先级）</li>
     *   <li>对象级处理器 — 全局提取器（匹配事件类型 + Key + 优先级）</li>
     *   <li>对象级处理器 — 自定义提取器（逐条提取 Key 匹配 + 优先级）</li>
     * </ol>
     * <p>
     * 注意：三个阶段独立执行，阶段②的全局提取器不存在不影响阶段③。
     *
     * @param eventType 事件类型
     * @param priority  优先级
     * @param event     事件对象
     */
    @SuppressWarnings("unchecked")
    public void dispatch(Class<? extends Event> eventType, EventPriority priority, Event event) {
        // ① 全局处理器
        CopyOnWriteArrayList<AnnotatedHandler> globals = globalHandlers.get(eventType);
        if (globals != null && !globals.isEmpty()) {
            for (AnnotatedHandler handler : globals) {
                if (handler.getPriority() == priority) {
                    handler.handle(event);
                }
            }
        }

        // ② 对象级处理器（全局Key提取器路由）
        EventKeyExtractor<Event> extractor =
                (EventKeyExtractor<Event>) keyExtractorRegistry.findExtractor(eventType);
        if (extractor != null) {
            Object key = extractor.extract(event);
            if (key != null) {
                ConcurrentHashMap<Object, CopyOnWriteArrayList<AnnotatedHandler>> keyMap =
                        keyedHandlers.get(eventType);
                if (keyMap != null) {
                    CopyOnWriteArrayList<AnnotatedHandler> keyed = keyMap.get(key);
                    if (keyed != null && !keyed.isEmpty()) {
                        for (AnnotatedHandler handler : keyed) {
                            if (handler.getPriority() == priority) {
                                handler.handle(event);
                            }
                        }
                    }
                }
            }
        }

        // ③ 自定义提取器的对象级处理器（per-wrapper 自定义维度）
        CopyOnWriteArrayList<CustomRoutingEntry> customs = customKeyedHandlers.get(eventType);
        if (customs != null && !customs.isEmpty()) {
            for (CustomRoutingEntry entry : customs) {
                if (entry.handler().getPriority() != priority) {
                    continue;
                }
                EventKeyExtractor<Event> customExtractor =
                        (EventKeyExtractor<Event>) entry.extractor();
                Object customKey = customExtractor.extract(event);
                if (customKey != null && customKey.equals(entry.key())) {
                    entry.handler().handle(event);
                }
            }
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 扫描目标对象中所有 {@link NukkitEvent} 标注的方法，创建处理器列表。
     * <p>
     * 遍历类及其所有父类（直到 Object），查找标注方法。
     */
    private List<AnnotatedHandler> scanHandlers(Object target) {
        List<AnnotatedHandler> handlers = new ArrayList<>();
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                NukkitEvent annotation = method.getAnnotation(NukkitEvent.class);
                if (annotation != null) {
                    try {
                        handlers.add(new AnnotatedHandler(target, method, annotation));
                    } catch (Exception e) {
                        Server.getInstance().getLogger().error(
                                "创建事件处理器失败: " + clazz.getName() + "." + method.getName(), e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return handlers;
    }

    /**
     * 确保 EventService 已订阅指定 (事件类型 + 优先级)。
     * <p>
     * 每个 (事件类型 + 优先级) 组合只向 EventService 注册一次。
     */
    private void ensureSubscribed(Class<? extends Event> eventType, EventPriority priority) {
        String combo = eventType.getName() + "@" + priority.name();
        if (!subscribedCombos.add(combo)) {
            return; // 已注册
        }
        eventService.subscribe(eventType, priority,
                event -> {
                    dispatch(eventType, priority, event);
                    return false; // 非独占，允许 EventService 继续分发
                });
    }

    /**
     * 从全局处理器列表中移除指定处理器。
     */
    private void removeHandlerFromGlobal(AnnotatedHandler handler) {
        CopyOnWriteArrayList<AnnotatedHandler> list = globalHandlers.get(handler.getEventType());
        if (list != null) {
            list.remove(handler);
        }
    }

    /**
     * 从对象级处理器中移除指定处理器（遍历所有Key）。
     */
    private void removeHandlerFromKeyed(AnnotatedHandler handler) {
        ConcurrentHashMap<Object, CopyOnWriteArrayList<AnnotatedHandler>> keyMap =
                keyedHandlers.get(handler.getEventType());
        if (keyMap != null) {
            for (CopyOnWriteArrayList<AnnotatedHandler> list : keyMap.values()) {
                list.remove(handler);
            }
        }
    }

    /**
     * 从自定义提取器处理器中移除指定处理器。
     */
    private void removeHandlerFromCustom(AnnotatedHandler handler) {
        CopyOnWriteArrayList<CustomRoutingEntry> list =
                customKeyedHandlers.get(handler.getEventType());
        if (list != null) {
            list.removeIf(entry -> entry.handler().equals(handler));
        }
    }

    /**
     * 自定义路由条目：处理器 + 绑定Key + 该条目专用的 Key 提取器。
     * <p>
     * 每个条目携带自己的提取器，因此同一事件类型下不同 wrapper
     * 可以使用完全不同的 Key 提取逻辑。
     *
     * @param handler  注解处理器
     * @param key      注册时绑定的身份Key
     * @param extractor 该条目专用的 Key 提取器（来自 {@link RoutingSpec}）
     */
    private record CustomRoutingEntry(
            AnnotatedHandler handler,
            Object key,
            EventKeyExtractor<?> extractor
    ) {}
}
