package io.github.JiangHu.jframe.data;

/**
 * 可保存对象标识接口（可选实现）。
 * <p>
 * 实现此接口后，可使用 {@link DataSaver#save(Object)} 无参重载方法，
 * 保存器会自动调用 {@link #saveKey()} 的返回值作为文件名（自动追加 {@code .json}）。
 * <p>
 * 未实现此接口的类，必须使用 {@link DataSaver#save(Object, String)}
 * 或 {@link DataSaver#save(Object, java.io.File)} 显式指定文件名。
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
 *         return uuid;   // 文件名 = <uuid>.json
 *     }
 * }
 *
 * // 自动命名：saver.save(playerData) → rootDir/<uuid>.json
 * // 显式命名：saver.save(playerData, "players/steve") → rootDir/players/steve.json
 * }</pre>
 *
 * <h3>设计动机</h3>
 * <p>
 * 将"文件命名策略"从注解（静态）迁移到接口方法（动态），
 * 使得同一类型的多个实例可以各自生成不同的文件名（如按 UUID 区分玩家数据），
 * 同时保持调用方代码简洁（无需每次手动拼接文件名）。
 *
 * @see DataSaver
 */
public interface SaveIdentifiable {

    /**
     * 返回该对象的保存键 / 文件名（不含 {@code .json} 扩展名）。
     * <p>
     * 返回值将作为相对于保存器根路径的文件名。可以包含子路径分隔符（如 {@code "players/steve"}）。
     * <p>
     * <b>不应</b>返回 {@code null} 或空字符串，否则保存时将抛出
     * {@link io.github.JiangHu.jframe.data.exception.DataException DataException}。
     *
     * @return 保存键 / 文件名（不含扩展名）
     */
    String saveKey();
}
