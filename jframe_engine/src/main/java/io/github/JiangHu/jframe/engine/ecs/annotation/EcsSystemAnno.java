package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 系统类声明注解 — 标记一个类为 ECS 系统以便包扫描发现。
 *
 * <p>注解名带 {@code Anno} 后缀是为避免与 {@link io.github.JiangHu.jframe.engine.ecs.system.EcsSystem}
 * 接口重名（用户系统类同时出现在 implements 与注解位置）。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.system.EcsSystem}</li>
 *   <li>公共无参构造</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsSystemAnno
 * public class MagicChestSystem implements EcsSystem {
 *     @Override public int interval() { return 20; }
 *     @Override public void tick(EcsEngine engine) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsSystemAnno {
}
