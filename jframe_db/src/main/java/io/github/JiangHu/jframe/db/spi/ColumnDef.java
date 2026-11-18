package io.github.JiangHu.jframe.db.spi;

import java.util.Objects;

/**
 * 单列定义（不可变值对象）— {@link TableDef} 的成员。
 * <p>
 * 列名、逻辑类型（{@link ColumnType}，方言中立）、索引/唯一标记，
 * 以及 {@code @Column} 声明的 DDL 参数（length / precision / scale）。
 * 适配器据此类渲染真实 DDL。
 *
 * @see TableDef
 * @see RelationalStore#ensureTable(TableDef)
 */
public final class ColumnDef {

    /** 列名（= 实体字段的 @SaveField 别名） */
    private final String name;

    /** 逻辑类型 */
    private final ColumnType type;

    /** 是否建索引 */
    private final boolean indexed;

    /** 是否唯一约束 */
    private final boolean unique;

    /** STRING 列长度（&lt;0 表示未指定，由适配器按方言决定；如 MySQL 无 length 的 STRING → TEXT） */
    private final int length;

    /** DECIMAL 精度（默认 10） */
    private final int precision;

    /** DECIMAL 标度（默认 2） */
    private final int scale;

    /**
     * 全参构造。
     *
     * @param name      列名
     * @param type      逻辑类型
     * @param indexed   是否索引
     * @param unique    是否唯一
     * @param length    STRING 长度（-1 未指定）
     * @param precision DECIMAL 精度
     * @param scale     DECIMAL 标度
     */
    public ColumnDef(String name, ColumnType type, boolean indexed, boolean unique,
                      int length, int precision, int scale) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.indexed = indexed;
        this.unique = unique;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
    }

    /**
     * 创建普通列（无索引、无唯一、参数缺省）。
     *
     * @param name 列名
     * @param type 逻辑类型
     * @return 列定义
     */
    public static ColumnDef of(String name, ColumnType type) {
        return new ColumnDef(name, type, false, false, -1, 10, 2);
    }

    /**
     * 创建主键列（VARCHAR 语义，jframe_db v1 主键统一字符串）。
     *
     * @param name 主键列名
     * @return 列定义
     */
    public static ColumnDef key(String name) {
        return new ColumnDef(name, ColumnType.STRING, true, true, 191, 10, 2);
    }

    /**
     * @return 派生的索引列副本
     */
    public ColumnDef withIndexed() {
        return new ColumnDef(name, type, true, unique, length, precision, scale);
    }

    /**
     * @return 派生的唯一列副本
     */
    public ColumnDef withUnique() {
        return new ColumnDef(name, type, indexed, true, length, precision, scale);
    }

    /**
     * @return 派生的指定长度副本（STRING 列用）
     */
    public ColumnDef length(int newLength) {
        return new ColumnDef(name, type, indexed, unique, newLength, precision, scale);
    }

    /**
     * @return 派生的指定精度/标度副本（DECIMAL 列用）
     */
    public ColumnDef decimal(int newPrecision, int newScale) {
        return new ColumnDef(name, type, indexed, unique, length, newPrecision, newScale);
    }

    /** @return 列名 */
    public String name() {
        return name;
    }

    /** @return 逻辑类型 */
    public ColumnType type() {
        return type;
    }

    /** @return 是否索引 */
    public boolean indexed() {
        return indexed;
    }

    /** @return 是否唯一 */
    public boolean unique() {
        return unique;
    }

    /** @return STRING 长度（-1 未指定） */
    public int length() {
        return length;
    }

    /** @return DECIMAL 精度 */
    public int precision() {
        return precision;
    }

    /** @return DECIMAL 标度 */
    public int scale() {
        return scale;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDef that)) return false;
        return length == that.length && precision == that.precision && scale == that.scale
                && indexed == that.indexed && unique == that.unique
                && name.equals(that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, indexed, unique, length, precision, scale);
    }

    @Override
    public String toString() {
        return name + ":" + type + (indexed ? "(idx)" : "") + (unique ? "(uniq)" : "")
                + (length >= 0 ? "(len=" + length + ")" : "")
                + (type == ColumnType.DECIMAL ? "(p=" + precision + ",s=" + scale + ")" : "");
    }
}
