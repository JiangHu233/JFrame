package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ItemCodec} 单元测试。
 * <p>
 * 覆盖：空气/物品边界、基础物品往返、带 NBT 物品往返、非法输入异常，
 * 以及 JSON 形式（{@link ItemCodec#encodeJson} / {@link ItemCodec#decodeJson}）的往返。
 */
@DisplayName("ItemCodec 物品编解码测试")
class ItemCodecTest {

    private static final int STONE_ID = 1;
    private static final int COBBLE_ID = 4;

    @Nested
    @DisplayName("空气 / null 边界")
    class AirBoundary {

        @Test
        @DisplayName("encode(null) 返回空字符串")
        void encodeNullReturnsEmpty() {
            assertEquals("", ItemCodec.encode(null));
        }

        @Test
        @DisplayName("encode(空气) 返回空字符串")
        void encodeAirReturnsEmpty() {
            assertEquals("", ItemCodec.encode(Item.get(Item.AIR)));
        }

        @Test
        @DisplayName("decode(空字符串) 返回空气物品")
        void decodeEmptyReturnsAir() {
            assertEquals(Item.AIR, ItemCodec.decode("").getId());
            assertEquals(Item.AIR, ItemCodec.decode(null).getId());
        }
    }

    @Nested
    @DisplayName("基础物品往返（Base64 格式）")
    class BasicItemRoundTrip {

        @Test
        @DisplayName("普通物品 id/damage/count 完整保留")
        void basicRoundTrip() {
            Item original = Item.get(STONE_ID, 0, 32);
            String encoded = ItemCodec.encode(original);
            assertFalse(encoded.isEmpty());

            Item restored = ItemCodec.decode(encoded);
            assertEquals(STONE_ID, restored.getId());
            assertEquals(0, restored.getDamage());
            assertEquals(32, restored.getCount());
        }

        @Test
        @DisplayName("带 damage 的物品往返")
        void damageRoundTrip() {
            Item restored = ItemCodec.decode(ItemCodec.encode(Item.get(COBBLE_ID, 3, 1)));
            assertEquals(COBBLE_ID, restored.getId());
            assertEquals(3, restored.getDamage());
        }
    }

    @Nested
    @DisplayName("带 NBT 物品往返（完整保真）")
    class NbtItemRoundTrip {

        @Test
        @DisplayName("自定义 NBT 标签完整保留")
        void customNbtRoundTrip() {
            Item original = Item.get(STONE_ID, 0, 1);
            original.setNamedTag(new CompoundTag()
                    .putString("custom_key", "hello")
                    .putInt("level", 99));

            Item restored = ItemCodec.decode(ItemCodec.encode(original));
            assertTrue(restored.hasCompoundTag());
            assertEquals("hello", restored.getNamedTag().getString("custom_key"));
            assertEquals(99, restored.getNamedTag().getInt("level"));
        }

        @Test
        @DisplayName("嵌套 CompoundTag 保留")
        void nestedNbtRoundTrip() {
            Item original = Item.get(STONE_ID, 0, 1);
            original.setNamedTag(new CompoundTag()
                    .putCompound("nested", new CompoundTag().putString("inner_key", "inner_value")));

            Item restored = ItemCodec.decode(ItemCodec.encode(original));
            assertEquals("inner_value", restored.getNamedTag().getCompound("nested").getString("inner_key"));
        }
    }

    @Nested
    @DisplayName("JSON 形式往返")
    class JsonRoundTrip {

        @Test
        @DisplayName("encodeJson 输出合法 JSON 对象")
        void encodeJsonProducesValidObject() {
            Item item = Item.get(STONE_ID, 0, 10);
            String json = ItemCodec.encodeJson(item);
            // 应是合法 JSON 对象，含 id/damage/count
            assertTrue(JsonParser.parseString(json).isJsonObject(), "应是 JSON 对象: " + json);
            assertTrue(json.contains("\"id\":1"), "应含 id: " + json);
            assertTrue(json.contains("\"count\":10"), "应含 count: " + json);
        }

        @Test
        @DisplayName("空气物品 encodeJson 返回 null")
        void airEncodeJsonReturnsNull() {
            assertEquals("null", ItemCodec.encodeJson(null));
            assertEquals("null", ItemCodec.encodeJson(Item.get(Item.AIR)));
        }

        @Test
        @DisplayName("基础物品 JSON 往返")
        void basicJsonRoundTrip() {
            Item original = Item.get(STONE_ID, 2, 15);
            Item restored = ItemCodec.decodeJson(ItemCodec.encodeJson(original));
            assertEquals(STONE_ID, restored.getId());
            assertEquals(2, restored.getDamage());
            assertEquals(15, restored.getCount());
        }

        @Test
        @DisplayName("带 NBT 物品 JSON 往返（可读 NBT 结构）")
        void nbtJsonRoundTrip() {
            Item original = Item.get(STONE_ID, 0, 1);
            original.setNamedTag(new CompoundTag()
                    .putString("name", "宝物")
                    .putInt("level", 5));

            String json = ItemCodec.encodeJson(original);
            assertTrue(json.contains("\"nbt\""), "JSON 应含 nbt 字段: " + json);
            // 可读性验证：nbt 字段是可读 JSON 结构，含明文的 name/level（非 Base64 乱码）
            assertTrue(json.contains("\"name\":\"宝物\""), "nbt 应为可读结构含 name: " + json);
            assertTrue(json.contains("\"level\":5"), "nbt 应为可读结构含 level: " + json);

            Item restored = ItemCodec.decodeJson(json);
            assertTrue(restored.hasCompoundTag());
            assertEquals("宝物", restored.getNamedTag().getString("name"));
            assertEquals(5, restored.getNamedTag().getInt("level"));
        }

        @Test
        @DisplayName("无 NBT 物品 JSON 不含 nbt 字段")
        void noNbtJsonOmitsField() {
            Item original = Item.get(STONE_ID, 0, 1);
            String json = ItemCodec.encodeJson(original);
            assertFalse(json.contains("\"nbt\""), "无 NBT 时不应含 nbt 字段: " + json);
        }

        @Test
        @DisplayName("decodeJson(null/空白/\"null\") 返回空气")
        void decodeJsonAirBoundary() {
            assertEquals(Item.AIR, ItemCodec.decodeJson(null).getId());
            assertEquals(Item.AIR, ItemCodec.decodeJson("").getId());
            assertEquals(Item.AIR, ItemCodec.decodeJson("null").getId());
            assertEquals(Item.AIR, ItemCodec.decodeJson("  ").getId());
        }

        @Test
        @DisplayName("JSON 与 Base64 两种格式还原结果等价")
        void jsonAndBase64Equivalent() {
            Item original = Item.get(COBBLE_ID, 1, 7);
            original.setNamedTag(new CompoundTag().putString("k", "v"));

            Item fromBase64 = ItemCodec.decode(ItemCodec.encode(original));
            Item fromJson = ItemCodec.decodeJson(ItemCodec.encodeJson(original));

            assertEquals(fromBase64.getId(), fromJson.getId());
            assertEquals(fromBase64.getDamage(), fromJson.getDamage());
            assertEquals(fromBase64.getCount(), fromJson.getCount());
            assertEquals("v", fromJson.getNamedTag().getString("k"));
        }
    }

    @Nested
    @DisplayName("异常输入")
    class InvalidInput {

        @Test
        @DisplayName("非法 Base64 抛出异常")
        void invalidBase64Throws() {
            assertThrows(InventoryCodecException.class, () -> ItemCodec.decode("!!!invalid!!!"));
        }

        @Test
        @DisplayName("非法 JSON 抛出异常")
        void invalidJsonThrows() {
            assertThrows(InventoryCodecException.class, () -> ItemCodec.decodeJson("{不是合法json"));
        }
    }
}
