package io.github.JiangHu.jframe.core.module.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工厂方法声明注解 — 标注在 {@link JBean @JBean} 产品类内部的
 * <b>static 无参</b>方法上，容器改用该方法构造实例（而非无参 new）。
 * <p>
 * 约束（扫描注册期 fail-fast 校验）：
 * <ul>
 *   <li>必须 {@code static}</li>
 *   <li>必须无参</li>
 *   <li>返回类型须与产品类兼容</li>
 *   <li>每个产品类最多一个 {@code @JFactory} 方法</li>
 * </ul>
 * 需要容器中其他对象的复杂构造请改用 {@link JFactoryClass @JFactoryClass} 工厂类。
 *
 * @see JBean
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JFactory {
}
