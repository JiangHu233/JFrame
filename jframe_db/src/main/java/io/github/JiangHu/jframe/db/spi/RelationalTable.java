package io.github.JiangHu.jframe.db.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单张表的读写句柄（适配器实现）— 主键 CRUD + 索引查询 + 流式遍历。
 * <p>
 * 由 {@link RelationalStore#ensureTable(TableDef)} 产出，绑定一张表。
 * 所有操作以参数绑定执行。
 *
 * @see RelationalStore
 */
public interface RelationalTable {

    /**
     * 按主键读取一行。
     *
     * @param key 主键值
     * @return 行数据；不存在返回 null
     */
    Row get(String key);

    /**
     * 整行 upsert（主键冲突覆盖）——<b>数据库单行原子操作</b>。
     *
     * @param row 行数据（必须含主键列）
     */
    void upsert(Row row);

    /**
     * 按主键删除。
     *
     * @param key 主键值
     * @return 行存在并删除返回 true；不存在返回 false
     */
    boolean delete(String key);

    /**
     * 按主键判断存在。
     *
     * @param key 主键值
     * @return 存在返回 true
     */
    boolean exists(String key);

    /**
     * 行数统计。
     *
     * @return 表总行数
     */
    long count();

    /**
     * 流式遍历全表（防 OOM）——按批次回调，不把全表载入内存。
     * <p>
     * 大表（数万行以上）必须以游标/分页方式实现；回调顺序不作保证。
     *
     * @param batchSize 每批行数（正数）
     * @param batch     批回调（收到一批完整行）
     */
    void forEach(int batchSize, Consumer<List<Row>> batch);

    /**
     * 按主键批量读取（SQL IN 语义）。
     * <p>
     * 默认实现为循环 {@link #get}，适配器应覆写为单次 IN 查询。
     *
     * @param keys 主键集合
     * @return 存在的行（顺序不作保证）
     */
    default List<Row> getBatch(Collection<String> keys) {
        List<Row> rows = new ArrayList<>();
        for (String key : keys) {
            Row row = get(key);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 按索引列等值查询。
     *
     * @param column 列名（应为 TableDef 中标记 indexed 的列）
     * @param value  列值
     * @return 命中的行（顺序不作保证）
     */
    List<Row> findBy(String column, Object value);

    /**
     * 按索引列 IN 查询。
     *
     * @param column 列名（应为 indexed 列）
     * @param values 列值集合
     * @return 命中的行（顺序不作保证）
     */
    List<Row> findByIn(String column, Collection<?> values);

    /**
     * 校验列属于该表（供默认实现/适配器复用的便捷静态工具）。
     *
     * @param column 列名
     * @return 非空列名
     */
    static String requireColumn(String column) {
        return Objects.requireNonNull(column, "column");
    }
}
