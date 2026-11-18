package io.github.JiangHu.jframe.db.spi;

/**
 * SQL 型存储 SPI（适配器实现）— 一条 MySQL 型存储通道。
 * <p>
 * 一个实现 = 一个库（连接通道）；jframe_db 不持有任何数据库连接，
 * 由适配器插件（如 fapeft 桥接 NsGameBase/WcpeLib）提供真实实现。
 * 注册后可服务任意多张表（{@code DataManagerBuilder.relational(store, 表名)}）。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li><b>幂等建表</b>：{@link #ensureTable} 表不存在则建；实体新增字段导致的缺列
 *       以 ALTER 补列（沿用幂等 DDL、异常忽略的线上实践）</li>
 *   <li><b>参数绑定</b>：所有带值方法必须以参数绑定执行
 *       （PreparedStatement / MyBatis {@code #{}}），禁止拼接值</li>
 *   <li><b>方言渲染</b>：{@link ColumnType} 为逻辑类型，按自家方言映射 DDL
 *       （参考该枚举的类型表）</li>
 *   <li><b>整行 upsert 原子</b>：单行 upsert 须为数据库单行原子操作
 *       （如 {@code INSERT ... ON DUPLICATE KEY UPDATE}）</li>
 * </ul>
 *
 * @see RelationalTable
 * @see TransactionalStore
 */
public interface RelationalStore {

    /**
     * 打开（或创建）一张表，返回该表的读写句柄。
     * <p>
     * 幂等：同一 TableDef 多次调用返回等价句柄；表不存在则按方言建表，
     * 已存在但缺列则补列（ALTER）。jframe_db 在 manager build 与关服路径调用。
     *
     * @param def 表定义
     * @return 表句柄
     * @throws io.github.JiangHu.jframe.serialize.exception.DataException 建表/补列失败
     */
    RelationalTable ensureTable(TableDef def);
}
