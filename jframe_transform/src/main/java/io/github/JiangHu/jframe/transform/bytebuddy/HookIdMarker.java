package io.github.JiangHu.jframe.transform.bytebuddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Advice 模板参数的 hookId 常量绑定标记——织入时经
 * {@code Advice.withCustomMapping().bind(HookIdMarker.class, id)} 把分发 id 以
 * int 常量内联进目标方法体（不引用模板类静态量，无跨类加载器泄漏）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HookIdMarker {
}
