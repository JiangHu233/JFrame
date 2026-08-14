package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.EndTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * NBT（{@link Tag}）与可读 JSON（{@link JsonElement}）互转工具。
 *
 * <p>供 {@link ItemCodec} 的 JSON 形式使用，把物品附加 NBT 展开为人类可读的 JSON 结构，
 * 而非 Base64 乱码。核心设计目标：<b>可读 + 往返保真</b>。
 *
 * <h3>映射规则</h3>
 * <p>常见类型直接映射为 JSON 原生类型，保证可读性；JSON 无法无歧义表达的类型
 * （byte/short/long/float、字节数组、整数数组）用 {@code {"__nbt":"<类型>","value":...}}
 * 包装，确保反向解析时精确还原。
 *
 * <table border="1">
 * <caption>NBT ↔ JSON 映射</caption>
 * <tr><th>NBT 类型</th><th>JSON 表示</th><th>说明</th></tr>
 * <tr><td>{@link CompoundTag}</td><td>JSON object</td><td>递归每个键值</td></tr>
 * <tr><td>{@link ListTag}</td><td>JSON array</td><td>递归每个元素</td></tr>
 * <tr><td>{@link StringTag}</td><td>JSON string</td><td>直接</td></tr>
 * <tr><td>{@link IntTag}</td><td>JSON number（整数）</td><td>最常见整型，直接映射</td></tr>
 * <tr><td>{@link DoubleTag}</td><td>JSON number（小数）</td><td>最常见浮点，直接映射</td></tr>
 * <tr><td>{@link ByteTag}</td><td>{@code {"__nbt":"byte","value":N}}</td><td>常作布尔标志，范围不同</td></tr>
 * <tr><td>{@link ShortTag}</td><td>{@code {"__nbt":"short","value":N}}</td><td>附魔 ID 等</td></tr>
 * <tr><td>{@link LongTag}</td><td>{@code {"__nbt":"long","value":N}}</td><td>可能超 int 范围</td></tr>
 * <tr><td>{@link FloatTag}</td><td>{@code {"__nbt":"float","value":N}}</td><td>精度不同于 double</td></tr>
 * <tr><td>{@link ByteArrayTag}</td><td>{@code {"__nbt":"byte[]","value":[...]}}</td><td>每个 byte 转 0~255</td></tr>
 * <tr><td>{@link IntArrayTag}</td><td>{@code {"__nbt":"int[]","value":[...]}}</td><td>直接</td></tr>
 * <tr><td>{@link EndTag}</td><td>JSON null</td><td>罕见，流终止符</td></tr>
 * </table>
 *
 * <h3>反向解析（JSON → NBT）</h3>
 * <ul>
 *   <li>JSON string → {@link StringTag}</li>
 *   <li>JSON number（字面量含 {@code .} 或 {@code e/E}）→ {@link DoubleTag}，否则 → {@link IntTag}</li>
 *   <li>JSON boolean → {@link ByteTag}（1/0）</li>
 *   <li>JSON array → {@link ListTag}（元素递归）</li>
 *   <li>JSON object 含 {@code __nbt} → 按 {@link #TYPE_KEY} 标记还原对应类型</li>
 *   <li>JSON object 不含 {@code __nbt} → {@link CompoundTag}（递归）</li>
 * </ul>
 *
 * <h3>保真性说明</h3>
 * <p>对于 {@link IntTag}/{@link DoubleTag}，JSON number 无法区分原始的精确类型，反向时
 * 依据字面量推断（整数 → IntTag，小数 → DoubleTag），与原 NBT 一致。
 * byte/short/long/float 因 NBT 二进制按类型写入不同字节宽度，必须保留类型信息，
 * 故用 {@code __nbt} 标记，确保 {@code NBT → JSON → NBT} 完全等价。
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 原始 NBT（钻石剑：自定义名称 + lore + 自定义数据）
 * CompoundTag nbt = new CompoundTag()
 *         .putCompound("display", new CompoundTag()
 *                 .putString("Name", "§b屠龙剑")
 *                 .putList("Lore", new ListTag<Tag>()
 *                         .add(new StringTag("", "§7传说武器"))))
 *         .putString("rarity", "legendary")
 *         .putInt("kill_count", 0);
 *
 * // 转成可读 JSON
 * JsonElement json = NbtJsonConverter.tagToJson(nbt);
 * // → {"display":{"Name":"§b屠龙剑","Lore":["§7传说武器"]},"rarity":"legendary","kill_count":0}
 *
 * // 反向还原，与原 NBT 等价
 * Tag restored = NbtJsonConverter.jsonToTag(json);
 * }</pre>
 *
 * @see ItemCodec#encodeJson(Item)
 * @see ItemCodec#decodeJson(String)
 */
public final class NbtJsonConverter {

    /**
     * 类型标记键名。当某个 NBT 值无法用 JSON 原生类型无歧义表达时，
     * 用此键标注其实际 NBT 类型，反向解析时据此精确还原。
     */
    static final String TYPE_KEY = "__nbt";

    /** 包装对象中承载实际值的键名 */
    private static final String VALUE_KEY = "value";

    // __nbt 标记的可能取值
    private static final String T_BYTE = "byte";
    private static final String T_SHORT = "short";
    private static final String T_LONG = "long";
    private static final String T_FLOAT = "float";
    private static final String T_BYTE_ARRAY = "byte[]";
    private static final String T_INT_ARRAY = "int[]";

    private NbtJsonConverter() {
    }

    /**
     * Tag → 可读 JsonElement（与 {@link #jsonToTag} 对称）。
     *
     * @param tag NBT 标签，{@code null} 返回 {@link JsonNull}
     * @return 可读 JSON 元素
     */
    public static JsonElement tagToJson(Tag tag) {
        if (tag == null) {
            return JsonNull.INSTANCE;
        }
        if (tag instanceof CompoundTag) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Tag> e : ((CompoundTag) tag).getTags().entrySet()) {
                obj.add(e.getKey(), tagToJson(e.getValue()));
            }
            return obj;
        }
        if (tag instanceof ListTag) {
            JsonArray arr = new JsonArray();
            for (Tag el : ((ListTag<?>) tag).getAll()) {
                arr.add(tagToJson(el));
            }
            return arr;
        }
        if (tag instanceof StringTag) {
            return new JsonPrimitive(((StringTag) tag).data);
        }
        // 最常见的整型/浮点型直接映射为 JSON number，保证可读性
        if (tag instanceof IntTag) {
            return new JsonPrimitive(((IntTag) tag).getData());
        }
        if (tag instanceof DoubleTag) {
            return new JsonPrimitive(((DoubleTag) tag).getData());
        }
        // 其余数值类型与数组类型用 __nbt 标记包装，确保往返保真
        if (tag instanceof ByteTag) {
            return wrap(T_BYTE, ((ByteTag) tag).getData());
        }
        if (tag instanceof ShortTag) {
            return wrap(T_SHORT, ((ShortTag) tag).getData());
        }
        if (tag instanceof LongTag) {
            return wrap(T_LONG, ((LongTag) tag).getData());
        }
        if (tag instanceof FloatTag) {
            return wrap(T_FLOAT, ((FloatTag) tag).getData());
        }
        if (tag instanceof ByteArrayTag) {
            return wrapByteArray(T_BYTE_ARRAY, ((ByteArrayTag) tag).data);
        }
        if (tag instanceof IntArrayTag) {
            return wrapIntArray(T_INT_ARRAY, ((IntArrayTag) tag).data);
        }
        if (tag instanceof EndTag) {
            return JsonNull.INSTANCE;
        }
        // 兜底：未知类型用 SNBT 字符串承载（反向将还原为 StringTag，属降级）
        return new JsonPrimitive(tag.toSNBT());
    }

    /**
     * JsonElement → Tag（与 {@link #tagToJson} 对称）。
     *
     * @param el JSON 元素，{@code null}/JSON null 返回 {@link EndTag}
     * @return NBT 标签
     * @throws InventoryCodecException 若 JSON 结构无法识别
     */
    public static Tag jsonToTag(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new EndTag();
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has(TYPE_KEY)) {
                return jsonToTyped(obj);
            }
            CompoundTag compound = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonNull()) {
                    continue; // 跳过 null，避免把 EndTag 塞进 CompoundTag
                }
                compound.put(e.getKey(), jsonToTag(v));
            }
            return compound;
        }
        if (el.isJsonArray()) {
            ListTag<Tag> list = new ListTag<>();
            for (JsonElement child : el.getAsJsonArray()) {
                list.add(jsonToTag(child));
            }
            return list;
        }
        if (el.isJsonPrimitive()) {
            return primitiveToTag(el.getAsJsonPrimitive());
        }
        throw new InventoryCodecException("无法识别的 JSON 元素类型: " + el);
    }

    /** JsonPrimitive → Tag */
    private static Tag primitiveToTag(JsonPrimitive p) {
        if (p.isString()) {
            return new StringTag("", p.getAsString());
        }
        if (p.isBoolean()) {
            return new ByteTag("", p.getAsBoolean() ? 1 : 0);
        }
        if (p.isNumber()) {
            // 依据原始字面量判断整型/浮点：含 . 或指数 → DoubleTag，否则 IntTag
            String raw = p.getAsString();
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                return new DoubleTag("", p.getAsDouble());
            }
            return new IntTag("", p.getAsInt());
        }
        throw new InventoryCodecException("无法识别的 JSON 基本量: " + p);
    }

    /** 带 {@link #TYPE_KEY} 标记的对象 → 对应类型 Tag */
    private static Tag jsonToTyped(JsonObject obj) {
        String type = obj.get(TYPE_KEY).getAsString();
        if (!obj.has(VALUE_KEY)) {
            throw new InventoryCodecException("NBT JSON 类型标记缺少 value 字段: " + type);
        }
        JsonElement valueEl = obj.get(VALUE_KEY);
        switch (type) {
            case T_BYTE:
                return new ByteTag("", valueEl.getAsInt());
            case T_SHORT:
                return new ShortTag("", valueEl.getAsInt());
            case T_LONG:
                return new LongTag("", valueEl.getAsLong());
            case T_FLOAT:
                return new FloatTag("", valueEl.getAsFloat());
            case T_BYTE_ARRAY: {
                JsonArray arr = valueEl.getAsJsonArray();
                byte[] bytes = new byte[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    bytes[i] = (byte) arr.get(i).getAsInt();
                }
                return new ByteArrayTag("", bytes);
            }
            case T_INT_ARRAY: {
                JsonArray arr = valueEl.getAsJsonArray();
                int[] ints = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    ints[i] = arr.get(i).getAsInt();
                }
                return new IntArrayTag("", ints);
            }
            default:
                throw new InventoryCodecException("未知的 NBT JSON 类型标记: " + type);
        }
    }

    /** 包装标量数值（byte/short/long/float）为 {@code {"__nbt":type,"value":N}} */
    private static JsonObject wrap(String type, Number value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(TYPE_KEY, type);
        obj.addProperty(VALUE_KEY, value);
        return obj;
    }

    /** 包装 byte[] 为 {@code {"__nbt":"byte[]","value":[...]}}（每个 byte 转 0~255） */
    private static JsonObject wrapByteArray(String type, byte[] data) {
        JsonObject obj = new JsonObject();
        obj.addProperty(TYPE_KEY, type);
        JsonArray arr = new JsonArray();
        for (byte b : data) {
            arr.add(b & 0xFF);
        }
        obj.add(VALUE_KEY, arr);
        return obj;
    }

    /** 包装 int[] 为 {@code {"__nbt":"int[]","value":[...]}} */
    private static JsonObject wrapIntArray(String type, int[] data) {
        JsonObject obj = new JsonObject();
        obj.addProperty(TYPE_KEY, type);
        JsonArray arr = new JsonArray();
        for (int i : data) {
            arr.add(i);
        }
        obj.add(VALUE_KEY, arr);
        return obj;
    }
}
