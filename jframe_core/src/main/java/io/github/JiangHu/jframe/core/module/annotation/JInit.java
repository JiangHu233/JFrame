package io.github.JiangHu.jframe.core.module.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean 二次初始化入口注解（配合 {@link ModuleManager#boot} 的批量引导使用）。
 * <p>
 * 标注在 {@link JBean @JBean} 产品类的<strong>一个</strong>方法上。boot 引导分两阶段：
 * <ol>
 *   <li><b>构造阶段</b>：全部 bean 无参构造并注册（构造期零依赖）；</li>
 *   <li><b>二次初始化阶段</b>：按扫描顺序统一调用各 bean 的 {@code @JInit} 方法——
 *       此时所有 bean 实例已存在，方法内通过 {@code ctx.get(X.class)} 绑定引用、
 *       {@code ctx.propXxx(...)} 读取配置，字段间循环引用天然免疫。</li>
 * </ol>
 *
 * <h3>方法约束（引导期 fail-fast 校验）</h3>
 * <ul>
 *   <li>public、非 static、返回 void；</li>
 *   <li>参数无或仅一个 {@link io.github.JiangHu.jframe.core.module.BeanContext BeanContext}；</li>
 *   <li>每类至多一个 {@code @JInit} 方法。</li>
 * </ul>
 *
 * <h3>使用约定</h3>
 * 方法体<b>只绑定引用与读取配置</b>，禁止调用其它 bean 的业务方法或读取其状态——
 * 其它 bean 的 {@code @JInit} 可能尚未执行（其字段仍为默认值）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @JBean
 * public class CombatService {
 *     private GameConfig config;
 *     private HudService hud;
 *
 *     @JInit
 *     public void init(BeanContext ctx) {
 *         this.config = ctx.get(GameConfig.class);
 *         this.hud = ctx.get(HudService.class);
 *     }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JInit {
}
