package io.github.JiangHu.jframe.db.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单行数据（可变构建器 / 读取视图）— {@link RelationalTable} 读写的数据载体。
 * <p>
 * 内部为<b>有序</b>键值对（LinkedHashMap，列序 = {@link TableDef#columns()} 序），
 * 值类型由列的 {@link ColumnType} 决定（见该枚举的类型表）。
 * {@link #key()} 返回主键列值的字符串形式。
 *
 * @see RelationalTable#upsert(Row)
 * @see RelationalTable#get(String)
 */
public final class Row {

    /** 主键列名 */
    private final String keyColumn;

    /** 有序键值对 */
    private final LinkedHashMap<String, Object> values;

    /**
     * 构造行。
     *
     * @param keyColumn 主键列名
     * @param values    键值对（拷贝入内）
     */
    public Row(String keyColumn, Map<String, Object> values) {
        this.keyColumn = Objects.requireNonNull(keyColumn, "keyColumn");
        this.values = new LinkedHashMap<>(values);
    }

    /**
     * 基于表定义创建空行。
     *
     * @param def 表定义
     * @return 空行（随后用 {@link #put} 填充）
     */
    public static Row empty(TableDef def) {
        return new Row(def.keyColumn(), new LinkedHashMap<>());
    }

    /**
     * 基于表定义包装既有键值对。
     *
     * @param def    表定义
     * @param values 键值对
     * @return 行
     */
    public static Row of(TableDef def, Map<String, Object> values) {
        return new Row(def.keyColumn(), values);
    }

    /**
     * 填充一列（构建期使用）。
     *
     * @param column 列名
     * @param value  列值（类型由 ColumnType 决定）
     * @return this（链式）
     */
    public Row put(String column, Object value) {
        values.put(column, value);
        return this;
    }

    /**
     * 读取一列。
     *
     * @param column 列名
     * @return 列值（缺失为 null）
     */
    public Object get(String column) {
        return values.get(column);
    }

    /** @return 主键值的字符串形式 */
    public String key() {
        Object v = values.get(keyColumn);
        return v == null ? null : String.valueOf(v);
    }

    /** @return 内部键值对（只读视图） */
    public Map<String, Object> values() {
        return Map.copyOf(values);
    }

    /** @return 内部键值对（有序可变副本） */
    public LinkedHashMap<String, Object> copyValues() {
        return new LinkedHashMap<>(values);
    }

    /** @return 主键列名 */
    public String keyColumn() {
        return keyColumn;
    }

    @Override
    public String toString() {
        return "Row" + values;
    }
}
