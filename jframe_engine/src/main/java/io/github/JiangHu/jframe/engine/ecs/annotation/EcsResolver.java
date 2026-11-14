package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 宿主解析器类声明注解 — 标记一个类为事件宿主解析器并指定注册名。
 *
 * <p>配合 {@code ecs.forPlugin(plugin).scan("包名")} 自动发现注册。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver}</li>
 *   <li>公共无参构造</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsResolver("chest-holder")
 * public class ChestHolderResolver implements EcsEventResolver {
 *     @Override public Class<? extends Event> eventType() { return InventoryOpenEvent.class; }
 *     @Override public List<Object> extractHosts(Event event) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsResolver {

    /**
     * 解析器注册名（仅作标识与日志）。
     *
     * @return 唯一名；空串时使用类简单名
     */
    String value() default "";
}
