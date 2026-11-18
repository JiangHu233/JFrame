package io.github.JiangHu.jframe.serialize;

/**
 * 可保存对象标识接口（可选实现）——实体的<b>存储主键契约</b>。
 * <p>
 * 实现此接口后，存储器可用对象的返回值作为<b>存储主键</b>自动定位数据：
 * <ul>
 *   <li>文件持久化（jframe_file 模块 {@code FileAPI}）：{@code saveKey()} 即文件名
 *       （自动追加 {@code .json} / {@code .yml} 扩展名），可用 {@code save(obj)} 无参重载</li>
 *   <li>数据库持久化（jframe_db 模块 {@code DataManager}）：{@code saveKey()} 即表主键列的值
 *       （v1 统一映射 VARCHAR 列），可用 {@code save(entity)} 自动定位行</li>
 * </ul>
 * 未实现此接口的类，必须使用显式指定主键/文件名的重载方法。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class PlayerData implements SaveIdentifiable {
 *
 *     @SaveField("uuid")
 *     private String uuid;
 *
 *     @SaveField("name")
 *     private String name;
 *
 *     @Override
 *     public String saveKey() {
 *         return uuid;   // 文件: <uuid>.json；数据库: players 表主键列 = uuid
 *     }
 * }
 * }</pre>
 *
 * <h3>设计动机</h3>
 * <p>
 * 将"存储主键策略"从注解（静态）迁移到接口方法（动态），
 * 使得同一类型的多个实例可以各自生成不同的主键（如按 UUID 区分玩家数据），
 * 同时保持调用方代码简洁（无需每次手动拼接键名）。
 */
public interface SaveIdentifiable {

    /**
     * 返回该对象的<b>存储主键</b>（文件持久化场景 = 文件名，不含扩展名；
     * 数据库持久化场景 = 主键列的值）。
     * <p>
     * 文件场景可以包含子路径分隔符（如 {@code "players/steve"}）。
     * <p>
     * <b>不应</b>返回 {@code null} 或空字符串，否则存储时将抛出
     * {@link io.github.JiangHu.jframe.serialize.exception.DataException DataException}。
     *
     * @return 存储主键（文件场景不含扩展名）
     */
    String saveKey();
}
