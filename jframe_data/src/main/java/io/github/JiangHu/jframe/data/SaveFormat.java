package io.github.JiangHu.jframe.data;

/**
 * 存储格式枚举 — 指定 {@link DataSaver} 将数据保存为哪种文本格式。
 * <p>
 * {@link DataSaver} 内部始终使用 Gson 将对象序列化为 {@code JsonElement} 树模型，
 * 再由格式层将树模型转换为对应格式的文本。两种格式共用同一套
 * {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 注解驱动机制，对用户完全透明。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 设置实例默认格式
 * saver.setFormat(SaveFormat.YAML);
 * saver.save(data, "config");        // → rootDir/config.yml
 *
 * // 按调用覆盖（不改变实例状态）
 * saver.withFormat(SaveFormat.YAML).save(data, "config");  // → config.yml
 * }</pre>
 *
 * <h3>格式对比</h3>
 * <ul>
 *   <li><b>JSON</b>：机器友好，解析快，适合大量数据存储</li>
 *   <li><b>YAML</b>：人类友好，缩进结构可读性高，适合配置文件</li>
 * </ul>
 *
 * @see DataSaver#setFormat(SaveFormat)
 * @see DataSaver#withFormat(SaveFormat)
 */
public enum SaveFormat {

    /**
     * JSON 格式（默认）。
     * <p>
     * 文件扩展名 {@code .json}，使用 Gson 的 pretty printing 输出。
     */
    JSON(".json"),

    /**
     * YAML 格式。
     * <p>
     * 文件扩展名 {@code .yml}，使用 SnakeYAML 的块样式（BLOCK）输出，缩进可读。
     * 遵循 YAML 1.2 规范（不会将 {@code yes}/{@code no}/{@code on}/{@code off}
     * 解释为布尔值）。
     */
    YAML(".yml");

    /** 文件扩展名（含前导点，如 {@code ".json"}） */
    private final String extension;

    SaveFormat(String extension) {
        this.extension = extension;
    }

    /**
     * 获取该格式对应的文件扩展名（含前导点）。
     *
     * @return 文件扩展名，如 {@code ".json"} 或 {@code ".yml"}
     */
    public String getExtension() {
        return extension;
    }
}
