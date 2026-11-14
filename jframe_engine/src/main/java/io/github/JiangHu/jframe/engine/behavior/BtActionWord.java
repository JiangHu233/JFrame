package io.github.JiangHu.jframe.engine.behavior;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行为词表注解 — 声明类是行为树的可复用行为叶（YAML 树以 {@link #value()}
 * 词名引用），经 {@code BtAPI.forPlugin().scan()} 注册（与 @EcsComponent 同款体验）。
 *
 * <p>实现须继承 {@link BtAction}；公有非静态字段即参数（字段名=YAML params 键，
 * 注册表反射注入，写错键名加载期即报——与 ECS ParamBinder 同款契约）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BtActionWord {

    /**
     * 词名（YAML 树引用键；命名惯例 {@code 域:动词}，如 "ai:fire"）。
     *
     * @return 词名
     */
    String value();
}
