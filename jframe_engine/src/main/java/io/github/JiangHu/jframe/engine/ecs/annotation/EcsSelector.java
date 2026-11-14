package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 选择器类声明注解 — 标记一个类为实体选择器并指定注册名。
 *
 * <p>配合 {@code ecs.forPlugin(plugin).scan("包名")} 自动发现注册。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector}</li>
 *   <li>公共无参构造</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsSelector("near-spawn")
 * public class NearSpawnSelector implements EntitySelector {
 *     private double distance = 32;   // selector.params.distance 注入
 *     @Override public boolean matches(EcsEntity e) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsSelector {

    /**
     * 选择器注册名（定义文件 selector.use 引用）。
     *
     * @return 唯一名；空串时使用类简单名
     */
    String value() default "";
}
