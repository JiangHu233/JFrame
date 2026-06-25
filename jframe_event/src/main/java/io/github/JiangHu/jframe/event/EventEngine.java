package io.github.JiangHu.jframe.event;

import cn.nukkit.Server;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.event.routing.HandlerRegistry;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件引擎（<b>内部模块</b>）：对接 Nukkit 底层事件系统，按事件类型精准订阅和分发。
 * <p>
 * 本类是事件系统的 L1 引擎层，只负责"按类型订阅"和"转发给消费者"，
 * <b>不包含</b>面向用户的注册 API（{@code register/unregister/evict}）。
 * 用户应通过 {@link EventAPI} 间接使用本类。
 * <p>
 * <b>优先级排序和独占逻辑由上层 {@link HandlerRegistry} 管理</b>，
 * 本类以固定 {@link EventPriority#LOWEST} 优先级向 Nukkit 注册，
 * 确保所有消费者在同一优先级层被调用，从而让 HandlerRegistry 能完整控制分发顺序。
 *
 * <h3>为什么固定 LOWEST？</h3>
 * Nukkit 按优先级从 LOWEST 到 MONITOR 分层调用。如果本类在不同优先级注册，
 * Nukkit 会分多次调用 dispatch，导致 HandlerRegistry 无法在一次调用中完成
 * 跨优先级的独占判断。固定 LOWEST 后，所有事件只触发一次 dispatch，
 * HandlerRegistry 内部按 @EventHandler 的 priority 排序并处理独占。
 *
 * <h3>架构定位</h3>
 * <pre>
 *   EventEngine（本类，无依赖）
 *       ↑
 *   HandlerRegistry（构造器依赖 EventEngine）
 *       ↑
 *   EventAPI（公开门面，依赖两者）
 * </pre>
 * 这种单向依赖（DAG）避免了原先 EventAPI ↔ HandlerRegistry 的循环依赖，
 * 使得三者均可使用构造器注入，Spring 能正常创建。
 *
 * @see EventAPI
 * @see EventConsumer
 * @see HandlerRegistry
 */
public class EventEngine implements Listener, PluginAware {

    /**
     * 固定注册优先级。
     * <p>
     * 使用 LOWEST 确保本引擎在 Nukkit 事件管线的最早阶段被调用，
     * 之后由 HandlerRegistry 内部按 @EventHandler 的 priority 排序分发。
     */
    public static final EventPriority REGISTER_PRIORITY = EventPriority.LOWEST;

    /**
     * 消费者注册表：事件类型 → 消费者列表。
     * <p>
     * 外层 {@link ConcurrentHashMap} 保证线程安全，
     * 内层 {@link CopyOnWriteArrayList} 保证遍历时线程安全（读多写少场景）。
     */
    private final Map<Class<? extends Event>, List<EventConsumer<?>>> consumers =
            new ConcurrentHashMap<>();

    /**
     * 已向 Nukkit 注册的事件类型集合，避免重复注册。
     */
    private final Set<Class<? extends Event>> registered = ConcurrentHashMap.newKeySet();

    /**
     * 待注册队列：在 {@link #plugin} 设置之前，订阅请求会被暂存于此，
     * 等 {@link #bindPlugin(Plugin)} 调用后一次性刷新到 Nukkit。
     */
    private final List<Class<? extends Event>> pendingRegistrations = new ArrayList<>();

    /**
     * 关联的插件实例，用于向 Nukkit 注册事件处理器。
     */
    @Getter
    private Plugin plugin;

    /**
     * 订阅指定事件类型。
     * <p>
     * 底层以固定 {@link #REGISTER_PRIORITY} 向 Nukkit 注册。
     * 同一事件类型只向 Nukkit 注册一次，多个消费者共享同一注册。
     *
     * @param eventType 事件类型
     * @param consumer  事件消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void subscribe(Class<T> eventType, EventConsumer<T> consumer) {
        consumers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(consumer);
        ensureRegistered(eventType);
    }

    /**
     * 注销指定事件类型下的某个消费者。
     *
     * @param eventType 事件类型
     * @param consumer  要注销的消费者
     * @param <T>       事件泛型
     */
    public <T extends Event> void unsubscribe(Class<T> eventType, EventConsumer<T> consumer) {
        List<EventConsumer<?>> list = consumers.get(eventType);
        if (list != null) {
            list.remove(consumer);
        }
    }

    /**
     * 注销某个消费者在所有事件类型上的订阅。
     *
     * @param consumer 要注销的消费者
     */
    public void unsubscribeAll(EventConsumer<?> consumer) {
        consumers.values().forEach(list -> list.remove(consumer));
    }

    /**
     * 绑定关联插件实例，并刷新所有待注册的订阅。
     * <p>
     * 实现自 {@link PluginAware}。{@code JFrameMain} 在导入事件模块
     * （或插件启用）后会自动调用本方法，将插件实例注入进来。
     * <p>
     * 在 Spring 容器初始化阶段，{@code HandlerRegistry} 可能在
     * plugin 设置之前就调用 subscribe（注册包装类时触发的事件订阅）。
     * 此时订阅会被暂存，等本方法调用后统一注册。
     *
     * @param plugin 插件实例
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return; // 已绑定
        }

        this.plugin = plugin;
        if (plugin != null && !pendingRegistrations.isEmpty()) {
            for (Class<? extends Event> eventType : pendingRegistrations) {
                doRegister(eventType);
            }
            pendingRegistrations.clear();
        }
    }

    /**
     * 向 Nukkit 注册事件处理器（每个事件类型只注册一次）。
     */
    private void ensureRegistered(Class<? extends Event> eventType) {
        if (!registered.add(eventType)) {
            return; // 已注册
        }
        if (plugin == null) {
            pendingRegistrations.add(eventType);
            return;
        }
        doRegister(eventType);
    }

    /**
     * 实际向 Nukkit PluginManager 注册事件处理器。
     */
    private void doRegister(Class<? extends Event> eventType) {
        Server.getInstance().getPluginManager().registerEvent(
                eventType, this, REGISTER_PRIORITY,
                (listener, event) -> dispatch(eventType, event),
                plugin, false);
    }

    /**
     * 分发事件给订阅了指定事件类型的所有消费者。
     * <p>
     * 消费者按注册顺序调用。单个消费者的异常会被捕获并记录，不影响其他消费者。
     *
     * @param eventType 事件类型
     * @param event     事件对象
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(Class<? extends Event> eventType, Event event) {
        List<EventConsumer<?>> list = consumers.get(eventType);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (EventConsumer consumer : list) {
            try {
                consumer.handleEvent(event);
            } catch (Exception e) {
                Server.getInstance().getLogger().error(
                        "EventConsumer 处理事件 " + eventType.getSimpleName() + " 时发生异常", e);
            }
        }
    }
}
