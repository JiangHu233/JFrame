package io.github.JiangHu.jframe.data.core.format;

import com.google.gson.JsonElement;

/**
 * 格式存取器接口 — {@link JsonElement 树}与<b>文本</b>之间的统一转换契约。
 * <p>
 * 每种存储格式(JSON / YAML)提供一个<b>同级</b>实现,地位完全对称:
 * <ul>
 *   <li>{@link JsonCodec}:JsonElement ↔ JSON 文本(Gson 直出/直析,零中转)</li>
 *   <li>{@link YamlCodec}:JsonElement ↔ YAML 文本(经 Java 原生对象桥接 SnakeYAML)</li>
 * </ul>
 * {@link io.github.JiangHu.jframe.data.DataSaver DataSaver} 通过
 * {@link io.github.JiangHu.jframe.data.SaveFormat#getCodec() SaveFormat.getCodec()}
 * 获取当前格式对应的存取器,统一调用 {@link #write} / {@link #read},不感知具体格式差异。
 * 未来新增格式(如 TOML)只需新增本接口的实现并挂到 {@link io.github.JiangHu.jframe.data.SaveFormat}
 * 枚举上,{@code DataSaver} 与用户适配器代码<b>零改动</b>。
 *
 * <h3>设计说明:主干货币为 JsonElement</h3>
 * <p>
 * 序列化主干(未绑定 {@link io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter SaveFieldAdapter}
 * 的字段、嵌套对象、容器)由 Gson 注解管线直接产出 {@code JsonElement} 树,
 * <b>直接往返于格式文本,不经过任何中间数据</b>:
 * <pre>
 *   无 adapter 数据: 内存对象 ←→ Gson ←→ JsonElement ←→ SaveFormatCodec ←→ 文本(json / yml)
 *   有 adapter 字段: 内存值 ←→ SaveFieldAdapter ←→ SaveValue(字段边界内) ←→ JsonElement ←→ 同上
 * </pre>
 * {@link io.github.JiangHu.jframe.data.value.SaveValue SaveValue} 仅作为字段适配器的
 * 契约货币,在 {@link io.github.JiangHu.jframe.data.core.engine.SaveFieldTypeAdapter} 字段边界处
 * 与 JsonElement 互转(经 {@link io.github.JiangHu.jframe.data.core.engine.SaveValueBridge}),
 * 不进入格式层。
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li><b>往返一致性</b>:{@code read(write(tree))} 与 {@code tree} 语义等价(数值精度按格式规范允许差异)</li>
 *   <li><b>线程安全</b>:实现应为无状态或每次调用创建独立内部状态(如 SnakeYAML 的 Yaml 实例)</li>
 *   <li><b>异常包装</b>:格式错误统一抛 {@link io.github.JiangHu.jframe.data.exception.DataException}</li>
 * </ul>
 *
 * @see JsonCodec
 * @see YamlCodec
 * @see io.github.JiangHu.jframe.data.SaveFormat
 * @see io.github.JiangHu.jframe.data.value.SaveValue
 */
public interface SaveFormatCodec {

    /**
     * 将 JsonElement 树序列化为格式文本。
     *
     * @param tree JsonElement 树(通常为对象根节点)
     * @return 格式化文本(JSON pretty printing / YAML 块样式)
     * @throws io.github.JiangHu.jframe.data.exception.DataException 若转换失败
     */
    String write(JsonElement tree);

    /**
     * 将格式文本反序列化为 JsonElement 树。
     *
     * @param content 格式文本
     * @return JsonElement 树(空内容或 null 值返回 {@link com.google.gson.JsonNull})
     * @throws io.github.JiangHu.jframe.data.exception.DataException 若文本格式错误或转换失败
     */
    JsonElement read(String content);
}
