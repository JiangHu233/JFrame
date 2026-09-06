package io.github.JiangHu.jframe.data;

import io.github.JiangHu.jframe.data.core.format.JsonCodec;
import io.github.JiangHu.jframe.data.core.format.SaveFormatCodec;
import io.github.JiangHu.jframe.data.core.format.YamlCodec;

/**
 * 存储格式枚举 — 指定 {@link DataSaver} 将数据保存为哪种文本格式。
 * <p>
 * 每种格式关联一个<b>同级</b>的 {@link SaveFormatCodec 格式存取器}，负责
 * {@link com.google.gson.JsonElement 树}（管线内部货币）与文本之间的互转：
 * <pre>
 *   内存对象 ←→ [SaveFieldAdapter ←→ SaveValue，仅绑定适配器的字段] ←→ 格式存取器 ←→ 文本（json / yml）
 * </pre>
 * 两种格式共用同一套 {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 注解驱动机制与用户适配器，对用户完全透明。
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
 *   <li><b>JSON</b>：机器友好，解析快，适合大量数据存储（{@link JsonCodec} 驱动）</li>
 *   <li><b>YAML</b>：人类友好，缩进结构可读性高，适合配置文件（{@link YamlCodec} 驱动）</li>
 * </ul>
 *
 * @see DataSaver#setFormat(SaveFormat)
 * @see DataSaver#withFormat(SaveFormat)
 * @see SaveFormatCodec
 */
public enum SaveFormat {

    /**
     * JSON 格式（默认）。
     * <p>
     * 文件扩展名 {@code .json}，由 {@link JsonCodec} 驱动
     * （Gson pretty printing，2 空格缩进）。
     */
    JSON(".json", new JsonCodec()),

    /**
     * YAML 格式。
     * <p>
     * 文件扩展名 {@code .yml}，由 {@link YamlCodec} 驱动
     * （SnakeYAML 块样式输出，缩进可读）。
     * SnakeYAML 默认 resolver 沿用 YAML 1.1 隐式类型规则
     * （裸 {@code yes}/{@code no} 解析为布尔）；框架写出的字符串值自动加引号，
     * 保证往返类型一致。
     */
    YAML(".yml", new YamlCodec());

    /** 文件扩展名（含前导点，如 {@code ".json"}） */
    private final String extension;

    /** 该格式对应的存取器（JsonElement 树 ↔ 文本） */
    private final SaveFormatCodec codec;

    SaveFormat(String extension, SaveFormatCodec codec) {
        this.extension = extension;
        this.codec = codec;
    }

    /**
     * 获取该格式对应的文件扩展名（含前导点）。
     *
     * @return 文件扩展名，如 {@code ".json"} 或 {@code ".yml"}
     */
    public String getExtension() {
        return extension;
    }

    /**
     * 获取该格式对应的存取器 — {@link com.google.gson.JsonElement 树}
     * 与文本之间的转换器。
     *
     * @return 格式存取器（如 {@link JsonCodec} / {@link YamlCodec}）
     * @see SaveFormatCodec
     */
    public SaveFormatCodec getCodec() {
        return codec;
    }
}
