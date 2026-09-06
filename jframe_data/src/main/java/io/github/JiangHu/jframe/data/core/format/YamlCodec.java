package io.github.JiangHu.jframe.data.core.format;

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
 * YAML 格式存取器 — {@link JsonElement 树}与 YAML 文本之间的同级转换器。
 * <p>
 * 内部由 SnakeYAML 驱动,通过 <b>Java 原生对象</b>作为必要桥梁
 * (SnakeYAML 的 dump/load 只接受/产出 Java 对象,这是 YAML 路径的理论最短中转):
 * <pre>
 *   write: JsonElement 树 → Java Map/List/标量 → SnakeYAML dump(块样式)→ YAML 文本
 *   read:  YAML 文本 → SnakeYAML load → Java Map/List/标量 → JsonElement 树
 * </pre>
 * SnakeYAML 仅是本存取器的<b>内部实现细节</b>,对用户完全不可见;用户适配器与
 * {@link io.github.JiangHu.jframe.data.DataSaver DataSaver} 只面对 JsonElement 树。
 *
 * <h3>类型映射</h3>
 * <table border="1">
 *   <tr><th>JsonElement</th><th>Java 中间对象</th><th>YAML 表示</th></tr>
 *   <tr><td>JsonObject</td><td>LinkedHashMap</td><td>块映射(缩进键值对)</td></tr>
 *   <tr><td>JsonArray</td><td>ArrayList</td><td>块序列({@code -} 列表)</td></tr>
 *   <tr><td>JsonPrimitive(bool)</td><td>Boolean</td><td>{@code true} / {@code false}</td></tr>
 *   <tr><td>JsonPrimitive(num)</td><td>Integer / Long / Double</td><td>数值字面量</td></tr>
 *   <tr><td>JsonPrimitive(str)</td><td>String</td><td>字符串(自动加引号)</td></tr>
 *   <tr><td>JsonNull</td><td>null</td><td>{@code null}</td></tr>
 * </table>
 *
 * <h3>输出风格</h3>
 * <ul>
 *   <li>块样式(BLOCK),缩进 2 空格,可读性好</li>
 *   <li>不在行内折行(width 设为最大值),保持长字符串完整</li>
 * </ul>
 *
 * <h3>YAML 规范</h3>
 * <p>
 * 使用 SnakeYAML 2.x,其默认 resolver 沿用 YAML 1.1 隐式类型规则:
 * 手写配置中的裸 {@code yes}/{@code no}/{@code on}/{@code off} 会被解析为布尔值
 * (这是配置场景的常见约定)。框架写出的字符串值会<b>自动加引号</b>
 * (如 {@code flag: 'yes'}),因此 {@code write → read} 往返始终类型一致。
 *
 * <h3>线程安全</h3>
 * <p>
 * 本类无共享可变状态;每次调用创建独立的 {@link Yaml} 实例
 * (SnakeYAML 的 Yaml 对象非线程安全),因此本类线程安全。
 *
 * @see SaveFormatCodec
 * @see JsonCodec
 */
public final class YamlCodec implements SaveFormatCodec {

    /** 创建 YAML 存取器。 */
    public YamlCodec() {
    }

    // ========== JsonElement → YAML ==========

    @Override
    public String write(JsonElement tree) {
        try {
            Object javaObj = elementToObject(tree);
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setIndent(2);
            options.setPrettyFlow(false);
            // 不在行内折行,保持长字符串完整
            options.setWidth(Integer.MAX_VALUE);
            Yaml yaml = new Yaml(options);
            return yaml.dump(javaObj);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("JsonElement 转 YAML 失败", e);
        }
    }

    // ========== YAML → JsonElement ==========

    @Override
    public JsonElement read(String content) {
        try {
            Yaml loader = new Yaml();
            Object javaObj = loader.load(content);
            return objectToElement(javaObj);
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("YAML 转 JsonElement 失败(内容可能不是合法 YAML)", e);
        }
    }

    // ========== JsonElement → Java 原生对象(递归) ==========

    /**
     * 递归将 JsonElement 转换为 Java 原生对象,供 SnakeYAML dump。
     */
    private Object elementToObject(JsonElement element) {
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
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return toJavaNumber(primitive.getAsNumber());
        }
        return primitive.getAsString();
    }

    /**
     * 将数值还原为合适的标准 Java Number 类型。
     * <ul>
     *   <li>已是标准类型(Integer/Long/Double/...)→ 直接返回</li>
     *   <li>其他包装类型(如 Gson 的 LazilyParsedNumber)→ 按字符串表示还原:
     *       含小数点或科学计数法 → Double;纯整数 → Integer(范围内)或 Long</li>
     * </ul>
     * <p>
     * 确保整数输出不带小数点、浮点数带小数点。
     */
    private Number toJavaNumber(Number num) {
        if (num instanceof Integer || num instanceof Long || num instanceof Double
                || num instanceof Float || num instanceof Short || num instanceof Byte
                || num instanceof BigInteger || num instanceof BigDecimal) {
            return num;
        }
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

    // ========== Java 原生对象 → JsonElement(递归) ==========

    /**
     * 递归将 SnakeYAML 加载的 Java 原生对象转换为 JsonElement 树。
     */
    private JsonElement objectToElement(Object obj) {
        if (obj == null) {
            return JsonNull.INSTANCE;
        }
        if (obj instanceof Map) {
            JsonObject object = new JsonObject();
            for (var entry : ((Map<?, ?>) obj).entrySet()) {
                object.add(String.valueOf(entry.getKey()), objectToElement(entry.getValue()));
            }
            return object;
        }
        if (obj instanceof Iterable) {
            JsonArray array = new JsonArray();
            for (Object item : (Iterable<?>) obj) {
                array.add(objectToElement(item));
            }
            return array;
        }
        if (obj instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (obj instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (obj instanceof Character c) {
            return new JsonPrimitive(String.valueOf(c));
        }
        if (obj instanceof String s) {
            return new JsonPrimitive(s);
        }
        // 其他类型(枚举、日期等)转为字符串
        return new JsonPrimitive(String.valueOf(obj));
    }
}
