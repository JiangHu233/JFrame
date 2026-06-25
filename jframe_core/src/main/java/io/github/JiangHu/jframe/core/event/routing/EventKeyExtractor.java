package io.github.JiangHu.jframe.core.event.routing;

import cn.nukkit.event.Event;

/**
 * 事件身份Key提取器。
 * <p>
 * 从 Nukkit 事件中提取"身份Key"，用于将事件路由到正确的包装类实例。
 * 例如：
 * <ul>
 *   <li>{@code PlayerEvent} → 提取 {@code event.getPlayer()}（Player 对象）</li>
 *   <li>{@code BlockEvent} → 提取 {@code event.getBlock()}（Block 对象）</li>
 *   <li>{@code EntityEvent} → 提取 {@code event.getEntity()}（Entity 对象）</li>
 * </ul>
 * 返回 {@code null} 表示该事件没有身份Key（全局事件，不按对象路由）。
 *
 * @param <T> 事件类型
 * @see KeyExtractorRegistry
 */
@FunctionalInterface
public interface EventKeyExtractor<T extends Event> {

    /**
     * 从事件中提取身份Key。
     *
     * @param event 事件对象
     * @return 身份Key（如 Player、Block），或 {@code null} 表示无Key
     */
    Object extract(T event);
}
