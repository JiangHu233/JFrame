package io.github.JiangHu.jframe.event.annotation;

import cn.nukkit.event.EventPriority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 事件处理器注解 — 执行层。
 * <p>
 * 标记一个方法为事件处理器，并声明其执行优先级和独占语义。
 * 必须与 {@link EventRoute} 组合使用。
 *
 * <h3>属性说明</h3>
 * <ul>
 *   <li>{@link #priority()} — 执行优先级，框架按 HIGHEST → LOWEST 降序分发</li>
 *   <li>{@link #exclusive()} — 跨优先级独占：执行后停止所有低优先级处理器</li>
 * </ul>
 *
 * <h3>优先级顺序</h3>
 * <pre>
 * HIGHEST → HIGH → NORMAL → LOW → LOWEST → MONITOR
 * </pre>
 * <p>
 * 高优先级先执行。若高优先级处理器声明 {@code exclusive = true}，
 * 则所有低优先级处理器都不会执行。
 *
 * <h3>独占语义</h3>
 * <pre>{@code
 * // HIGH 优先级独占：执行后 NORMAL/LOW 等都不执行
 * @EventRoute
 * @EventHandler(priority = EventPriority.HIGH, exclusive = true)
 * public void onChatHigh(PlayerChatEvent event) { ... }
 *
 * // 默认：NORMAL 优先级，非独占
 * @EventRoute
 * @EventHandler
 * public void onChat(PlayerChatEvent event) { ... }
 * }</pre>
 *
 * @see EventRoute
 * @see EventPriority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {

    /**
     * 执行优先级。
     * <p>
     * 框架内部按优先级从高到低分发。同一优先级内的多个处理器，
     * 执行顺序不明确（如需明确顺序请使用不同优先级）。
     *
     * @return 优先级
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * 跨优先级独占。
     * <p>
     * 若为 {@code true} 且该方法执行了（通过了条件检查），
     * 则停止向所有低优先级的处理器分发该事件。
     * <p>
     * 注意：独占只影响比当前优先级更低的处理器，
     * 同一优先级内的其他处理器仍会执行。
     *
     * @return 是否独占
     */
    boolean exclusive() default false;
}
