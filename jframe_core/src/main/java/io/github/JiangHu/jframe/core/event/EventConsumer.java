package io.github.JiangHu.jframe.core.event;

import cn.nukkit.event.Event;

/**
 * 事件消费者接口。
 * <p>
 * 定义对特定类型 Nukkit 事件的处理逻辑。泛型参数 {@code T} 指定消费者
 * 所处理的事件类型，调用时无需手动强转。
 * <p>
 * 配合 {@link EventService#subscribe(Class, EventConsumer)} 使用，
 * 只有订阅了对应事件类型的消费者才会被调用（由 Nukkit 原生按事件类型过滤）。
 *
 * @param <T> 处理的事件类型
 * @see EventService
 * @see Event
 */
@FunctionalInterface
public interface EventConsumer<T extends Event> {

    /**
     * 处理一个事件。
     *
     * @param event 待处理事件（类型安全，无需强转）
     * @return 是否独占事件：若返回 {@code true}，则停止向同优先级的
     *         其他消费者分发该事件
     */
    boolean handleEvent(T event);
}
