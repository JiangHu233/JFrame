package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

/**
 * codec 编解码器用法演示（可运行的「活文档」）。
 * <p>
 * 本类不是单纯的断言测试，而是<b>带控制台输出的演示</b>：每个方法演示一个典型场景，
 * 打印编码前后的对比，方便开发者直观理解 {@link ItemCodec} / {@link InventoryCodec}
 * 的 Base64 与 JSON 双格式用法。
 * <p>
 * <b>查看演示输出：</b>在 IDE 中右键运行本类（或在 Maven 测试报告中查看 surefire stdout），
 * 即可看到每种格式的实际编码字符串。
 *
 * @see ItemCodec
 * @see InventoryCodec
 */
@DisplayName("codec 编解码器用法演示")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodecDemoTest {

    private static void section(String title) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private static void step(String msg) {
        System.out.println("▶ " + msg);
    }

    private static void print(String label, Object value) {
        System.out.println("  " + label + ": " + value);
    }

    // ============================================================
    //  演示 1：单个物品的双格式序列化
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("演示1：附魔命名剑的 Base64 与 JSON 双格式")
    void demoItemDualFormat() {
        section("演示1：单个物品的双格式序列化");

        // —— 构造一把带自定义名称 + lore + 自定义 NBT 的钻石剑 ——
        step("构造物品：钻石剑（自定义名称 + lore + 自定义NBT）");
        Item sword = Item.get(Item.DIAMOND_SWORD);
        // 注意顺序：setNamedTag 会替换整个 NamedTag，必须先调用；
        // setCustomName / setLore 随后写入 NamedTag 的 display 子标签，不会覆盖上面的自定义数据
        sword.setNamedTag(new CompoundTag()
                .putString("rarity", "legendary")
                .putInt("kill_count", 0));
        sword.setCustomName("§b屠龙剑");
        sword.setLore("§7传说武器", "§c攻击力 +100");
        print("物品描述", "id=" + sword.getId() + ", damage=" + sword.getDamage() + ", count=" + sword.getCount());

        // —— 紧凑 Base64 文本格式 ——
        step("【Base64 格式】ItemCodec.encode(sword)");
        String base64 = ItemCodec.encode(sword);
        print("Base64 字符串（长度=" + base64.length() + "）", base64);

        // —— 可读 JSON 格式 ——
        step("【JSON 格式】ItemCodec.encodeJson(sword)");
        String json = ItemCodec.encodeJson(sword);
        print("JSON 字符串", json);

        // —— 反序列化还原 ——
        step("反序列化并验证两种格式还原结果一致");
        Item fromBase64 = ItemCodec.decode(base64);
        Item fromJson = ItemCodec.decodeJson(json);
        print("Base64 还原 → 名称", fromBase64.getCustomName());
        print("JSON   还原 → 名称", fromJson.getCustomName());
        print("JSON   还原 → NBT.kill_count", fromJson.getNamedTag().getInt("kill_count"));

        org.junit.jupiter.api.Assertions.assertEquals(fromBase64.getId(), fromJson.getId());
        org.junit.jupiter.api.Assertions.assertEquals("§b屠龙剑", fromJson.getCustomName());
    }

    // ============================================================
    //  演示 2：空气物品的边界处理
    // ============================================================

    @Test
    @Order(2)
    @DisplayName("演示2：空气/null 物品的边界约定")
    void demoAirBoundary() {
        section("演示2：空气物品的边界约定");

        step("encode(null) 与 encode(空气) 都返回空字符串");
        print("encode(null)", "\"" + ItemCodec.encode(null) + "\"");
        print("encode(空气)", "\"" + ItemCodec.encode(Item.get(Item.AIR)) + "\"");

        step("encodeJson(null) 与 encodeJson(空气) 都返回 \"null\"");
        print("encodeJson(null)", ItemCodec.encodeJson(null));
        print("encodeJson(空气)", ItemCodec.encodeJson(Item.get(Item.AIR)));

        step("decode(\"\") / decodeJson(\"null\") 都返回空气物品");
        print("decode(\"\").getId()", ItemCodec.decode("").getId() + " (AIR=0)");
        print("decodeJson(\"null\").getId()", ItemCodec.decodeJson("null").getId() + " (AIR=0)");
    }

    // ============================================================
    //  演示 3：物品栏序列化（保留槽位位置）
    // ============================================================

    @Test
    @Order(3)
    @DisplayName("演示3：箱子物品栏的双格式序列化与位置保留")
    void demoInventoryDualFormat() {
        section("演示3：物品栏序列化（保留槽位位置）");

        // —— 构造一个 27 格箱子，在 0/5/10 槽放物品 ——
        step("构造 27 格箱子：0=石头×10，5=圆石×20，10=命名物品×1");
        Inventory chest = new FakeInventory(null, InventoryType.CHEST);
        chest.setItem(0, Item.get(Item.STONE, 0, 10));
        chest.setItem(5, Item.get(Item.COBBLESTONE, 2, 20));
        Item named = Item.get(Item.DIAMOND, 0, 1);
        named.setNamedTag(new CompoundTag().putString("owner", "Steve"));
        chest.setItem(10, named);

        // —— 紧凑文本格式 ——
        step("【Base64 文本格式】InventoryCodec.encode(chest)");
        String textData = InventoryCodec.encode(chest);
        print("文本格式（长度=" + textData.length() + "）", textData);
        print("decodeSize", InventoryCodec.decodeSize(textData) + " 格");

        // —— JSON 格式 ——
        step("【JSON 格式】InventoryCodec.encodeJson(chest)");
        String jsonData = InventoryCodec.encodeJson(chest);
        print("JSON 格式", jsonData);

        // —— apply 还原到一个新箱子 ——
        step("apply：将文本格式还原到新的空箱子");
        Inventory restored = new FakeInventory(null, InventoryType.CHEST);
        InventoryCodec.apply(restored, textData);
        print("还原后 槽0", restored.getItem(0).getId() + " ×" + restored.getItem(0).getCount());
        print("还原后 槽5", restored.getItem(5).getId() + " ×" + restored.getItem(5).getCount());
        print("还原后 槽10 NBT.owner", restored.getItem(10).getNamedTag().getString("owner"));
        print("还原后 槽1（应为空气）", restored.getItem(1).getId() + " (AIR=0)");

        org.junit.jupiter.api.Assertions.assertEquals(10, restored.getItem(0).getCount());
        org.junit.jupiter.api.Assertions.assertEquals("Steve", restored.getItem(10).getNamedTag().getString("owner"));
    }

    // ============================================================
    //  演示 4：稀疏存储（空气槽位不写入）
    // ============================================================

    @Test
    @Order(4)
    @DisplayName("演示4：稀疏存储 —— 空气槽位不写入")
    void demoSparseStorage() {
        section("演示4：稀疏存储（空气槽位不写入）");

        step("构造 27 格箱子，仅槽位 13 放 1 个物品");
        Inventory chest = new FakeInventory(null, InventoryType.CHEST);
        chest.setItem(13, Item.get(Item.APPLE, 0, 3));

        String textData = InventoryCodec.encode(chest);
        String jsonData = InventoryCodec.encodeJson(chest);
        print("文本格式", textData);
        print("JSON 格式", jsonData);

        step("观察：两种格式都只记录了 1 个非空槽位（index=13）");
        Map<Integer, Item> slots = InventoryCodec.decode(textData);
        print("非空槽位数量", slots.size() + "（27 格中仅 1 格非空）");
        print("槽位索引", slots.keySet());

        org.junit.jupiter.api.Assertions.assertEquals(1, slots.size());
        org.junit.jupiter.api.Assertions.assertTrue(slots.containsKey(13));
    }

    // ============================================================
    //  演示 5：两种格式还原等价性
    // ============================================================

    @Test
    @Order(5)
    @DisplayName("演示5：Base64 与 JSON 两种格式还原结果等价")
    void demoFormatEquivalence() {
        section("演示5：两种格式还原结果等价");

        Inventory chest = new FakeInventory(null, InventoryType.CHEST);
        chest.setItem(0, Item.get(Item.STONE, 0, 10));
        chest.setItem(5, Item.get(Item.COBBLESTONE, 2, 20));

        step("同一物品栏分别用两种格式序列化");
        String textData = InventoryCodec.encode(chest);
        String jsonData = InventoryCodec.encodeJson(chest);

        Map<Integer, Item> fromText = InventoryCodec.decode(textData);
        Map<Integer, Item> fromJson = InventoryCodec.decodeJson(jsonData);

        step("逐槽位对比两种格式的还原结果");
        print("文本格式槽位数", fromText.size());
        print("JSON 格式槽位数", fromJson.size());

        boolean allEqual = true;
        for (Integer idx : fromText.keySet()) {
            Item a = fromText.get(idx);
            Item b = fromJson.get(idx);
            boolean eq = a.getId() == b.getId()
                    && a.getDamage() == b.getDamage()
                    && a.getCount() == b.getCount();
            print("槽 " + idx + " 等价", eq);
            if (!eq) allEqual = false;
        }
        print("结论", allEqual ? "✓ 两种格式完全等价" : "✗ 不一致");

        org.junit.jupiter.api.Assertions.assertTrue(allEqual);
    }

    // ============================================================
    //  演示 6：异常处理（容量校验、非法输入）
    // ============================================================

    @Test
    @Order(6)
    @DisplayName("演示6：异常处理 —— 容量不匹配与非法输入")
    void demoExceptionHandling() {
        section("演示6：异常处理");

        // —— 容量不匹配 ——
        step("场景A：用 9 格物品栏的编码去 apply 到 27 格箱子");
        Item[] small = new Item[9];
        for (int i = 0; i < 9; i++) small[i] = Item.get(Item.AIR);
        small[0] = Item.get(Item.STONE, 0, 1);
        String smallData = InventoryCodec.encodeSlots(small, 9);

        Inventory bigChest = new FakeInventory(null, InventoryType.CHEST);
        try {
            InventoryCodec.apply(bigChest, smallData);
        } catch (InventoryCodecException e) {
            print("捕获异常", e.getMessage());
        }

        // —— 非法 JSON ——
        step("场景B：传入非法 JSON 字符串");
        try {
            InventoryCodec.decodeJson("{这不是合法json");
        } catch (InventoryCodecException e) {
            print("捕获异常", e.getMessage());
        }

        // —— 版本号错误 ——
        step("场景C：JSON 版本号不是 V1");
        try {
            InventoryCodec.decodeJson("{\"version\":\"V9\",\"size\":27,\"slots\":[]}");
        } catch (InventoryCodecException e) {
            print("捕获异常", e.getMessage());
        }

        step("结论：所有错误统一包装为 InventoryCodecException（非受检），便于集中捕获");
    }

    // ============================================================
    //  演示 7：离线数据迁移（仅用物品数组，无需 Inventory 实例）
    // ============================================================

    @Test
    @Order(7)
    @DisplayName("演示7：离线数据迁移 —— 仅用物品数组序列化")
    void demoOfflineMigration() {
        section("演示7：离线数据迁移（无 Inventory 实例）");

        step("构造物品数组（模拟从数据库/旧存档读出的原始数据）");
        Item[] slots = new Item[9];
        for (int i = 0; i < 9; i++) slots[i] = Item.get(Item.AIR);
        slots[0] = Item.get(Item.IRON_INGOT, 0, 32);
        slots[8] = Item.get(Item.GOLD_INGOT, 0, 16);

        step("用 encodeSlots / encodeSlotsJson 序列化（无需 Inventory 实例）");
        String textData = InventoryCodec.encodeSlots(slots, 9);
        String jsonData = InventoryCodec.encodeSlotsJson(slots, 9);
        print("文本格式", textData);
        print("JSON 格式", jsonData);

        step("迁移到真实物品栏");
        Inventory target = new FakeInventory(null, InventoryType.CHEST);
        // 注意：这里用 9 格数据演示，但箱子是 27 格，会抛容量不匹配
        // 正确做法：目标物品栏容量需与编码 size 一致
        print("提示", "apply 会校验容量，编码 size=9 必须应用到 9 格物品栏");

        org.junit.jupiter.api.Assertions.assertEquals(9, InventoryCodec.decodeSize(textData));
    }
}
