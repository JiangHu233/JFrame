package io.github.JiangHu.jframe.engine.behavior;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 条件词表注解 — 声明类是行为树的可复用条件叶（YAML 树以 {@link #value()}
 * 词名引用），经 {@code BtAPI.forPlugin().scan()} 注册。
 *
 * <p>实现须继承 {@link BtCondition}；公有非静态字段即参数（反射注入）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BtConditionWord {

    /**
     * 词名（命名惯例 {@code 域:谓词}，如 "ecs:has-component"）。
     *
     * @return 词名
     */
    String value();
}
