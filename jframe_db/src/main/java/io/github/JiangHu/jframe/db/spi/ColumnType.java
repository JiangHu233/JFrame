package io.github.JiangHu.jframe.db.spi;

/**
 * 列逻辑类型 — jframe_db 与适配器之间的<b>方言中立</b>类型契约。
 * <p>
 * jframe 不出现任何 JDBC 类型；实体字段经映射层（{@code mapping} 包）归一为下列逻辑类型，
 * 适配器按自家数据库方言渲染真实 DDL：
 * <table border="1">
 *   <tr><th>逻辑类型</th><th>Java 值类型</th><th>MySQL 方言参考</th></tr>
 *   <tr><td>{@link #STRING}</td><td>String</td><td>带 length → VARCHAR(n)；无 length → TEXT；
 *       索引列无 length → VARCHAR(191)</td></tr>
 *   <tr><td>{@link #TEXT}</td><td>String</td><td>TEXT / mediumtext</td></tr>
 *   <tr><td>{@link #INT}</td><td>Integer</td><td>INT</td></tr>
 *   <tr><td>{@link #LONG}</td><td>Long</td><td>BIGINT</td></tr>
 *   <tr><td>{@link #DOUBLE}</td><td>Double</td><td>DOUBLE</td></tr>
 *   <tr><td>{@link #BOOLEAN}</td><td>Boolean</td><td>TINYINT(1)</td></tr>
 *   <tr><td>{@link #DECIMAL}</td><td>BigDecimal</td><td>DECIMAL(precision, scale)</td></tr>
 *   <tr><td>{@link #JSON}</td><td>String（JSON 文本）</td><td>JSON / mediumtext</td></tr>
 * </table>
 * <p>
 * {@link #AUTO} 不是真实列类型，仅作为 {@code @Column#type()} 的默认值，
 * 表示「按字段类型自动推断」。
 */
public enum ColumnType {

    /** 定长字符串（可用 {@code @Column(length=n)} 指定长度；可索引）。 */
    STRING,

    /** 长文本（不可索引，适合简介/日志类大字段）。 */
    TEXT,

    /** 32 位整数。 */
    INT,

    /** 64 位整数（含 Instant 的 epochMilli 表示）。 */
    LONG,

    /** 双精度浮点。 */
    DOUBLE,

    /** 布尔。 */
    BOOLEAN,

    /** 定点小数（{@code @Column(precision, scale)}，缺省 10/2）。 */
    DECIMAL,

    /** JSON 文档列（值为 JSON 文本，由 serialize 内核管线编解码）。 */
    JSON,

    /** 仅作 {@code @Column#type()} 默认值：按字段 Java 类型自动推断。 */
    AUTO
}
