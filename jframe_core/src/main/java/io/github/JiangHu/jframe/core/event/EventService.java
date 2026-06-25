package io.github.JiangHu.jframe.core.event;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.plugin.Plugin;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件服务：基于 Nukkit 的动态事件分发中心。
 * <p>
 * 允许在运行时订阅任意 Nukkit 事件类型。底层利用 Nukkit 原生的
 * {@code PluginManager.registerEvent} 进行注册，Nukkit 会按事件类型
 * 自动过滤——只有订阅了该事件类型的 {@link EventConsumer} 才会被调用，
 * 避免了 catch-all 遍历带来的性能开销。
 * <p>
 * <b>使用前必须调用 {@link #setPlugin(Plugin)} 设置关联插件。</b>
 * 通常在插件主类的 {@code onEnable()} 中完成：
 * <pre>{@code
 * EventService service = applicationContext.getBean(EventService.class);
 * service.setPlugin(this);
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 订阅玩家移动事件（类型安全，无需强转）
 * eventService.subscribe(PlayerMoveEvent.class, event -> {
 *     event.getPlayer().sendMessage("你移动了！");
 *     return false; // 非独占，继续传递
 * });
 *
 * // 按优先级订阅
 * eventService.subscribe(BlockBreakEvent.class, EventPriority.HIGH, event -> {
 *     event.setCancelled(true); // 取消事件
 *     return true;              // 独占，停止同优先级的后续分发
 * });
 * }</pre>
 *
 * @see EventConsumer
 * @see EventPriority
 */
@Component
public class EventService implements Listener {

    /**
     * 消费者注册表：事件类型 → (优先级 → 消费者列表)。
     * <p>
     * 使用 {@link ConcurrentHashMap} 保证线程安全，
     * 内部列表使用 {@link CopyOnWriteArrayList} 保证遍历时的线程安全。
     */
    private final Map<Class<? extends Event>, EnumMap<EventPriority, List<EventConsumer<?>>>> consumers =
            new ConcurrentHashMap<>();

    /**
     * 已向 Nukkit 注册的 (事件类型 + 优先级) 组合，避免重复注册。
     */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /**
     * 待注册队列：在 {@link #plugin} 设置之前，订阅请求会被暂存于此，
     * 等 {@link #setPlugin(Plugin)} 调用后一次性刷新到 Nukkit。
     * <p>
     * 这使得 Spring 容器初始化阶段（BeanPostProcessor 扫描 @NukkitEvent）
     * 可以先于 Plugin 设置完成订阅，不会抛出异常。
     */
    private final List<Registration> pendingRegistrations = new ArrayList<>();

    /**
     * 关联的插件实例，用于向 Nukkit 注册事件处理器。
     * <p>
     * 设置后会自动刷新所有待注册的订阅。
     */
    @Getter
    private Plugin plugin;

    /**
     * 订阅指定事件类型（默认优先级 {@link EventPriority#NORMAL}）。
     *
     * @param eventType 事件类型（如 {@code PlayerMoveEvent.class}）
     * @param consumer  事件消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void subscribe(Class<T> eventType, EventConsumer<T> consumer) {
        subscribe(eventType, EventPriority.NORMAL, consumer);
    }

    /**
     * 订阅指定事件类型（自定义优先级）。
     * <p>
     * Nukkit 会按优先级顺序（{@link EventPriority#LOWEST} → {@link EventPriority#MONITOR}）
     * 依次调用各优先级的处理器。同一优先级内，消费者按注册顺序调用。
     *
     * @param eventType 事件类型
     * @param priority  事件优先级
     * @param consumer  事件消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void subscribe(Class<T> eventType, EventPriority priority, EventConsumer<T> consumer) {
        consumers
                .computeIfAbsent(eventType, k -> new EnumMap<>(EventPriority.class))
                .computeIfAbsent(priority, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
        ensureRegistered(eventType, priority);
    }

    /**
     * 注销指定事件类型 + 优先级下的某个消费者。
     *
     * @param eventType 事件类型
     * @param priority  事件优先级
     * @param consumer  要注销的消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void unsubscribe(Class<T> eventType, EventPriority priority, EventConsumer<T> consumer) {
        EnumMap<EventPriority, List<EventConsumer<?>>> byPriority = consumers.get(eventType);
        if (byPriority != null) {
            List<EventConsumer<?>> list = byPriority.get(priority);
            if (list != null) {
                list.remove(consumer);
            }
        }
    }

    /**
     * 注销指定事件类型下所有优先级的某个消费者。
     *
     * @param eventType 事件类型
     * @param consumer  要注销的消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void unsubscribe(Class<T> eventType, EventConsumer<T> consumer) {
        EnumMap<EventPriority, List<EventConsumer<?>>> byPriority = consumers.get(eventType);
        if (byPriority != null) {
            byPriority.values().forEach(list -> list.remove(consumer));
        }
    }

    /**
     * 注销某个消费者在所有事件类型、所有优先级上的订阅。
     *
     * @param consumer 要注销的消费者
     */
    public void unsubscribeAll(EventConsumer<?> consumer) {
        consumers.values().forEach(byPriority ->
                byPriority.values().forEach(list -> list.remove(consumer)));
    }

    /**
     * 设置关联插件实例，并刷新所有待注册的订阅。
     * <p>
     * 在 Spring 容器初始化阶段，{@code EventBeanPostProcessor} 可能会在
     * plugin 设置之前扫描到 {@code @NukkitEvent} Bean 并调用 {@link #subscribe}。
     * 此时订阅会被暂存到 {@link #pendingRegistrations}，等本方法调用后统一注册。
     *
     * @param plugin 插件实例
     */
    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
        if (plugin != null && !pendingRegistrations.isEmpty()) {
            for (Registration reg : pendingRegistrations) {
                doRegister(reg.eventType, reg.priority);
            }
            pendingRegistrations.clear();
        }
    }

    /**
     * 向 Nukkit 注册事件处理器（每个 事件类型 + 优先级 组合只注册一次）。
     * <p>
     * 如果 {@link #plugin} 尚未设置，则将注册请求暂存到待注册队列，
     * 等 {@link #setPlugin(Plugin)} 调用后再统一执行。
     */
    private void ensureRegistered(Class<? extends Event> eventType, EventPriority priority) {
        String key = eventType.getName() + "@" + priority.name();
        if (!registered.add(key)) {
            return; // 该组合已注册
        }
        if (plugin == null) {
            // 延迟注册：暂存到队列，等 setPlugin() 后统一刷新
            pendingRegistrations.add(new Registration(eventType, priority));
            return;
        }
        doRegister(eventType, priority);
    }

    /**
     * 实际向 Nukkit PluginManager 注册事件处理器。
     */
    private void doRegister(Class<? extends Event> eventType, EventPriority priority) {
        Server.getInstance().getPluginManager().registerEvent(
                eventType, this, priority,
                (listener, event) -> dispatch(eventType, priority, event),
                plugin, false);
    }

    /**
     * 待注册项：暂存事件类型和优先级。
     */
    private record Registration(Class<? extends Event> eventType, EventPriority priority) {
    }

    /**
     * 分发事件给订阅了指定 (事件类型 + 优先级) 的所有消费者。
     * <p>
     * 消费者按注册顺序调用；若某消费者返回 {@code true}（独占处理），
     * 则停止向同优先级的后续消费者分发。
     * <p>
     * 单个消费者的异常会被捕获并记录日志，不影响其他消费者。
     *
     * @param eventType 事件类型
     * @param priority  优先级
     * @param event     事件对象
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(Class<? extends Event> eventType, EventPriority priority, Event event) {
        EnumMap<EventPriority, List<EventConsumer<?>>> byPriority = consumers.get(eventType);
        if (byPriority == null) {
            return;
        }
        List<EventConsumer<?>> list = byPriority.get(priority);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (EventConsumer consumer : list) {
            try {
                if (consumer.handleEvent(event)) {
                    return; // 独占处理，停止同优先级分发
                }
            } catch (Exception e) {
                Server.getInstance().getLogger().error(
                        "EventConsumer 处理事件 " + eventType.getSimpleName()
                                + " (优先级: " + priority + ") 时发生异常", e);
            }
        }
    }
}
