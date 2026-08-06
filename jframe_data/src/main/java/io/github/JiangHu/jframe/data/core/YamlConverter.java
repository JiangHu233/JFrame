package io.github.JiangHu.jframe.data.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.JiangHu.jframe.data.exception.DataException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonElement 树 ↔ YAML 字符串转换器 — 格式层的核心组件。
 * <p>
 * {@link io.github.JiangHu.jframe.data.DataSaver DataSaver} 内部始终使用 Gson 将对象序列化为
 * {@link JsonElement} 树模型（经过 {@link SaveFieldTypeAdapter}，仅含
 * {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField} 字段）。
 * 本类负责将这棵树转换为 YAML 文本，或将 YAML 文本还原为树，从而在不改动 Gson 序列化核心的前提下
 * 增加 YAML 格式支持。
 *
 * <h3>转换原理</h3>
 * <p>
 * JSON 和 YAML 本质上是相同数据结构（映射 / 序列 / 标量）的两种文本表示。本类通过
 * <b>Java 原生对象</b>作为中间桥梁完成转换：
 * <pre>
 *   JsonElement 树 ←→ Java Map/List/标量 ←→ YAML 文本（SnakeYAML）
 * </pre>
 *
 * <h3>类型映射</h3>
 * <table border="1">
 *   <tr><th>JsonElement</th><th>Java 中间对象</th><th>YAML 表示</th></tr>
 *   <tr><td>JsonObject</td><td>LinkedHashMap</td><td>块映射（缩进键值对）</td></tr>
 *   <tr><td>JsonArray</td><td>ArrayList</td><td>块序列（{@code -} 列表）</td></tr>
 *   <tr><td>JsonPrimitive(bool)</td><td>Boolean</td><td>{@code true} / {@code false}</td></tr>
 *   <tr><td>JsonPrimitive(number)</td><td>Integer / Long / Double</td><td>数值字面量</td></tr>
 *   <tr><td>JsonPrimitive(string)</td><td>String</td><td>字符串（自动加引号）</td></tr>
 *   <tr><td>JsonNull</td><td>null</td><td>{@code null}</td></tr>
 * </table>
 *
 * <h3>线程安全</h3>
 * <p>
 * 本类所有方法均为静态方法，每次调用创建独立的 {@link Yaml} 实例（SnakeYAML 的 Yaml 对象
 * 非线程安全），因此本类本身线程安全。
 *
 * <h3>YAML 规范</h3>
 * <p>
 * 使用 SnakeYAML 2.x，遵循 YAML 1.2 规范 — 不会将 {@code yes}/{@code no}/{@code on}/{@code off}
 * 解释为布尔值（这是 YAML 1.1 的行为），避免了常见的字符串误判问题。
 *
 * @see io.github.JiangHu.jframe.data.SaveFormat#YAML
 * @see io.github.JiangHu.jframe.data.DataSaver
 */
public final class YamlConverter {

    private YamlConverter() {
        // 工具类，禁止实例化
    }

    // ========== JsonElement → YAML ==========

    /**
     * 将 {@link JsonElement} 树转换为 YAML 字符串。
     * <p>
     * 使用块样式（BLOCK）输出，缩进 2 空格，可读性好。
     *
     * @param element JsonElement 树（通常为 Gson {@code toJsonTree} 的产物）
     * @return YAML 字符串
     * @throws DataException 若转换过程中发生异常
     */
    public static String toYaml(JsonElement element) {
        try {
            Object javaObj = elementToObject(element);
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setIndent(2);
            options.setPrettyFlow(false);
            // 不在行内折行，保持长字符串完整
            options.setWidth(Integer.MAX_VALUE);
            Yaml yaml = new Yaml(options);
            return yaml.dump(javaObj);
        } catch (Exception e) {
            if (e instanceof DataException) {
                throw e;
            }
            throw new DataException("JsonElement 转 YAML 失败", e);
        }
    }

    // ========== YAML → JsonElement ==========

    /**
     * 将 YAML 字符串转换为 {@link JsonElement} 树。
     * <p>
     * 内部由 SnakeYAML 将 YAML 加载为 Java 原生对象（Map/List/标量），
     * 再递归转换为 JsonElement 树。
     *
     * @param yaml YAML 字符串
     * @return JsonElement 树（空内容或 null 值返回 {@link JsonNull}）
     * @throws DataException 若 YAML 格式错误或转换失败
     */
    public static JsonElement fromYaml(String yaml) {
        try {
            Yaml loader = new Yaml();
            Object javaObj = loader.load(yaml);
            return objectToElement(javaObj);
        } catch (Exception e) {
            if (e instanceof DataException) {
                throw e;
            }
            throw new DataException("YAML 转 JsonElement 失败", e);
        }
    }

    // ========== JsonElement → Java 原生对象（递归） ==========

    /**
     * 递归将 JsonElement 转换为 Java 原生对象，供 SnakeYAML dump。
     */
    private static Object elementToObject(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), elementToObject(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(elementToObject(item));
            }
            return list;
        }
        if (element.isJsonPrimitive()) {
            return primitiveToObject(element.getAsJsonPrimitive());
        }
        // 理论上不会到达
        return null;
    }

    /**
     * 将 JsonPrimitive 转换为对应的 Java 标量（Boolean / Number / String）。
     * <p>
     * 数字类型会尝试还原为最精确的 Java 类型（Integer → Long → Double），
     * 以确保 YAML 输出时类型正确（整数不带小数点，浮点数带小数点）。
     */
    private static Object primitiveToObject(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return toJavaNumber(primitive);
        }
        // 字符串或字符
        return primitive.getAsString();
    }

    /**
     * 将 JsonPrimitive 中的数字还原为合适的 Java Number 类型。
     * <p>
     * Gson 的 {@code toJsonTree} 通常直接存入 Java 原生类型（Integer/Double/Long），
     * 但在某些路径下可能产生 LazilyParsedNumber 包装类。本方法统一处理：
     * <ul>
     *   <li>已是标准 Java 类型 → 直接返回</li>
     *   <li>含小数点或科学计数法 → Double</li>
     *   <li>纯整数 → Integer（范围内）或 Long</li>
     * </ul>
     */
    private static Number toJavaNumber(JsonPrimitive primitive) {
        Number num = primitive.getAsNumber();
        // 标准 Java 类型直接返回（Gson toJsonTree 的常见情况）
        if (num instanceof Integer || num instanceof Long || num instanceof Double
                || num instanceof Float || num instanceof Short || num instanceof Byte
                || num instanceof BigInteger || num instanceof BigDecimal) {
            return num;
        }
        // LazilyParsedNumber 等包装类 → 通过字符串表示还原
        String s = num.toString();
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Long.parseLong(s);
        }
    }

    // ========== Java 原生对象 → JsonElement（递归） ==========

    /**
     * 递归将 SnakeYAML 加载的 Java 原生对象转换为 JsonElement 树。
     */
    private static JsonElement objectToElement(Object obj) {
        if (obj == null) {
            return JsonNull.INSTANCE;
        }
        if (obj instanceof Map) {
            JsonObject jsonObject = new JsonObject();
            for (var entry : ((Map<?, ?>) obj).entrySet()) {
                jsonObject.add(String.valueOf(entry.getKey()), objectToElement(entry.getValue()));
            }
            return jsonObject;
        }
        if (obj instanceof Iterable) {
            JsonArray jsonArray = new JsonArray();
            for (Object item : (Iterable<?>) obj) {
                jsonArray.add(objectToElement(item));
            }
            return jsonArray;
        }
        if (obj instanceof Boolean) {
            return new JsonPrimitive((Boolean) obj);
        }
        if (obj instanceof Number) {
            return new JsonPrimitive((Number) obj);
        }
        if (obj instanceof Character) {
            return new JsonPrimitive((Character) obj);
        }
        if (obj instanceof String) {
            return new JsonPrimitive((String) obj);
        }
        // 其他类型（枚举、日期等）转为字符串
        return new JsonPrimitive(String.valueOf(obj));
    }
}
