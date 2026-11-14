package io.github.JiangHu.jframe.core.module.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工厂类声明注解 — 声明一个"多产品工厂"：类内若干
 * {@link JBean @JBean} 方法各产出一个 Bean，容器在<b>首次需要其任一产物时</b>
 * 才实例化工厂类（public 无参构造，扫描期校验），全部产物懒加载单例。
 * <p>
 * {@code @JBean} 方法约束（扫描注册期 fail-fast 校验）：
 * <ul>
 *   <li>public、非 void，static 或实例方法均可</li>
 *   <li>参数只能为空，或仅有一个 {@code ModuleContext}
 *       （唯一允许的"注入"——B 持有 A 通过 {@code ctx.get(A.class)} 显式拉取，
 *       容器不做任意参数的反射注入，且保证拿到同一单例）</li>
 * </ul>
 * 与产品类上的 {@link JBean @JBean} 互斥：同一类不得同时标注两者。
 *
 * @see JBean
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JFactoryClass {
}
