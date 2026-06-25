package io.github.JiangHu.jframe.core.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 运行时数据标记注解。
 * <p>
 * 用于标注某个字段或类型属于「运行时数据」——即仅在服务运行期间有效、
 * 不需要持久化保存到磁盘的临时数据。
 * <p>
 * 该注解采用 {@link RetentionPolicy#SOURCE 源码级保留策略}，
 * 即编译后不会保留在字节码中，仅供编译期（如注解处理器）使用。
 * 可作用于字段（{@link ElementType#FIELD}）或类型（{@link ElementType#TYPE}）。
 *
 * @see SerializableData
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface RuntimeData {

    /**
     * 可选的描述信息，用于补充说明该数据的用途。
     *
     * @return 描述文本，默认为空字符串
     */
    String value() default "";
}
