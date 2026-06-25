package io.github.JiangHu.jframe.core.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可序列化数据标记注解。
 * <p>
 * 用于标注某个字段或类型属于「需要持久化保存的数据」——
 * 即在服务重启后仍需恢复的数据，会被序列化写入磁盘（如配置、玩家存档等）。
 * <p>
 * 与 {@link RuntimeData @RuntimeData} 相对：后者标记的是仅运行时有效的临时数据，
 * 不参与序列化。
 * <p>
 * 该注解采用 {@link RetentionPolicy#SOURCE 源码级保留策略}，
 * 即编译后不会保留在字节码中，仅供编译期（如注解处理器）使用。
 * 可作用于字段（{@link ElementType#FIELD}）或类型（{@link ElementType#TYPE}）。
 *
 * @see RuntimeData
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface SerializableData {

    /**
     * 可选的描述信息，用于补充说明该数据的用途。
     *
     * @return 描述文本，默认为空字符串
     */
    String value() default "";
}
