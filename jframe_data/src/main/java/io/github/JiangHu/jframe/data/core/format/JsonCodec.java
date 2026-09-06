package io.github.JiangHu.jframe.data.core.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.JiangHu.jframe.data.exception.DataException;

/**
 * JSON 格式存取器 — {@link JsonElement 树}与 JSON 文本之间的同级转换器。
 * <p>
 * 内部由 Gson 驱动,<b>直出/直析,零中转</b>:
 * <pre>
 *   write: JsonElement 树 → Gson pretty printing → JSON 文本
 *   read:  JSON 文本 → JsonParser → JsonElement 树
 * </pre>
 * Gson 仅是本存取器的<b>内部实现细节</b>,对用户完全不可见;用户适配器与
 * {@link io.github.JiangHu.jframe.data.DataSaver DataSaver} 只面对 JsonElement 树。
 *
 * <h3>输出风格</h3>
 * <ul>
 *   <li>pretty printing(2 空格缩进)</li>
 *   <li>禁用 HTML 转义(与 {@code DataSaver} 的 JSON 输出风格保持一致)</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>
 * 持有的 {@link Gson} 实例线程安全,本类无共享可变状态,线程安全。
 *
 * @see SaveFormatCodec
 * @see YamlCodec
 */
public final class JsonCodec implements SaveFormatCodec {

    /** Gson 实例(仅用于树 ↔ 文本,不涉及对象反射序列化,无需注册注解工厂) */
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            // 序列化 null 键("key": null),保证 read(write(tree)) 往返一致,
            // 且与 YAML 格式行为对齐(YAML dump 总是输出 null 值)
            .serializeNulls()
            .create();

    /** 创建 JSON 存取器。 */
    public JsonCodec() {
    }

    @Override
    public String write(JsonElement tree) {
        try {
            return gson.toJson(tree);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("JsonElement 转 JSON 文本失败", e);
        }
    }

    @Override
    public JsonElement read(String content) {
        try {
            return JsonParser.parseString(content);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("JSON 文本转 JsonElement 失败(内容可能不是合法 JSON)", e);
        }
    }
}
