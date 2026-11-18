package io.github.JiangHu.jframe.db.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 表定义（不可变值对象）— jframe_db 向适配器描述一张表应长什么样。
 * <p>
 * 由映射层（EntityMapping）从实体类解析产出；适配器在
 * {@link RelationalStore#ensureTable(TableDef)} 中据此渲染 DDL
 * （建表幂等：不存在则建，缺列则补列 ALTER）。
 * <p>
 * 列集合<b>包含</b>主键列（类型 STRING，v1 主键统一字符串）。
 * 列名为实体字段的 {@code @SaveField} 别名。
 *
 * @see ColumnDef
 * @see RelationalStore#ensureTable(TableDef)
 */
public final class TableDef {

    /** 表名 */
    private final String name;

    /** 主键列名 */
    private final String keyColumn;

    /** 全部列（含主键列，按实体字段声明顺序） */
    private final List<ColumnDef> columns;

    /** 列名 → 定义 的只读索引视图 */
    private final Map<String, ColumnDef> columnMap;

    /**
     * 构造表定义。
     *
     * @param name      表名
     * @param keyColumn 主键列名（必须存在于 columns）
     * @param columns   全部列（含主键列）
     */
    public TableDef(String name, String keyColumn, List<ColumnDef> columns) {
        this.name = Objects.requireNonNull(name, "name");
        this.keyColumn = Objects.requireNonNull(keyColumn, "keyColumn");
        this.columns = List.copyOf(columns);
        Map<String, ColumnDef> map = new LinkedHashMap<>();
        for (ColumnDef c : this.columns) {
            map.put(c.name(), c);
        }
        if (!map.containsKey(keyColumn)) {
            throw new IllegalArgumentException("表定义 " + name + " 的列集合缺少主键列 " + keyColumn);
        }
        this.columnMap = Collections.unmodifiableMap(map);
    }

    /** @return 表名 */
    public String name() {
        return name;
    }

    /** @return 主键列名 */
    public String keyColumn() {
        return keyColumn;
    }

    /** @return 全部列（不可变，含主键列） */
    public List<ColumnDef> columns() {
        return columns;
    }

    /**
     * 按列名查找定义。
     *
     * @param columnName 列名
     * @return 列定义 Optional
     */
    public Optional<ColumnDef> column(String columnName) {
        return Optional.ofNullable(columnMap.get(columnName));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableDef that)) return false;
        return name.equals(that.name) && keyColumn.equals(that.keyColumn) && columns.equals(that.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, keyColumn, columns);
    }

    @Override
    public String toString() {
        List<String> names = new ArrayList<>();
        for (ColumnDef c : columns) {
            names.add(c.toString());
        }
        return "TableDef[" + name + " key=" + keyColumn + " " + names + "]";
    }
}
