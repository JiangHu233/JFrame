package io.github.JiangHu.jframe.transform.annotation;

import io.github.JiangHu.jframe.transform.HookPriority;
import io.github.JiangHu.jframe.transform.bridge.HookAt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级钩子声明注解（AOP 声明式层，设计方案 §3.4.5）——标注在插件类的方法上，
 * 经 {@code TransformPluginScope.scan(basePackage)} 包扫描注册。
 * <p>
 * 宿主类为普通类（无需实现任何接口）；一个类可挂任意多个无关 hook；含非 static
 * 注解方法的宿主类以无参构造实例化一次（实例字段 = 跨方法共享状态）。
 *
 * <h3>签名规则（注册期校验，不匹配 fail-fast）</h3>
 * <ul>
 *   <li>{@code at = HEAD} / {@code RETURN}：方法必须 {@code (HookContext) → void}</li>
 *   <li>{@code at = REPLACE}：签名镜像目标——同参同返回值，首参 = 目标实例
 *       （static 目标无实例参）；方法名/目标名不支持通配</li>
 * </ul>
 *
 * <pre>{@code
 * public class NukkitPatches {
 *     @HookOn(target = "cn.nukkit.Server", method = "getMotd", at = HookAt.RETURN)
 *     public void motdPrefix(HookContext ctx) { ctx.setReturn("[增强] " + ctx.getReturn()); }
 *
 *     @HookOn(target = "cn.nukkit.Player", method = "attackEntity", at = HookAt.REPLACE)
 *     public boolean critStreak(Player self, Entity target, double damage) {
 *         return self.kill(target, damage * 2);
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HookOn {

    /**
     * 目标类全限定名（支持 {@code *} 简单通配；REPLACE 不支持通配）。
     *
     * @return 类名模式
     */
    String target();

    /**
     * 目标方法名（支持 {@code *} 简单通配；REPLACE 不支持通配）。
     *
     * @return 方法名模式
     */
    String method();

    /**
     * 目标参数类型（空 = 任意重载；消除重载歧义）。
     *
     * @return 参数类型
     */
    Class<?>[] params() default {};

    /**
     * 相位（HEAD = before / RETURN = after / REPLACE = 整方法接管）。
     *
     * @return 相位
     */
    HookAt at() default HookAt.HEAD;

    /**
     * 优先级（同方法多钩子 HIGHEST→LOWEST；多 REPLACE 最高优先级胜出）。
     *
     * @return 优先级
     */
    HookPriority priority() default HookPriority.NORMAL;

    /**
     * before 阶段是否需要参数数组（false 免装箱；REPLACE 恒为 true，属性值忽略）。
     *
     * @return 是否需要参数
     */
    boolean needsArgs() default false;
}
