package io.github.JiangHu.jframe.event.annotation;

import io.github.JiangHu.jframe.event.EventAPI;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 包装类声明注解（类级别）— 类似 Spring 的 {@code @Component}。
 * <p>
 * 标记一个类为<b>事件包装类</b>，使其可被 {@link io.github.JiangHu.jframe.event.scan.WrapperScanner}
 * 自动发现并注册。配合 {@link EventService#scan} 使用，可免去逐个手动
 * {@code register(Class)} 的样板代码。
 *
 * <h3>与 Spring 的类比</h3>
 * <pre>
 *   Spring:     @Component  +  包扫描（@ComponentScan）  →  自动注册为 Bean
 *   JFrame:     @Wrapper    +  包扫描（eventService.scan） →  自动注册为事件处理器
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Wrapper
 * public class PlayerStatWrapper {
 *
 *     @KeyExtractor
 *     public static Player extract(PlayerMoveEvent event) {
 *         return event.getPlayer();
 *     }
 *
 *     @EventRoute @EventHandler
 *     public void onMove(PlayerMoveEvent event) { ... }
 * }
 *
 * // 在插件主类中：扫描整个 wrapper 包，自动注册所有 @Wrapper 类
 * eventService.scan("io.github.JiangHu.jframe.example.wrapper");
 * }</pre>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>{@code @Wrapper} 仅作为<b>发现标记</b>，不改变注册行为</li>
 *   <li>被标记的类仍需声明 {@link KeyExtractor}（必需）和 {@link EventHandler}（必需），
 *       否则即使被扫描到也会被注册逻辑拒绝</li>
 *   <li>接口、抽象类、注解、枚举即使标记了 {@code @Wrapper} 也会被扫描器跳过</li>
 *   <li>未标记 {@code @Wrapper} 的类不会被自动扫描，但仍可手动 {@code register(Class)}</li>
 * </ul>
 *
 * @see KeyExtractor
 * @see EventHandler
 * @see io.github.JiangHu.jframe.event.scan.WrapperScanner
 * @see EventAPI#scan
 */

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Wrapper {

    /**
     * 包装类名称（可选）。
     * <p>
     * 类似 Spring {@code @Component("name")}，用于为包装类指定一个语义化名称。
     * 当前版本仅用于日志标识与未来按名查找的扩展点，不影响注册逻辑。
     * <p>
     * 默认为空字符串，表示使用类的全限定名。
     *
     * @return 包装类名称，默认空
     */
    String value() default "";
}
