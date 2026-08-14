package io.github.JiangHu.jframe.data.core.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.JiangHu.jframe.data.exception.DataException;
import io.github.JiangHu.jframe.data.value.SaveValue;

/**
 * SaveValue ↔ JsonElement 双向转换桥 — 连接「用户适配器字段货币」与「序列化管线内部树」的字段边界组件。
 * <p>
 * 用户适配器（{@link io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter SaveFieldAdapter}）
 * 面向框架自有的 {@link SaveValue} 树，而序列化管线（{@link SaveFieldTypeAdapter} 注解适配器链
 * 与格式存取器）运行在 Gson 的 {@code JsonElement} 树模型之上。本类在两者之间做无损双向转换：
 * <pre>
 *   用户适配器（SaveValue） ←→ SaveValueBridge ←→ JsonElement 树（管线内部货币）
 * </pre>
 * 仅绑定适配器的字段经过本类；普通字段由 Gson 直接处理，零额外中转。
 *
 * <h3>类型映射</h3>
 * <table border="1">
 *   <tr><th>SaveValue</th><th>JsonElement</th></tr>
 *   <tr><td>{@link SaveValue.Map}</td><td>{@link JsonObject}</td></tr>
 *   <tr><td>{@link SaveValue.List}</td><td>{@link JsonArray}</td></tr>
 *   <tr><td>{@link SaveValue.Str}</td><td>{@link JsonPrimitive}(string)</td></tr>
 *   <tr><td>{@link SaveValue.Num}</td><td>{@link JsonPrimitive}(number)</td></tr>
 *   <tr><td>{@link SaveValue.Bool}</td><td>{@link JsonPrimitive}(boolean)</td></tr>
 *   <tr><td>{@link SaveValue.Null}</td><td>{@link JsonNull}</td></tr>
 * </table>
 *
 * <h3>数值保真</h3>
 * <p>
 * Gson 的 {@code toJsonTree} 在某些路径下产生 {@code LazilyParsedNumber} 包装类，
 * {@link #fromGson} 会将其还原为标准 Java 数值类型（Integer → Long → Double），
 * 确保整数输出不带小数点、浮点数带小数点。
 *
 * <h3>线程安全</h3>
 * <p>
 * 所有方法均为静态方法且无共享状态，线程安全。
 * 注意：转换<b>产生新树</b>，不与原树共享可变节点。
 */
public final class SaveValueBridge {

    private SaveValueBridge() {
        // 工具类，禁止实例化
    }

    // ========== JsonElement → SaveValue ==========

    /**
     * 将 Gson 的 JsonElement 树递归转换为 SaveValue 树。
     *
     * @param element JsonElement 树（可为 null，视作 JsonNull）
     * @return SaveValue 树（null 输入返回 {@link SaveValue.Null}）
     * @throws DataException 若遇到无法识别的节点类型
     */
    public static SaveValue fromGson(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return SaveValue.ofNull();
            }
            if (element.isJsonObject()) {
                SaveValue.Map map = SaveValue.map();
                for (var entry : element.getAsJsonObject().entrySet()) {
                    map.put(entry.getKey(), fromGson(entry.getValue()));
                }
                return map;
            }
            if (element.isJsonArray()) {
                SaveValue.List list = SaveValue.list();
                for (JsonElement item : element.getAsJsonArray()) {
                    list.add(fromGson(item));
                }
                return list;
            }
            if (element.isJsonPrimitive()) {
                return primitiveFromGson(element.getAsJsonPrimitive());
            }
            throw new DataException("无法识别的 JsonElement 节点: " + element.getClass().getName());
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("JsonElement 转 SaveValue 失败", e);
        }
    }

    /**
     * 将 JsonPrimitive 转换为标量 SaveValue。
     * <p>
     * 布尔优先判定（Gson 中布尔与字符串是不同 primitive 类别）；
     * 数字经 {@code toJavaNumber} 还原为标准类型；其余按字符串处理。
     */
    private static SaveValue primitiveFromGson(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return SaveValue.of(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return SaveValue.of(toJavaNumber(primitive));
        }
        return SaveValue.of(primitive.getAsString());
    }

    /**
     * 将 JsonPrimitive 中的数字还原为合适的标准 Java Number 类型。
     * <ul>
     *   <li>已是标准类型（Integer/Long/Double/...）→ 直接返回</li>
     *   <li>LazilyParsedNumber 等包装类 → 按字符串表示还原：
     *       含小数点或科学计数法 → Double；纯整数 → Integer（范围内）或 Long</li>
     * </ul>
     */
    private static Number toJavaNumber(JsonPrimitive primitive) {
        Number num = primitive.getAsNumber();
        if (num instanceof Integer || num instanceof Long || num instanceof Double
                || num instanceof Float || num instanceof Short || num instanceof Byte
                || num instanceof java.math.BigInteger || num instanceof java.math.BigDecimal) {
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

    // ========== SaveValue → JsonElement ==========

    /**
     * 将 SaveValue 树递归转换为 Gson 的 JsonElement 树。
     *
     * @param value SaveValue 树（可为 null，视作 Null 节点）
     * @return JsonElement 树（null 输入返回 {@link JsonNull#INSTANCE}）
     * @throws DataException 若遇到无法识别的节点类型
     */
    public static JsonElement toGson(SaveValue value) {
        try {
            if (value == null || value.isNull()) {
                return JsonNull.INSTANCE;
            }
            if (value.isMap()) {
                JsonObject object = new JsonObject();
                for (var entry : value.asMap().entries()) {
                    object.add(entry.getKey(), toGson(entry.getValue()));
                }
                return object;
            }
            if (value.isList()) {
                JsonArray array = new JsonArray();
                for (SaveValue item : value.asList().values()) {
                    array.add(toGson(item));
                }
                return array;
            }
            if (value.isStr()) {
                return new JsonPrimitive(value.asString());
            }
            if (value.isNum()) {
                return new JsonPrimitive(value.asNumber());
            }
            if (value.isBool()) {
                return new JsonPrimitive(value.asBool());
            }
            throw new DataException("无法识别的 SaveValue 节点: " + value.getClass().getName());
        } catch (DataException e) {
            throw e;
        } catch (Exception e) {
            throw new DataException("SaveValue 转 JsonElement 失败", e);
        }
    }
}
