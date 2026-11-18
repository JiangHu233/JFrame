package io.github.JiangHu.jframe.db.annotation;

import io.github.JiangHu.jframe.db.spi.ColumnType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 列类型声明 — 自动建表的<b>类型参数</b>补充（不标则按字段 Java 类型自动推断）。
 * <p>
 * 类型推断只能从 Java 类型推「类别」，推不出「参数」；本注解补齐三件事：
 * <ul>
 *   <li>{@link #length()}：String 列长度（VARCHAR(50) vs TEXT 框架猜不出）</li>
 *   <li>{@link #type()}：逻辑类型显式覆盖（如把 String 强制 TEXT、普通列强制 JSON）</li>
 *   <li>{@link #precision()} / {@link #scale()}：DECIMAL 精度/标度（金额类字段）</li>
 * </ul>
 * <b>列名不进本注解</b>——列名 = {@code @SaveField} 别名，单一事实来源。
 * 必须与 {@code @SaveField} 同时使用；仅作用于实体自身标量字段，
 * {@code @ChildTable} / {@code @RelationTable} 子表列用推断默认。
 *
 * <pre>{@code
 * @SaveField @Indexed @Column(length = 50)  private String name;    // VARCHAR(50) + 索引
 * @SaveField @Column(type = ColumnType.TEXT) private String bio;    // 强制 TEXT
 * @SaveField @Column(precision = 10, scale = 2) private BigDecimal balance;  // DECIMAL(10,2)
 * @SaveField private int level;   // 不标 → 自动推断 INT，绝大多数字段零负担
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {

    /**
     * STRING 列长度（-1 = 未指定，由适配器按方言决定：无 length → TEXT，
     * 索引列无 length → VARCHAR(191)）。
     *
     * @return 长度，默认 -1
     */
    int length() default -1;

    /**
     * 逻辑类型显式覆盖（{@link ColumnType#AUTO} = 按字段类型推断）。
     *
     * @return 逻辑类型，默认 AUTO
     */
    ColumnType type() default ColumnType.AUTO;

    /**
     * DECIMAL 精度。
     *
     * @return 精度，默认 10
     */
    int precision() default 10;

    /**
     * DECIMAL 标度。
     *
     * @return 标度，默认 2
     */
    int scale() default 2;
}
