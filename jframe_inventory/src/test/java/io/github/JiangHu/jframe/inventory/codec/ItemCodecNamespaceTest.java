package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ItemCodec namespace 支持与向后兼容测试。
 * <p>
 * 验证修复后的 ItemCodec 能正确处理：
 * <ul>
 *   <li>基岩版 1.21+ 新物品（仅有 namespace，无固定数字 ID）</li>
 *   <li>大数字 ID（> 32767，超出 short 范围）</li>
 *   <li>旧格式数据向后兼容（无 namespace 字段、short 格式 ID）</li>
 * </ul>
 * <p>
 * 注意：单元测试环境中 RuntimeItems 未初始化，{@code Item.getNamespaceId()} 返回 null，
 * {@code Item.fromString()} 返回 id=0 的通用物品。因此 namespace 往返的完整验证
 * 需在服务器运行时进行；本测试主要验证编解码逻辑的正确性和向后兼容性。
 */
@DisplayName("ItemCodec namespace 支持与向后兼容")
class ItemCodecNamespaceTest {

    private static final int STONE_ID = 1;
    private static final int COBBLE_ID = 4;

    // ================================================================
    //  基础回归：修改不破坏现有功能
    // ================================================================

    @Nested
    @DisplayName("基础回归：修改不破坏现有编解码")
    class BasicRegression {

        @Test
        @DisplayName("Base64 基础物品往返仍正常")
        void base64BasicRoundTrip() {
            Item original = Item.get(STONE_ID, 0, 32);
            Item restored = ItemCodec.decode(ItemCodec.encode(original));
            assertEquals(STONE_ID, restored.getId());
            assertEquals(0, restored.getDamage());
            assertEquals(32, restored.getCount());
        }

        @Test
        @DisplayName("JSON 基础物品往返仍正常")
        void jsonBasicRoundTrip() {
            Item original = Item.get(COBBLE_ID, 2, 15);
            Item restored = ItemCodec.decodeJson(ItemCodec.encodeJson(original));
            assertEquals(COBBLE_ID, restored.getId());
            assertEquals(2, restored.getDamage());
            assertEquals(15, restored.getCount());
        }

        @Test
        @DisplayName("带 NBT 物品 Base64 往返仍正常")
        void nbtBase64RoundTrip() {
            Item original = Item.get(STONE_ID, 0, 1);
            original.setNamedTag(new CompoundTag()
                    .putString("custom", "value")
                    .putInt("level", 42));

            Item restored = ItemCodec.decode(ItemCodec.encode(original));
            assertEquals(STONE_ID, restored.getId());
            assertTrue(restored.hasCompoundTag());
            assertEquals("value", restored.getNamedTag().getString("custom"));
            assertEquals(42, restored.getNamedTag().getInt("level"));
        }
    }

    // ================================================================
    //  向后兼容：旧格式数据（无 namespace，short 格式 ID）
    // ================================================================

    @Nested
    @DisplayName("向后兼容：旧格式数据")
    class BackwardCompatibility {

        /**
         * 手动构造旧格式 Base64 数据（用 putShort 存储 ID），验证新代码能正确解码。
         * <p>
         * 旧格式 NBT 结构：{ id: TAG_Short, Damage: TAG_Short, Count: TAG_Byte }
         * 新格式 NBT 结构：{ id: TAG_Int, Damage: TAG_Int, Count: TAG_Byte, Namespace?: TAG_String }
         */
        @Test
        @DisplayName("旧格式 Base64（short ID）能被正确解码")
        void legacyBase64ShortIdDecodes() throws Exception {
            // 构造旧格式 NBT（模拟修复前的 putShort 格式）
            CompoundTag legacyTag = new CompoundTag()
                    .putShort("id", (short) STONE_ID)
                    .putShort("Damage", (short) 0)
                    .putByte("Count", (byte) 10);
            byte[] bytes = NBTIO.write(legacyTag, ByteOrder.LITTLE_ENDIAN, true);
            String legacyBase64 = Base64.getEncoder().encodeToString(bytes);

            // 新代码应能正确解码旧格式
            Item decoded = ItemCodec.decode(legacyBase64);
            assertEquals(STONE_ID, decoded.getId(), "旧格式 short ID 应被正确读取");
            assertEquals(0, decoded.getDamage());
            assertEquals(10, decoded.getCount());
        }

        /**
         * 手动构造旧格式 Base64 数据（带 damage），验证新代码能正确解码。
         */
        @Test
        @DisplayName("旧格式 Base64（short ID + damage）能被正确解码")
        void legacyBase64WithDamageDecodes() throws Exception {
            CompoundTag legacyTag = new CompoundTag()
                    .putShort("id", (short) COBBLE_ID)
                    .putShort("Damage", (short) 3)
                    .putByte("Count", (byte) 5);
            byte[] bytes = NBTIO.write(legacyTag, ByteOrder.LITTLE_ENDIAN, true);
            String legacyBase64 = Base64.getEncoder().encodeToString(bytes);

            Item decoded = ItemCodec.decode(legacyBase64);
            assertEquals(COBBLE_ID, decoded.getId());
            assertEquals(3, decoded.getDamage());
            assertEquals(5, decoded.getCount());
        }

        /**
         * 旧格式 JSON（无 namespace 字段）能被正确解码。
         */
        @Test
        @DisplayName("旧格式 JSON（无 namespace）能被正确解码")
        void legacyJsonNoNamespaceDecodes() {
            // 旧格式 JSON：只有 id/damage/count，没有 namespace
            String legacyJson = "{\"id\":" + STONE_ID + ",\"damage\":0,\"count\":10}";

            Item decoded = ItemCodec.decodeJson(legacyJson);
            assertEquals(STONE_ID, decoded.getId());
            assertEquals(0, decoded.getDamage());
            assertEquals(10, decoded.getCount());
        }

        /**
         * 旧格式 JSON（无 namespace，带 NBT）能被正确解码。
         */
        @Test
        @DisplayName("旧格式 JSON（无 namespace + NBT）能被正确解码")
        void legacyJsonWithNbtDecodes() {
            String legacyJson = "{\"id\":" + STONE_ID
                    + ",\"damage\":0,\"count\":1"
                    + ",\"nbt\":{\"custom\":\"hello\",\"level\":99}}";

            Item decoded = ItemCodec.decodeJson(legacyJson);
            assertEquals(STONE_ID, decoded.getId());
            assertTrue(decoded.hasCompoundTag());
            assertEquals("hello", decoded.getNamedTag().getString("custom"));
            assertEquals(99, decoded.getNamedTag().getInt("level"));
        }
    }

    // ================================================================
    //  namespace 优先：解码时优先使用 namespace
    // ================================================================

    @Nested
    @DisplayName("namespace 优先解码")
    class NamespacePriority {

        /**
         * 手动构造包含 namespace 的 NBT 数据，验证解码时优先使用 namespace。
         * <p>
         * 注意：测试环境中 fromString() 返回 id=0 的通用物品，
         * 但关键是验证解码逻辑走了 namespace 路径（不抛异常、不使用数字 ID）。
         */
        @Test
        @DisplayName("Base64 含 namespace 字段时优先用 namespace 解码")
        void base64NamespacePriority() throws Exception {
            // 构造包含 namespace 的 NBT（新格式）
            CompoundTag tag = new CompoundTag()
                    .putString("Namespace", "minecraft:copper_sword")
                    .putInt("id", 99999)  // 故意写一个不存在的 ID
                    .putInt("Damage", 0)
                    .putByte("Count", (byte) 1);
            byte[] bytes = NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN, true);
            String base64 = Base64.getEncoder().encodeToString(bytes);

            // 解码应走 namespace 路径，不会因为 id=99999 而出错
            Item decoded = ItemCodec.decode(base64);
            // 在测试环境中 fromString 返回 id=0，但关键是没抛异常
            // 且没有使用 99999 这个 ID（说明走了 namespace 路径）
            assertNotEquals(99999, decoded.getId(),
                    "有 namespace 时不应使用数字 ID 99999");
        }

        /**
         * JSON 含 namespace 字段时优先使用 namespace 解码。
         */
        @Test
        @DisplayName("JSON 含 namespace 字段时优先用 namespace 解码")
        void jsonNamespacePriority() {
            String json = "{\"namespace\":\"minecraft:copper_sword\","
                    + "\"id\":99999,\"damage\":0,\"count\":1}";

            Item decoded = ItemCodec.decodeJson(json);
            assertNotEquals(99999, decoded.getId(),
                    "有 namespace 时不应使用数字 ID 99999");
        }

        /**
         * JSON 同时有 namespace 和 id 时，namespace 优先。
         * 验证即使 id 是一个有效的原版物品 ID，也不会被使用。
         */
        @Test
        @DisplayName("JSON namespace 优先于有效数字 ID")
        void jsonNamespaceOverridesValidId() {
            // namespace 指向 copper_sword，但 id 写的是 STONE(1)
            String json = "{\"namespace\":\"minecraft:copper_sword\","
                    + "\"id\":" + STONE_ID + ",\"damage\":0,\"count\":1}";

            Item decoded = ItemCodec.decodeJson(json);
            // 应该走 namespace 路径，而不是返回 STONE
            // 测试环境中 fromString 返回 id=0（不是 STONE_ID=1）
            assertNotEquals(STONE_ID, decoded.getId(),
                    "有 namespace 时不应回退到数字 ID " + STONE_ID);
        }
    }

    // ================================================================
    //  int 存储：大 ID 不再被截断
    // ================================================================

    @Nested
    @DisplayName("int 存储：大 ID 不截断")
    class IntStorage {

        /**
         * 手动构造新格式 NBT（用 putInt 存储 ID），验证大 ID 能被正确读取。
         */
        @Test
        @DisplayName("新格式 Base64（int ID = 40000）能被正确解码")
        void newFormatLargeIdDecodes() throws Exception {
            int largeId = 40000;  // 超出 short 范围
            CompoundTag tag = new CompoundTag()
                    .putInt("id", largeId)
                    .putInt("Damage", 0)
                    .putByte("Count", (byte) 1);
            byte[] bytes = NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN, true);
            String base64 = Base64.getEncoder().encodeToString(bytes);

            Item decoded = ItemCodec.decode(base64);
            assertEquals(largeId, decoded.getId(),
                    "int 格式存储的大 ID 应被完整读取，不截断");
        }

        /**
         * 验证旧格式（short）的大 ID 会被截断，新格式（int）不会。
         * 这是一个对比测试，展示修复前后的差异。
         */
        @Test
        @DisplayName("对比：short 格式截断 vs int 格式完整")
        void shortVsIntComparison() throws Exception {
            int largeId = 40000;

            // 旧格式（short）：会截断
            CompoundTag shortTag = new CompoundTag()
                    .putShort("id", (short) largeId)
                    .putInt("Damage", 0)
                    .putByte("Count", (byte) 1);
            byte[] shortBytes = NBTIO.write(shortTag, ByteOrder.LITTLE_ENDIAN, true);
            Item fromShort = ItemCodec.decode(Base64.getEncoder().encodeToString(shortBytes));

            // 新格式（int）：不截断
            CompoundTag intTag = new CompoundTag()
                    .putInt("id", largeId)
                    .putInt("Damage", 0)
                    .putByte("Count", (byte) 1);
            byte[] intBytes = NBTIO.write(intTag, ByteOrder.LITTLE_ENDIAN, true);
            Item fromInt = ItemCodec.decode(Base64.getEncoder().encodeToString(intBytes));

            assertNotEquals(largeId, fromShort.getId(),
                    "旧 short 格式应截断 40000 → " + fromShort.getId());
            assertEquals(largeId, fromInt.getId(),
                    "新 int 格式应完整保留 40000");
        }

        /**
         * JSON 格式天然使用 int，大 ID 不受影响。
         */
        @Test
        @DisplayName("JSON 格式大 ID 不截断")
        void jsonLargeIdNoTruncation() {
            int largeId = 50000;
            String json = "{\"id\":" + largeId + ",\"damage\":0,\"count\":1}";

            Item decoded = ItemCodec.decodeJson(json);
            assertEquals(largeId, decoded.getId(),
                    "JSON 格式应完整保留大 ID");
        }
    }

    // ================================================================
    //  编码验证：新格式输出包含 namespace 字段（当可用时）
    // ================================================================

    @Nested
    @DisplayName("编码输出验证")
    class EncodeOutput {

        /**
         * 验证 JSON 编码输出中，当 getNamespaceId() 返回非 null 时包含 namespace 字段。
         * 注意：测试环境中 getNamespaceId() 可能返回 null，此时 namespace 字段应被省略。
         */
        @Test
        @DisplayName("JSON 编码在 namespace 可用时包含该字段")
        void jsonIncludesNamespaceWhenAvailable() {
            Item item = Item.get(STONE_ID, 0, 1);
            String json = ItemCodec.encodeJson(item);

            // 无论 namespace 是否可用，JSON 都应是合法的
            assertNotNull(json);
            assertTrue(json.contains("\"id\":" + STONE_ID), "JSON 应包含 id: " + json);

            // 如果 getNamespaceId() 返回非 null，JSON 应包含 namespace 字段
            // 注意：测试环境中 RuntimeItems 未初始化，getNamespaceId() 可能抛出 NPE
            String namespace;
            try {
                namespace = item.getNamespaceId();
            } catch (Exception e) {
                namespace = null;
            }
            if (namespace != null && !namespace.isEmpty()) {
                assertTrue(json.contains("\"namespace\""), "namespace 可用时 JSON 应包含该字段: " + json);
            }
            // 如果 namespace 为 null（测试环境），namespace 字段应被省略（不强制要求）
        }

        /**
         * 验证 Base64 编码后的数据能被正确解码（往返完整性）。
         */
        @Test
        @DisplayName("Base64 编码后 NBT 包含 int 类型 ID（非 short）")
        void base64UsesIntNotShort() throws Exception {
            Item item = Item.get(STONE_ID, 0, 5);
            String base64 = ItemCodec.encode(item);
            assertFalse(base64.isEmpty());

            // 解码 NBT 检查类型
            byte[] bytes = Base64.getDecoder().decode(base64);
            CompoundTag tag = NBTIO.read(bytes, ByteOrder.LITTLE_ENDIAN, true);

            // 新格式应使用 IntTag 存储 id（而非 ShortTag）
            cn.nukkit.nbt.tag.Tag idTag = tag.get("id");
            assertNotNull(idTag, "NBT 应包含 id 字段");
            assertInstanceOf(cn.nukkit.nbt.tag.IntTag.class, idTag,
                    "新格式 id 应为 IntTag，实际为: " + idTag.getClass().getSimpleName());
        }
    }
}
