package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 生成器类声明注解 — 标记一个类为实体生成器并指定注册名。
 *
 * <p>配合 {@code ecs.forPlugin(plugin).scan("包名")} 自动发现注册。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.spawner.Spawner}</li>
 *   <li>公共无参构造</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsSpawner("ghost-pack")
 * public class GhostPackSpawner implements Spawner {
 *     private int count = 3;   // spawn.params.count 注入
 *     @Override public Object spawn(SpawnContext ctx) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsSpawner {

    /**
     * 生成器注册名（定义文件 spawn.use 引用）。
     *
     * @return 唯一名；空串时使用类简单名
     */
    String value() default "";
}
