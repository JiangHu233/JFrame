package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.item.Item;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物品栏编解码器 —— {@link Inventory} 与文本字符串互转，完整保留槽位位置关系。
 * <p>
 * 适用于任意 Nukkit 原生物品栏（玩家背包、箱子、末影箱、熔炉、酿造台等），
 * 不同类型物品栏统一用同一格式编码，反序列化时按 {@code size} 校验目标容量。
 *
 * <h3>文本格式</h3>
 * <pre>
 * V1:<size>:<index>=<base64>,<index>=<base64>,...
 * </pre>
 * <ul>
 *   <li>{@code V1} —— 格式版本号，预留向后兼容</li>
 *   <li>{@code size} —— 物品栏容量（槽位数），反序列化时用于校验目标物品栏</li>
 *   <li>{@code index=base64} —— 槽位条目，{@code index} 为槽位下标（0-based），
 *       {@code base64} 为 {@link ItemCodec#encode(Item)} 输出</li>
 *   <li>空气槽位<strong>不</strong>写入，采用稀疏存储</li>
 * </ul>
 *
 * <h3>格式示例</h3>
 * <pre>
 * V1:27:0=CgE...,5=DAM...,26=EQE...        // 27 格箱子，0/5/26 槽有物品
 * V1:27:                                    // 27 格空物品栏
 * </pre>
 *
 * <h3>分隔符安全性</h3>
 * <p>
 * Base64 字符集为 {@code [A-Za-z0-9+/=]}，不含 {@code :}、{@code ,}；
 * 槽位索引为纯数字，不含 {@code =}。因此 {@code :}、{@code ,}、{@code =}
 * 作为分隔符不会与数据内容冲突。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 序列化玩家背包
 * String data = InventoryCodec.encode(player.getInventory());
 *
 * // 持久化到数据库 / 配置文件 ...
 *
 * // 反序列化并应用到目标物品栏（自动校验容量）
 * InventoryCodec.apply(player.getInventory(), data);
 *
 * // 或仅解码为映射，自行处理
 * Map<Integer, Item> slots = InventoryCodec.decode(data);
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>
 * 本类所有方法均为无状态静态方法，可安全并发调用。
 * 但传入的 {@link Inventory} 实例本身的线程安全性由调用方保证。
 *
 * @see ItemCodec
 */
public final class InventoryCodec {

    /** 当前格式版本号 */
    private static final String FORMAT_VERSION = "V1";
    /** 版本号 / size / 条目列表之间的分隔符 */
    private static final String SEP_VERSION = ":";
    /** 条目之间的分隔符 */
    private static final String SEP_ENTRY = ",";
    /** 槽位索引与 Base64 数据之间的分隔符 */
    private static final String SEP_SLOT = "=";

    /** Gson 实例（无状态，线程安全，复用） */
    private static final Gson GSON = new Gson();

    private InventoryCodec() {
    }

    // ==================== 编码 ====================

    /**
     * 将整个物品栏序列化为文本字符串，保留槽位位置。
     *
     * @param inventory 物品栏
     * @return 文本字符串，格式 {@code V1:<size>:<entries>}
     * @throws InventoryCodecException 若任意槽位物品编码失败
     */
    public static String encode(Inventory inventory) {
        int size = inventory.getSize();
        Item[] slots = new Item[size];
        for (int i = 0; i < size; i++) {
            slots[i] = inventory.getItem(i);
        }
        return encodeSlots(slots, size);
    }

    /**
     * 将物品数组序列化为文本字符串，保留槽位位置。
     * <p>
     * 适用于无 {@link Inventory} 实例、仅有物品数组的场景（如离线数据迁移）。
     *
     * @param slots 物品数组，{@code slots[i]} 对应槽位 {@code i}
     * @param size  物品栏容量（写入 header，用于反序列化校验）；
     *              若小于数组长度则只取前 {@code size} 个
     * @return 文本字符串
     * @throws InventoryCodecException 若任意物品编码失败
     */
    public static String encodeSlots(Item[] slots, int size) {
        if (slots == null) {
            throw new InventoryCodecException("物品数组不能为 null");
        }
        if (size < 0) {
            throw new InventoryCodecException("物品栏容量不能为负: " + size);
        }
        int limit = Math.min(slots.length, size);
        StringBuilder sb = new StringBuilder(64);
        sb.append(FORMAT_VERSION).append(SEP_VERSION).append(size).append(SEP_VERSION);
        boolean first = true;
        for (int i = 0; i < limit; i++) {
            Item item = slots[i];
            String encoded = ItemCodec.encode(item);
            if (encoded.isEmpty()) {
                continue; // 空气槽位稀疏跳过
            }
            if (!first) {
                sb.append(SEP_ENTRY);
            }
            sb.append(i).append(SEP_SLOT).append(encoded);
            first = false;
        }
        return sb.toString();
    }

    // ==================== 解码 ====================

    /**
     * 将文本字符串反序列化为「槽位索引 → 物品」映射。
     * <p>
     * 返回的映射只包含非空槽位，且保持槽位索引升序（{@link LinkedHashMap}）。
     *
     * @param encoded 文本字符串
     * @return 不可变映射（槽位索引 → 物品），空物品栏返回空映射
     * @throws InventoryCodecException 若格式非法、版本不支持或条目解析失败
     */
    public static Map<Integer, Item> decode(String encoded) {
        return decodeSlots(encoded);
    }

    /**
     * {@link #decode(String)} 的语义别名，强调「解码为槽位映射」。
     *
     * @param encoded 文本字符串
     * @return 不可变映射（槽位索引 → 物品）
     * @throws InventoryCodecException 若格式非法、版本不支持或条目解析失败
     */
    public static Map<Integer, Item> decodeSlots(String encoded) {
        ParsedHeader header = parseHeader(encoded);
        Map<Integer, Item> slots = decodeEntries(header.entries);
        return Collections.unmodifiableMap(slots);
    }

    /**
     * 读取编码字符串 header 中的物品栏容量。
     *
     * @param encoded 文本字符串
     * @return 物品栏容量
     * @throws InventoryCodecException 若格式非法
     */
    public static int decodeSize(String encoded) {
        return parseHeader(encoded).size;
    }

    /**
     * 将文本字符串反序列化并应用到目标物品栏。
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>解析 header，校验 {@code size} 与目标物品栏 {@link Inventory#getSize()} 一致</li>
     *   <li>调用 {@link Inventory#clearAll()} 清空目标物品栏</li>
     *   <li>按槽位索引逐一 {@link Inventory#setItem(int, Item)} 填充</li>
     * </ol>
     *
     * @param inventory 目标物品栏
     * @param encoded   文本字符串
     * @throws InventoryCodecException 若格式非法、容量不匹配或填充失败
     */
    public static void apply(Inventory inventory, String encoded) {
        if (inventory == null) {
            throw new InventoryCodecException("目标物品栏不能为 null");
        }
        ParsedHeader header = parseHeader(encoded);
        Map<Integer, Item> slots = decodeEntries(header.entries);
        int size = inventory.getSize();
        if (header.size != size) {
            throw new InventoryCodecException(
                    "物品栏容量不匹配：编码=" + header.size + "，目标=" + size);
        }
        inventory.clearAll();
        for (Map.Entry<Integer, Item> entry : slots.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= size) {
                throw new InventoryCodecException(
                        "槽位索引 " + index + " 超出物品栏容量 [0," + (size - 1) + "]");
            }
            inventory.setItem(index, entry.getValue());
        }
    }

    // ==================== JSON 形式（可读，调试友好） ====================

    /**
     * 将整个物品栏序列化为可读 JSON 字符串，保留槽位位置。
     * <p>
     * 与 {@link #encode(Inventory)} 的紧凑文本格式并列。JSON 形式结构清晰，
     * 便于调试查看、日志输出与跨系统交换。
     *
     * <h3>JSON 结构</h3>
     * <pre>{@code
     * {
     *   "version": "V1",
     *   "size": 27,
     *   "slots": [
     *     { "index": 0, "item": { "id": 1, "damage": 0, "count": 10 } },
     *     { "index": 5, "item": { "id": 4, "damage": 0, "count": 20, "nbt": "..." } }
     *   ]
     * }
     * }</pre>
     * <p>
     * 空气槽位稀疏省略，与紧凑格式一致。
     *
     * @param inventory 物品栏
     * @return JSON 字符串
     * @throws InventoryCodecException 若任意槽位物品编码失败
     */
    public static String encodeJson(Inventory inventory) {
        int size = inventory.getSize();
        Item[] slots = new Item[size];
        for (int i = 0; i < size; i++) {
            slots[i] = inventory.getItem(i);
        }
        return encodeSlotsJson(slots, size);
    }

    /**
     * 将物品数组序列化为可读 JSON 字符串，保留槽位位置。
     *
     * @param slots 物品数组，{@code slots[i]} 对应槽位 {@code i}
     * @param size  物品栏容量（写入 JSON，用于反序列化校验）
     * @return JSON 字符串
     * @throws InventoryCodecException 若参数非法或编码失败
     */
    public static String encodeSlotsJson(Item[] slots, int size) {
        if (slots == null) {
            throw new InventoryCodecException("物品数组不能为 null");
        }
        if (size < 0) {
            throw new InventoryCodecException("物品栏容量不能为负: " + size);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("size", size);
        JsonArray slotsArr = new JsonArray();
        int limit = Math.min(slots.length, size);
        for (int i = 0; i < limit; i++) {
            JsonObject itemJson = ItemCodec.itemToJson(slots[i]);
            if (itemJson == null) {
                continue; // 空气槽位稀疏跳过
            }
            JsonObject slotObj = new JsonObject();
            slotObj.addProperty("index", i);
            slotObj.add("item", itemJson);
            slotsArr.add(slotObj);
        }
        root.add("slots", slotsArr);
        return GSON.toJson(root);
    }

    /**
     * 将 JSON 字符串反序列化为「槽位索引 → 物品」映射（不可变）。
     *
     * @param json JSON 字符串
     * @return 不可变映射（槽位索引 → 物品），空物品栏返回空映射
     * @throws InventoryCodecException 若 JSON 格式非法或解析失败
     */
    public static Map<Integer, Item> decodeJson(String json) {
        JsonObject root = parseJsonRoot(json);
        JsonArray slotsArr = root.has("slots") ? root.getAsJsonArray("slots") : new JsonArray();
        Map<Integer, Item> slots = new LinkedHashMap<>();
        for (JsonElement element : slotsArr) {
            JsonObject slotObj = element.getAsJsonObject();
            int index = slotObj.get("index").getAsInt();
            if (index < 0) {
                throw new InventoryCodecException("槽位索引不能为负: " + index);
            }
            JsonObject itemObj = slotObj.getAsJsonObject("item");
            slots.put(index, ItemCodec.jsonToItem(itemObj));
        }
        return Collections.unmodifiableMap(slots);
    }

    /**
     * 将 JSON 字符串反序列化并应用到目标物品栏。
     * <p>
     * 执行步骤：解析 JSON → 校验 {@code size} 与目标容量一致 →
     * {@link Inventory#clearAll()} → 按槽位索引 {@link Inventory#setItem} 填充。
     *
     * @param inventory 目标物品栏
     * @param json      JSON 字符串
     * @throws InventoryCodecException 若格式非法、容量不匹配或填充失败
     */
    public static void applyJson(Inventory inventory, String json) {
        if (inventory == null) {
            throw new InventoryCodecException("目标物品栏不能为 null");
        }
        JsonObject root = parseJsonRoot(json);
        int encodedSize = root.get("size").getAsInt();
        int size = inventory.getSize();
        if (encodedSize != size) {
            throw new InventoryCodecException(
                    "物品栏容量不匹配：编码=" + encodedSize + "，目标=" + size);
        }
        JsonArray slotsArr = root.has("slots") ? root.getAsJsonArray("slots") : new JsonArray();
        inventory.clearAll();
        for (JsonElement element : slotsArr) {
            JsonObject slotObj = element.getAsJsonObject();
            int index = slotObj.get("index").getAsInt();
            if (index < 0 || index >= size) {
                throw new InventoryCodecException(
                        "槽位索引 " + index + " 超出物品栏容量 [0," + (size - 1) + "]");
            }
            JsonObject itemObj = slotObj.getAsJsonObject("item");
            inventory.setItem(index, ItemCodec.jsonToItem(itemObj));
        }
    }

    // ==================== 内部解析 ====================

    /**
     * 解析 JSON 根对象并校验版本号。
     */
    private static JsonObject parseJsonRoot(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new InventoryCodecException("物品栏 JSON 编码为空");
        }
        try {
            JsonObject root = JsonParser.parseString(json.trim()).getAsJsonObject();
            if (!root.has("version")) {
                throw new InventoryCodecException("物品栏 JSON 缺少 version 字段");
            }
            if (!FORMAT_VERSION.equals(root.get("version").getAsString())) {
                throw new InventoryCodecException(
                        "不支持的 JSON 格式版本: " + root.get("version").getAsString()
                                + "（当前支持 " + FORMAT_VERSION + "）");
            }
            return root;
        } catch (InventoryCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new InventoryCodecException("物品栏 JSON 解析失败: " + json, e);
        }
    }

    /**
     * 解析 header：{@code V1:<size>:<entries>}。
     */
    private static ParsedHeader parseHeader(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new InventoryCodecException("物品栏编码为空");
        }
        // split limit=3 保留 entries 段（即使为空）
        String[] parts = encoded.split(SEP_VERSION, 3);
        if (parts.length < 3) {
            throw new InventoryCodecException("物品栏编码格式非法（缺少字段）: " + encoded);
        }
        if (!FORMAT_VERSION.equals(parts[0])) {
            throw new InventoryCodecException(
                    "不支持的格式版本: " + parts[0] + "（当前支持 " + FORMAT_VERSION + "）");
        }
        int size;
        try {
            size = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new InventoryCodecException("物品栏容量解析失败: " + parts[1], e);
        }
        if (size < 0) {
            throw new InventoryCodecException("物品栏容量不能为负: " + size);
        }
        return new ParsedHeader(size, parts[2]);
    }

    /**
     * 解析条目段：{@code <index>=<base64>,<index>=<base64>,...}。
     */
    private static Map<Integer, Item> decodeEntries(String entries) {
        Map<Integer, Item> slots = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) {
            return slots; // 空物品栏
        }
        String[] pairs = entries.split(SEP_ENTRY);
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int sep = pair.indexOf(SEP_SLOT);
            if (sep <= 0) {
                throw new InventoryCodecException("槽位条目格式非法: " + pair);
            }
            int index;
            try {
                index = Integer.parseInt(pair.substring(0, sep));
            } catch (NumberFormatException e) {
                throw new InventoryCodecException("槽位索引解析失败: " + pair, e);
            }
            if (index < 0) {
                throw new InventoryCodecException("槽位索引不能为负: " + index);
            }
            String base64 = pair.substring(sep + SEP_SLOT.length());
            Item item = ItemCodec.decode(base64);
            slots.put(index, item);
        }
        return slots;
    }

    /**
     * 解析后的 header 数据。
     */
    private static final class ParsedHeader {
        final int size;
        final String entries;

        ParsedHeader(int size, String entries) {
            this.size = size;
            this.entries = entries;
        }
    }
}
