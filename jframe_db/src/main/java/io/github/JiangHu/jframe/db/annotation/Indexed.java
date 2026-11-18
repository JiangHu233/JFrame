package io.github.JiangHu.jframe.db.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 索引列标记 — 标注在 {@code @SaveField} 字段上，为其对应列建立索引，
 * 使 {@code DataManager#findBy / findByIn} 可按该列查询。
 * <p>
 * 必须与 {@link io.github.JiangHu.jframe.serialize.annotation.SaveField @SaveField}
 * 同时使用（该注解只是列的修饰符，不单独构成持久化）。
 * <p>
 * 索引 String 列未指定 {@link Column @Column(length)} 时，适配器按索引长度
 * 惯例处理（如 MySQL utf8mb4 → VARCHAR(191)）。
 *
 * <pre>{@code
 * public class PlayerData implements SaveIdentifiable {
 *     @SaveField @Indexed
 *     private String name;   // 真实列 + 索引 → players.findBy("name", "Steve")
 *     ...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Indexed {
}
