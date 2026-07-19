package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NbtJsonConverter} 单元测试。
 * <p>
 * 核心目标：验证 NBT → JSON → NBT 的<b>类型保真</b>与<b>可读性</b>。
 * <ul>
 *   <li>常见类型（string/int/double/compound/list）直接映射为 JSON 原生类型</li>
 *   <li>罕见类型（byte/short/long/float/byte[]/int[]）用 {@code __nbt} 标记包装，
 *       反向解析后 {@code getClass()} 必须与原类型一致</li>
 * </ul>
 */
@DisplayName("NbtJsonConverter NBT↔可读 JSON 转换测试")
class NbtJsonConverterTest {

    @Nested
    @DisplayName("常见类型直接映射（可读）")
    class DirectMapping {

        @Test
        @DisplayName("StringTag ↔ JSON string")
        void stringTag() {
            StringTag tag = new StringTag("", "hello");
            JsonElement json = NbtJsonConverter.tagToJson(tag);
            assertTrue(json.isJsonPrimitive() && json.getAsJsonPrimitive().isString());
            assertEquals("hello", json.getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(StringTag.class, restored);
            assertEquals("hello", ((StringTag) restored).data);
        }

        @Test
        @DisplayName("IntTag ↔ JSON 整数")
        void intTag() {
            IntTag tag = new IntTag("", 42);
            JsonElement json = NbtJsonConverter.tagToJson(tag);
            assertTrue(json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(IntTag.class, restored);
            assertEquals(42, ((IntTag) restored).data);
        }

        @Test
        @DisplayName("DoubleTag ↔ JSON 小数")
        void doubleTag() {
            DoubleTag tag = new DoubleTag("", 3.14);
            JsonElement json = NbtJsonConverter.tagToJson(tag);

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(DoubleTag.class, restored);
            assertEquals(3.14, ((DoubleTag) restored).data, 0.0001);
        }

        @Test
        @DisplayName("CompoundTag ↔ JSON object")
        void compoundTag() {
            CompoundTag tag = new CompoundTag()
                    .putString("name", "test")
                    .putInt("count", 5);
            JsonElement json = NbtJsonConverter.tagToJson(tag);
            assertTrue(json.isJsonObject());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(CompoundTag.class, restored);
            CompoundTag c = (CompoundTag) restored;
            assertEquals("test", c.getString("name"));
            assertEquals(5, c.getInt("count"));
        }

        @Test
        @DisplayName("ListTag ↔ JSON array")
        void listTag() {
            ListTag<Tag> tag = new ListTag<Tag>()
                    .add(new StringTag("", "a"))
                    .add(new StringTag("", "b"));
            JsonElement json = NbtJsonConverter.tagToJson(tag);
            assertTrue(json.isJsonArray());
            assertEquals(2, json.getAsJsonArray().size());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(ListTag.class, restored);
            assertEquals(2, ((ListTag<?>) restored).size());
        }
    }

    @Nested
    @DisplayName("罕见类型用 __nbt 标记保真（类型必须保留）")
    class TypedWrapper {

        @Test
        @DisplayName("ByteTag 用 __nbt:byte 标记，类型保留")
        void byteTag() {
            ByteTag tag = new ByteTag("", 7);
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("byte", json.get(NbtJsonConverter.TYPE_KEY).getAsString());
            assertEquals(7, json.get("value").getAsInt());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(ByteTag.class, restored);
            assertEquals(7, ((ByteTag) restored).data);
        }

        @Test
        @DisplayName("ShortTag 用 __nbt:short 标记，类型保留")
        void shortTag() {
            ShortTag tag = new ShortTag("", 1000);
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("short", json.get(NbtJsonConverter.TYPE_KEY).getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(ShortTag.class, restored);
            assertEquals(1000, ((ShortTag) restored).data);
        }

        @Test
        @DisplayName("LongTag 用 __nbt:long 标记，类型保留（含超 int 范围值）")
        void longTag() {
            long big = 5000000000L; // 超出 int 范围
            LongTag tag = new LongTag("", big);
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("long", json.get(NbtJsonConverter.TYPE_KEY).getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(LongTag.class, restored);
            assertEquals(big, ((LongTag) restored).data);
        }

        @Test
        @DisplayName("FloatTag 用 __nbt:float 标记，类型保留")
        void floatTag() {
            FloatTag tag = new FloatTag("", 1.5f);
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("float", json.get(NbtJsonConverter.TYPE_KEY).getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(FloatTag.class, restored);
            assertEquals(1.5f, ((FloatTag) restored).data, 0.0001f);
        }

        @Test
        @DisplayName("ByteArrayTag 用 __nbt:byte[] 标记，内容保留")
        void byteArrayTag() {
            ByteArrayTag tag = new ByteArrayTag("", new byte[]{1, 2, 3, (byte) 0xFF});
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("byte[]", json.get(NbtJsonConverter.TYPE_KEY).getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(ByteArrayTag.class, restored);
            assertArrayEquals(new byte[]{1, 2, 3, (byte) 0xFF}, ((ByteArrayTag) restored).data);
        }

        @Test
        @DisplayName("IntArrayTag 用 __nbt:int[] 标记，内容保留")
        void intArrayTag() {
            IntArrayTag tag = new IntArrayTag("", new int[]{10, 20, 30});
            JsonObject json = NbtJsonConverter.tagToJson(tag).getAsJsonObject();
            assertEquals("int[]", json.get(NbtJsonConverter.TYPE_KEY).getAsString());

            Tag restored = NbtJsonConverter.jsonToTag(json);
            assertInstanceOf(IntArrayTag.class, restored);
            assertArrayEquals(new int[]{10, 20, 30}, ((IntArrayTag) restored).data);
        }
    }

    @Nested
    @DisplayName("JSON 字面量类型推断")
    class JsonInference {

        @Test
        @DisplayName("JSON 整数字面量 → IntTag")
        void integerLiteralToInt() {
            Tag tag = NbtJsonConverter.jsonToTag(JsonParser.parseString("123"));
            assertInstanceOf(IntTag.class, tag);
            assertEquals(123, ((IntTag) tag).data);
        }

        @Test
        @DisplayName("JSON 小数字面量 → DoubleTag")
        void decimalLiteralToDouble() {
            Tag tag = NbtJsonConverter.jsonToTag(JsonParser.parseString("1.5"));
            assertInstanceOf(DoubleTag.class, tag);
            assertEquals(1.5, ((DoubleTag) tag).data, 0.0001);
        }

        @Test
        @DisplayName("JSON 指数字面量 → DoubleTag")
        void exponentLiteralToDouble() {
            Tag tag = NbtJsonConverter.jsonToTag(JsonParser.parseString("1e3"));
            assertInstanceOf(DoubleTag.class, tag);
        }

        @Test
        @DisplayName("JSON boolean → ByteTag（1/0）")
        void booleanToByte() {
            Tag trueTag = NbtJsonConverter.jsonToTag(JsonParser.parseString("true"));
            assertInstanceOf(ByteTag.class, trueTag);
            assertEquals(1, ((ByteTag) trueTag).data);

            Tag falseTag = NbtJsonConverter.jsonToTag(JsonParser.parseString("false"));
            assertInstanceOf(ByteTag.class, falseTag);
            assertEquals(0, ((ByteTag) falseTag).data);
        }
    }

    @Test
    @DisplayName("复杂嵌套结构往返保真（混合所有类型）")
    void complexStructureRoundTrip() {
        CompoundTag original = new CompoundTag()
                .putString("owner", "Steve")
                .putCompound("display", new CompoundTag()
                        .putString("Name", "§b神器")
                        .putList("Lore", new ListTag<Tag>()
                                .add(new StringTag("", "第一行"))
                                .add(new StringTag("", "第二行"))))
                .putByte("tier", 3)
                .putInt("durability", 100)
                .putDouble("weight", 2.5);

        JsonElement json = NbtJsonConverter.tagToJson(original);
        // 可读性：明文可见关键字段（非 Base64 乱码）
        String jsonStr = json.toString();
        assertTrue(jsonStr.contains("\"owner\":\"Steve\""), "应含明文 owner: " + jsonStr);
        assertTrue(jsonStr.contains("\"Name\":\"§b神器\""), "应含明文 Name: " + jsonStr);
        assertTrue(jsonStr.contains("\"第一行\""), "应含明文 Lore: " + jsonStr);

        Tag restored = NbtJsonConverter.jsonToTag(json);
        assertInstanceOf(CompoundTag.class, restored);
        CompoundTag c = (CompoundTag) restored;
        assertEquals("Steve", c.getString("owner"));
        assertEquals("§b神器", c.getCompound("display").getString("Name"));
        assertEquals(2, c.getCompound("display").getList("Lore").size());
        // byte 类型保真（不能降级为 IntTag）
        assertInstanceOf(ByteTag.class, c.get("tier"), "tier 必须保留为 ByteTag");
        assertEquals(3, c.getByte("tier"));
        assertEquals(100, c.getInt("durability"));
        assertEquals(2.5, c.getDouble("weight"), 0.0001);
    }

    @Test
    @DisplayName("未知 __nbt 标记抛出异常")
    void unknownTypeThrows() {
        JsonObject bad = new JsonObject();
        bad.addProperty(NbtJsonConverter.TYPE_KEY, "nonexistent");
        bad.addProperty("value", 1);
        assertThrows(InventoryCodecException.class, () -> NbtJsonConverter.jsonToTag(bad));
    }

    @Test
    @DisplayName("__nbt 标记缺少 value 字段抛出异常")
    void missingValueThrows() {
        JsonObject bad = new JsonObject();
        bad.addProperty(NbtJsonConverter.TYPE_KEY, "byte");
        assertThrows(InventoryCodecException.class, () -> NbtJsonConverter.jsonToTag(bad));
    }
}
