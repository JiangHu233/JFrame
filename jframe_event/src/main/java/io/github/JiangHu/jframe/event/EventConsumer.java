package io.github.JiangHu.jframe.event;

import cn.nukkit.event.Event;

/**
 * 事件消费者接口。
 * <p>
 * 定义对特定类型 Nukkit 事件的处理逻辑。泛型参数 {@code T} 指定消费者
 * 所处理的事件类型，调用时无需手动强转。
 * <p>
 * 配合 {@link EventAPI#subscribe(Class, EventConsumer)} 使用，
 * 只有订阅了对应事件类型的消费者才会被调用（由 Nukkit 原生按事件类型过滤）。
 *
 * @param <T> 处理的事件类型
 * @see EventAPI
 * @see Event
 */
@FunctionalInterface
public interface EventConsumer<T extends Event> {

    /**
     * 处理一个事件。
     *
     * @param event 待处理事件（类型安全，无需强转）
     * @return 返回值在 {@link EventAPI} 层面被忽略（始终继续分发）。
     *         优先级排序和独占逻辑由 {@code HandlerRegistry} 内部管理。
     */
    boolean handleEvent(T event);
}
