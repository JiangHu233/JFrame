package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 回收器类声明注解 — 标记一个类为实体回收器并指定注册名。
 *
 * <p>配合 {@code ecs.forPlugin(plugin).scan("包名")} 自动发现注册。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.despawner.Despawner}</li>
 *   <li>公共无参构造</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsDespawner("arena-recycle")
 * public class ArenaRecycleDespawner implements Despawner {
 *     private boolean broadcast = true;   // despawn.params.broadcast 注入
 *     @Override public void despawn(DespawnContext ctx) {
 *         if (!(ctx.product() instanceof ArenaRef ref)) return;
 *         Files.deleteIfExists(ref.markerPath());
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsDespawner {

    /**
     * 回收器注册名（定义文件 despawn.use 引用）。
     *
     * @return 唯一名；空串时使用类简单名
     */
    String value() default "";
}
