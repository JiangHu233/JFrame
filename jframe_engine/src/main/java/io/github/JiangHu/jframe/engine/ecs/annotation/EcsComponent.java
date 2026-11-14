package io.github.JiangHu.jframe.engine.ecs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组件类声明注解 — 标记一个类为 ECS 组件并指定注册 id。
 *
 * <p>配合 {@code ecs.forPlugin(plugin).scan("包名")} 自动发现注册，
 * 也可手动 {@code ecs.registerComponent(id, 类)} 注册。
 *
 * <h3>要求</h3>
 * <ul>
 *   <li>实现 {@link io.github.JiangHu.jframe.engine.ecs.entity.Component}（通常继承 ComponentBase）</li>
 *   <li>公共无参构造（引擎按宿主粒度反射实例化）</li>
 * </ul>
 *
 * <pre>{@code
 * @EcsComponent("magic-alarm")
 * public class MagicAlarm extends ComponentBase {
 *     private String sound = "random.orb";   // params.sound 可覆盖
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EcsComponent {

    /**
     * 组件注册 id（定义文件 components[].id 引用）。
     *
     * @return 唯一 id；空串时使用类简单名
     */
    String value() default "";

    /**
     * 是否参与实例池化（默认 false = 每次挂载反射新建，行为与无池一致）。
     * <p>设为 true 的组件须遵守重置契约：{@code onUnmount} 后引擎在归池前调用
     * {@link io.github.JiangHu.jframe.engine.ecs.entity.Component#reset()}，
     * 组件作者负责清空 params 注入之外累积的运行时字段；框架注入字段
     * （host / mountedFrom）由引擎强制清空。仅高频生灭的组件建议开启。
     *
     * @return 池化 true
     */
    boolean pooled() default false;
}
