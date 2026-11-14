package io.github.JiangHu.jframe.event.annotation;

import cn.nukkit.event.Event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 事件路由筛选注解 — 匹配层。
 * <p>
 * 声明一个方法处理什么事件、在什么条件下处理。
 * 必须与 {@link EventHandler} 组合使用。
 *
 * <h3>属性说明</h3>
 * <ul>
 *   <li>{@link #value()} — 事件类型。不填则从方法第一个参数自动推断</li>
 *   <li>{@link #filter()} — 引用同类中的 boolean 筛选方法名</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 自动推断事件类型 + 无条件
 * @EventRoute
 * @EventHandler
 * public void onChat(PlayerChatEvent event) { ... }
 *
 * // 指定事件类型 + filter 方法引用
 * @EventRoute(value = PlayerInteractEvent.class, filter = "isRightClick")
 * @EventHandler
 * public void onRightClick(PlayerInteractEvent event) { ... }
 * }</pre>
 *
 * @see EventHandler
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventRoute {

    /**
     * 事件类型。
     * <p>
     * 默认 {@code Event.class} 为哨兵值，表示从方法第一个参数自动推断。
     *
     * @return 事件类型
     */
    Class<? extends Event> value() default Event.class;

    /**
     * 筛选方法名引用。
     * <p>
     * 引用同类中签名为 {@code boolean method(EventType)} 的方法。
     * 返回 true 时处理器才执行。可以是 private 方法。
     *
     * @return 方法名
     */
    String filter() default "";
}
