package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InventoryCodec} 单元测试。
 * <p>
 * 覆盖：Base64 文本格式与 JSON 格式的结构校验、稀疏存储、槽位位置往返、
 * 容量校验、apply/applyJson 真实填充、异常输入。
 */
@DisplayName("InventoryCodec 物品栏编解码测试")
class InventoryCodecTest {

    private static final int STONE_ID = 1;
    private static final int COBBLE_ID = 4;
    private static final int CHEST_SIZE = 27;

    private Item[] buildMixedSlots() {
        Item[] slots = new Item[CHEST_SIZE];
        for (int i = 0; i < CHEST_SIZE; i++) {
            slots[i] = Item.get(Item.AIR);
        }
        slots[0] = Item.get(STONE_ID, 0, 10);
        slots[5] = Item.get(COBBLE_ID, 2, 20);
        Item named = Item.get(STONE_ID, 0, 1);
        named.setNamedTag(new CompoundTag().putString("display_name", "魔法石"));
        slots[10] = named;
        return slots;
    }

    @Nested
    @DisplayName("Base64 文本格式")
    class Base64Format {

        @Test
        @DisplayName("encode 输出以 V1 开头并包含容量")
        void encodeHeader() {
            Item[] slots = new Item[CHEST_SIZE];
            for (int i = 0; i < CHEST_SIZE; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[0] = Item.get(STONE_ID, 0, 5);

            String encoded = InventoryCodec.encodeSlots(slots, CHEST_SIZE);
            assertTrue(encoded.startsWith("V1:"), "应以 V1 开头: " + encoded);
            assertTrue(encoded.contains("27"), "应含容量 27: " + encoded);
        }

        @Test
        @DisplayName("空气槽位不写入（稀疏存储）")
        void sparseStorage() {
            Item[] slots = new Item[CHEST_SIZE];
            for (int i = 0; i < CHEST_SIZE; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[0] = Item.get(STONE_ID, 0, 1);

            String encoded = InventoryCodec.encodeSlots(slots, CHEST_SIZE);
            // 只应有一个非空条目（index=0）
            // 注意：不能用 '=' 字符计数来判断条目数，因为 Base64 padding 也含 '='
            Map<Integer, Item> decoded = InventoryCodec.decodeSlots(encoded);
            assertEquals(1, decoded.size(), "只应有 1 个非空条目: " + encoded);
            assertEquals(STONE_ID, decoded.get(0).getId());
        }

        @Test
        @DisplayName("槽位位置完整保留")
        void slotPositionsPreserved() {
            Item[] original = buildMixedSlots();
            Map<Integer, Item> restored = InventoryCodec.decodeSlots(
                    InventoryCodec.encodeSlots(original, CHEST_SIZE));

            assertEquals(3, restored.size());
            assertTrue(restored.containsKey(0));
            assertTrue(restored.containsKey(5));
            assertTrue(restored.containsKey(10));
            assertEquals(STONE_ID, restored.get(0).getId());
            assertEquals(COBBLE_ID, restored.get(5).getId());
            assertEquals("魔法石", restored.get(10).getNamedTag().getString("display_name"));
        }

        @Test
        @DisplayName("decodeSize 解析容量")
        void decodeSizeParses() {
            Item[] slots = new Item[CHEST_SIZE];
            for (int i = 0; i < CHEST_SIZE; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[0] = Item.get(STONE_ID, 0, 1);

            String encoded = InventoryCodec.encodeSlots(slots, CHEST_SIZE);
            assertEquals(CHEST_SIZE, InventoryCodec.decodeSize(encoded));
        }

        @Test
        @DisplayName("apply 真实填充到 Inventory")
        void applyFillsInventory() {
            Item[] original = buildMixedSlots();
            Inventory inventory = new FakeInventory(null, InventoryType.CHEST);
            InventoryCodec.apply(inventory, InventoryCodec.encodeSlots(original, CHEST_SIZE));

            assertEquals(STONE_ID, inventory.getItem(0).getId());
            assertEquals(10, inventory.getItem(0).getCount());
            assertEquals(COBBLE_ID, inventory.getItem(5).getId());
            assertEquals(2, inventory.getItem(5).getDamage());
            // 未填充的槽位应为空气
            assertEquals(Item.AIR, inventory.getItem(1).getId());
        }

        @Test
        @DisplayName("apply 容量不匹配抛出异常")
        void applySizeMismatchThrows() {
            Item[] slots = new Item[9];
            for (int i = 0; i < 9; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[0] = Item.get(STONE_ID, 0, 1);

            Inventory inventory = new FakeInventory(null, InventoryType.CHEST);
            String encoded = InventoryCodec.encodeSlots(slots, 9);
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.apply(inventory, encoded));
        }

        @Test
        @DisplayName("非法格式抛出异常")
        void invalidFormatThrows() {
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.decodeSlots("!!!invalid!!!"));
        }

        @Test
        @DisplayName("版本号错误抛出异常")
        void wrongVersionThrows() {
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.decodeSlots("V2:27:"));
        }
    }

    @Nested
    @DisplayName("JSON 格式")
    class JsonFormat {

        @Test
        @DisplayName("encodeJson 输出合法 JSON 对象含 version/size/slots")
        void encodeJsonStructure() {
            Item[] slots = buildMixedSlots();
            String json = InventoryCodec.encodeSlotsJson(slots, CHEST_SIZE);

            assertTrue(JsonParser.parseString(json).isJsonObject(), "应是 JSON 对象: " + json);
            assertTrue(json.contains("\"version\":\"V1\""), "应含 version: " + json);
            assertTrue(json.contains("\"size\":27"), "应含 size: " + json);
            assertTrue(json.contains("\"slots\""), "应含 slots: " + json);
        }

        @Test
        @DisplayName("JSON 稀疏存储（空气不写入 slots 数组）")
        void jsonSparseStorage() {
            Item[] slots = new Item[CHEST_SIZE];
            for (int i = 0; i < CHEST_SIZE; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[3] = Item.get(STONE_ID, 0, 1);

            String json = InventoryCodec.encodeSlotsJson(slots, CHEST_SIZE);
            // slots 数组应只有 1 个元素（空气稀疏省略）
            assertTrue(json.contains("\"slots\":["), "应含 slots 数组: " + json);
            assertTrue(json.contains("\"index\":3"), "应含 index 3: " + json);
        }

        @Test
        @DisplayName("JSON 槽位位置完整保留")
        void jsonSlotPositionsPreserved() {
            Item[] original = buildMixedSlots();
            Map<Integer, Item> restored = InventoryCodec.decodeJson(
                    InventoryCodec.encodeSlotsJson(original, CHEST_SIZE));

            assertEquals(3, restored.size());
            assertTrue(restored.containsKey(0));
            assertTrue(restored.containsKey(5));
            assertTrue(restored.containsKey(10));
            assertEquals(STONE_ID, restored.get(0).getId());
            assertEquals(10, restored.get(0).getCount());
            assertEquals(COBBLE_ID, restored.get(5).getId());
            assertEquals(2, restored.get(5).getDamage());
            assertEquals("魔法石", restored.get(10).getNamedTag().getString("display_name"));
        }

        @Test
        @DisplayName("applyJson 真实填充到 Inventory")
        void applyJsonFillsInventory() {
            Item[] original = buildMixedSlots();
            Inventory inventory = new FakeInventory(null, InventoryType.CHEST);
            InventoryCodec.applyJson(inventory, InventoryCodec.encodeSlotsJson(original, CHEST_SIZE));

            assertEquals(STONE_ID, inventory.getItem(0).getId());
            assertEquals(10, inventory.getItem(0).getCount());
            assertEquals(COBBLE_ID, inventory.getItem(5).getId());
            assertEquals(2, inventory.getItem(5).getDamage());
            assertEquals("魔法石", inventory.getItem(10).getNamedTag().getString("display_name"));
            assertEquals(Item.AIR, inventory.getItem(1).getId());
        }

        @Test
        @DisplayName("applyJson 容量不匹配抛出异常")
        void applyJsonSizeMismatchThrows() {
            Item[] slots = new Item[9];
            for (int i = 0; i < 9; i++) {
                slots[i] = Item.get(Item.AIR);
            }
            slots[0] = Item.get(STONE_ID, 0, 1);

            Inventory inventory = new FakeInventory(null, InventoryType.CHEST);
            String json = InventoryCodec.encodeSlotsJson(slots, 9);
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.applyJson(inventory, json));
        }

        @Test
        @DisplayName("JSON 版本号错误抛出异常")
        void jsonWrongVersionThrows() {
            String badJson = "{\"version\":\"V2\",\"size\":27,\"slots\":[]}";
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.decodeJson(badJson));
        }

        @Test
        @DisplayName("非法 JSON 抛出异常")
        void invalidJsonThrows() {
            assertThrows(InventoryCodecException.class,
                    () -> InventoryCodec.decodeJson("{不是合法json"));
        }

        @Test
        @DisplayName("JSON 与 Base64 两种格式还原结果等价")
        void jsonAndBase64Equivalent() {
            Item[] original = buildMixedSlots();
            Map<Integer, Item> fromBase64 = InventoryCodec.decodeSlots(
                    InventoryCodec.encodeSlots(original, CHEST_SIZE));
            Map<Integer, Item> fromJson = InventoryCodec.decodeJson(
                    InventoryCodec.encodeSlotsJson(original, CHEST_SIZE));

            assertEquals(fromBase64.size(), fromJson.size());
            for (Integer idx : fromBase64.keySet()) {
                Item b = fromBase64.get(idx);
                Item j = fromJson.get(idx);
                assertEquals(b.getId(), j.getId());
                assertEquals(b.getDamage(), j.getDamage());
                assertEquals(b.getCount(), j.getCount());
            }
        }

        @Test
        @DisplayName("encodeJson(Inventory) 直接接受 Inventory 实例")
        void encodeJsonFromInventory() {
            Inventory inventory = new FakeInventory(null, InventoryType.CHEST);
            inventory.setItem(0, Item.get(STONE_ID, 0, 8));
            inventory.setItem(15, Item.get(COBBLE_ID, 1, 3));

            String json = InventoryCodec.encodeJson(inventory);
            Map<Integer, Item> restored = InventoryCodec.decodeJson(json);

            assertEquals(2, restored.size());
            assertEquals(8, restored.get(0).getCount());
            assertEquals(COBBLE_ID, restored.get(15).getId());
        }
    }
}
