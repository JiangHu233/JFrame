package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.Tag;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 物品编解码器 —— {@link Item} 与 Base64-NBT 字符串互转。
 * <p>
 * 采用「完整保真」策略：序列化物品的 id / damage / count，以及全部附加 NBT
 * （含自定义名称、lore、附魔、自定义数据等）。附加 NBT 以嵌套 {@code "tag"} 字段
 * 整体保存，{@link #encode} 与 {@link #decode} 完全对称。
 *
 * <h3>编码规则</h3>
 * <ul>
 *   <li>{@code null} 或空气物品（{@link Item#AIR}）→ 空字符串 {@code ""}</li>
 *   <li>非空物品 → {@code Base64( NBTIO.write(nbt, LITTLE_ENDIAN, network=true) )}</li>
 * </ul>
 *
 * <h3>NBT 字节序</h3>
 * <p>
 * 采用 {@link ByteOrder#LITTLE_ENDIAN} + {@code network=true}（基岩版协议格式），
 * 与项目内 NBT 写入方案一致，确保跨版本稳定。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Item sword = Item.get(Item.DIAMOND_SWORD);
 * sword.setCustomName("§b屠龙剑");
 * sword.setLore("§7传说武器");
 *
 * String encoded = ItemCodec.encode(sword);   // → Base64 字符串
 * Item restored = ItemCodec.decode(encoded);  // → 完整还原（含名称/lore/附魔）
 * }</pre>
 *
 * <h3>实现说明</h3>
 * <p>
 * 不依赖 {@code item.saveToNBT()} / {@code Item.fromNBT()}（不同 Nukkit 分支方法名不一），
 * 而是手动构造对称的 {@link CompoundTag}，仅依赖最稳定的 API：
 * {@link Item#getId()} / {@link Item#getDamage()} / {@link Item#getCount()} /
 * {@link Item#hasCompoundTag()} / {@link Item#getNamedTag()} / {@link Item#setNamedTag(CompoundTag)}。
 *
 * @see InventoryCodec
 */
public final class ItemCodec {

    /** 附加 NBT 的嵌套键名 */
    private static final String KEY_EXTRA_NBT = "tag";
    /** 物品 namespace 键名（基岩版 1.21+ 新物品仅有 namespace，无固定数字 ID） */
    private static final String KEY_NAMESPACE = "Namespace";
    /** 物品 id 键名 */
    private static final String KEY_ID = "id";
    /** 物品 damage/meta 键名 */
    private static final String KEY_DAMAGE = "Damage";
    /** 物品 count 键名 */
    private static final String KEY_COUNT = "Count";

    /** JSON 模式：物品 namespace 键名 */
    private static final String JSON_NAMESPACE = "namespace";
    /** JSON 模式：物品 id 键名 */
    private static final String JSON_ID = "id";
    /** JSON 模式：物品 damage 键名 */
    private static final String JSON_DAMAGE = "damage";
    /** JSON 模式：物品 count 键名 */
    private static final String JSON_COUNT = "count";
    /** JSON 模式：附加 NBT（可读 JSON 结构）键名 */
    private static final String JSON_NBT = "nbt";

    /** Gson 实例（无状态，线程安全，复用） */
    private static final Gson GSON = new Gson();

    private ItemCodec() {
    }

    /**
     * 将物品序列化为 Base64-NBT 字符串。
     *
     * @param item 物品，{@code null} 或空气返回空字符串
     * @return Base64 字符串，或空字符串（空气）
     * @throws InventoryCodecException 若 NBT 写入失败
     */
    public static String encode(Item item) {
        if (item == null || item.getId() == Item.AIR) {
            return "";
        }
        try {
            CompoundTag tag = itemToNbt(item);
            byte[] bytes = NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN, true);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new InventoryCodecException("物品序列化失败（NBT 写入异常）: " + describe(item), e);
        } catch (Exception e) {
            throw new InventoryCodecException("物品序列化失败: " + describe(item), e);
        }
    }

    /**
     * 将 Base64-NBT 字符串反序列化为物品。
     *
     * @param encoded Base64 字符串，{@code null}/空白返回空气物品
     * @return 物品，空字符串返回 {@link Item#get(int) Item.get(Item.AIR)}
     * @throws InventoryCodecException 若 Base64 解码或 NBT 解析失败
     */
    public static Item decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Item.get(Item.AIR);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            CompoundTag tag = NBTIO.read(bytes, ByteOrder.LITTLE_ENDIAN, true);
            return nbtToItem(tag);
        } catch (IOException e) {
            throw new InventoryCodecException("物品反序列化失败（NBT 解析异常）: " + encoded, e);
        } catch (Exception e) {
            throw new InventoryCodecException("物品反序列化失败: " + encoded, e);
        }
    }

    // ==================== JSON 形式（可读，调试友好） ====================

    /**
     * 将物品序列化为可读 JSON 字符串。
     * <p>
     * 与 {@link #encode(Item)} 的紧凑 Base64 格式并列，JSON 形式结构清晰、
     * 便于调试查看与跨系统交换。附加 NBT 以 {@code "nbt"} 字段展开为<b>可读 JSON 结构</b>
     * （由 {@link NbtJsonConverter} 转换）：常见类型（string/int/double/compound/list）直接
     * 映射为 JSON 原生类型，byte/short/long/float 与数组类型用 {@code {"__nbt":"...","value":...}}
     * 标记保真，确保 {@code JSON → NBT} 往返等价。
     *
     * <h3>JSON 结构</h3>
     * <pre>{@code
     * {
     *   "id": 276,
     *   "damage": 0,
     *   "count": 1,
     *   "nbt": {                       // 可读 NBT 结构，无附加 NBT 时省略
     *     "display": { "Name": "§b屠龙剑" },
     *     "rarity": "legendary",
     *     "kill_count": 0
     *   }
     * }
     * }</pre>
     *
     * @param item 物品，{@code null} 或空气返回 {@code "null"}
     * @return JSON 字符串
     * @throws InventoryCodecException 若 NBT 写入失败
     */
    public static String encodeJson(Item item) {
        return GSON.toJson(itemToJson(item));
    }

    /**
     * 将 JSON 字符串反序列化为物品（与 {@link #encodeJson(Item)} 对称）。
     *
     * @param json JSON 字符串，{@code null}/空白/{@code "null"} 返回空气物品
     * @return 物品
     * @throws InventoryCodecException 若 JSON 解析或 NBT 还原失败
     */
    public static Item decodeJson(String json) {
        if (json == null) {
            return Item.get(Item.AIR);
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return Item.get(Item.AIR);
        }
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            return jsonToItem(obj);
        } catch (InventoryCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new InventoryCodecException("物品 JSON 反序列化失败: " + json, e);
        }
    }

    /**
     * 判断字符串是否表示空气物品（空字符串）。
     *
     * @param encoded 编码字符串
     * @return 为空则表示空气
     */
    static boolean isAir(String encoded) {
        return encoded == null || encoded.isEmpty();
    }

    /**
     * Item → JsonObject（可读 JSON，附加 NBT 展开为可读结构）。
     * <p>
     * 空气物品返回 {@code null}（序列化为 JSON {@code null}）。
     * 供 {@link #encodeJson(Item)} 与 {@link InventoryCodec#encodeSlotsJson} 复用。
     */
    static JsonObject itemToJson(Item item) {
        if (item == null || item.getId() == Item.AIR) {
            return null;
        }
        JsonObject obj = new JsonObject();
        // namespace 优先存储（基岩版 1.21+ 新物品仅有 namespace，无固定数字 ID）
        String namespace = safeGetNamespaceId(item);
        if (namespace != null && !namespace.isEmpty()) {
            obj.addProperty(JSON_NAMESPACE, namespace);
        }
        obj.addProperty(JSON_ID, item.getId());
        obj.addProperty(JSON_DAMAGE, item.getDamage());
        obj.addProperty(JSON_COUNT, item.getCount());
        if (item.hasCompoundTag()) {
            CompoundTag extra = item.getNamedTag();
            if (extra != null) {
                obj.add(JSON_NBT, NbtJsonConverter.tagToJson(extra));
            }
        }
        return obj;
    }

    /**
     * JsonObject → Item（与 {@link #itemToJson} 对称）。
     */
    static Item jsonToItem(JsonObject obj) {
        if (obj == null || obj.isJsonNull()) {
            return Item.get(Item.AIR);
        }
        int damage = obj.has(JSON_DAMAGE) ? obj.get(JSON_DAMAGE).getAsInt() : 0;
        int count = obj.has(JSON_COUNT) ? obj.get(JSON_COUNT).getAsInt() : 1;
        Item item;
        // 优先使用 namespace 创建物品（基岩版 1.21+ 新物品仅有 namespace）
        if (obj.has(JSON_NAMESPACE) && !obj.get(JSON_NAMESPACE).isJsonNull()) {
            String namespace = obj.get(JSON_NAMESPACE).getAsString();
            item = Item.fromString(namespace);
            item.setDamage(damage);
            item.setCount(count);
        } else {
            // 回退到数字 ID（向后兼容旧格式数据）
            int id = obj.get(JSON_ID).getAsInt();
            item = Item.get(id, damage, count);
        }
        if (obj.has(JSON_NBT) && !obj.get(JSON_NBT).isJsonNull()) {
            Tag tag = NbtJsonConverter.jsonToTag(obj.get(JSON_NBT));
            if (!(tag instanceof CompoundTag)) {
                throw new InventoryCodecException("物品的 nbt 字段必须是 JSON 对象: " + obj.get(JSON_NBT));
            }
            item.setNamedTag((CompoundTag) tag);
        }
        return item;
    }

    /**
     * Item → CompoundTag（手动对称构造）。
     * <p>
     * 结构：{@code { id, Damage, Count, tag?: <附加NBT> }}
     */
    private static CompoundTag itemToNbt(Item item) {
        CompoundTag tag = new CompoundTag()
                .putInt(KEY_ID, item.getId())
                .putInt(KEY_DAMAGE, item.getDamage())
                .putByte(KEY_COUNT, item.getCount());
        // namespace 优先存储（基岩版 1.21+ 新物品仅有 namespace，无固定数字 ID）
        String namespace = safeGetNamespaceId(item);
        if (namespace != null && !namespace.isEmpty()) {
            tag.putString(KEY_NAMESPACE, namespace);
        }
        if (item.hasCompoundTag()) {
            CompoundTag extra = item.getNamedTag();
            if (extra != null) {
                tag.putCompound(KEY_EXTRA_NBT, extra);
            }
        }
        return tag;
    }

    /**
     * CompoundTag → Item（与 {@link #itemToNbt} 对称）。
     */
    private static Item nbtToItem(CompoundTag tag) {
        int damage = readIntOrShort(tag, KEY_DAMAGE);
        int count = tag.getByte(KEY_COUNT);
        Item item;
        // 优先使用 namespace 创建物品（基岩版 1.21+ 新物品仅有 namespace）
        if (tag.contains(KEY_NAMESPACE)) {
            String namespace = tag.getString(KEY_NAMESPACE);
            item = Item.fromString(namespace);
            item.setDamage(damage);
            item.setCount(count);
        } else {
            // 回退到数字 ID（向后兼容旧格式数据，兼容 short/int 两种 NBT 类型）
            int id = readIntOrShort(tag, KEY_ID);
            item = Item.get(id, damage, count);
        }
        if (tag.contains(KEY_EXTRA_NBT)) {
            CompoundTag extra = tag.getCompound(KEY_EXTRA_NBT);
            if (extra != null) {
                item.setNamedTag(extra);
            }
        }
        return item;
    }

    /**
     * 安全获取物品的 namespace ID。
     * <p>
     * {@link Item#getNamespaceId()} 内部依赖 {@code RuntimeItems.getMapping()}，
     * 在 RuntimeItems 未初始化的环境（如单元测试）中会抛出 NullPointerException。
     * 本方法捕获异常并返回 null，确保编解码在无服务器环境下也能正常工作。
     * 在服务器运行时，此方法等同于直接调用 {@link Item#getNamespaceId()}。
     *
     * @param item 物品
     * @return namespace ID（如 "minecraft:stone"），或 null（不可用时）
     */
    private static String safeGetNamespaceId(Item item) {
        try {
            return item.getNamespaceId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 CompoundTag 读取 int 或 short 值（向后兼容）。
     * <p>
     * 旧格式用 {@code putShort} 存储 id/damage，新格式改用 {@code putInt}。
     * 本方法通过检测 NBT 节点的实际类型（{@link IntTag} / {@link ShortTag}）来正确读取，
     * 确保新旧格式数据都能兼容。
     *
     * @param tag NBT 复合标签
     * @param key 键名
     * @return 读取到的值，键不存在时返回 0
     */
    private static int readIntOrShort(CompoundTag tag, String key) {
        Tag raw = tag.get(key);
        if (raw instanceof IntTag) {
            return ((IntTag) raw).data;
        }
        if (raw instanceof ShortTag) {
            return ((ShortTag) raw).data;
        }
        return 0;
    }

    /**
     * 生成物品的简短描述（用于异常消息）。
     */
    private static String describe(Item item) {
        if (item == null) {
            return "null";
        }
        return "id=" + item.getId() + ", damage=" + item.getDamage() + ", count=" + item.getCount();
    }
}
