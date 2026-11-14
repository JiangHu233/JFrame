package io.github.JiangHu.jframe.inventory.codec;

import cn.nukkit.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 铜装备探测测试 —— 确认 Nukkit MOT 中 Item 类的 namespace 相关 API 是否可用，
 * 以及铜装备的 ID / namespace 值，为 ItemCodec 修复提供依据。
 * <p>
 * 本测试以「打印信息」为主，断言为辅。即使某些方法不存在也不会导致编译失败
 * （通过反射调用），方便快速了解运行时环境。
 */
@DisplayName("铜装备 / namespace API 探测")
class CopperItemProbeTest {

    private static void section(String title) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private static void print(String label, Object value) {
        System.out.println("  " + label + ": " + value);
    }

    // ============================================================
    //  探测 1：反射检查 Item 类的 namespace 相关方法
    // ============================================================

    @Test
    @DisplayName("探测1：Item 类有哪些 namespace / fromString 相关方法")
    void probeItemMethods() {
        section("探测1：Item 类的 namespace 相关方法");

        String[] candidates = {
                "getNamespaceId", "getNamespace", "getNamespacedId",
                "fromString", "fromNamespace", "getVanillaName",
                "getId", "getDamage", "getCount",
                "hasCompoundTag", "getNamedTag"
        };

        for (String name : candidates) {
            boolean found = false;
            for (Method m : Item.class.getMethods()) {
                if (m.getName().equals(name)) {
                    print("✓ " + name, m.toGenericString());
                    found = true;
                    break;
                }
            }
            if (!found) {
                print("✗ " + name, "方法不存在");
            }
        }
    }

    // ============================================================
    //  探测 2：反射检查 Item 类中铜装备相关常量
    // ============================================================

    @Test
    @DisplayName("探测2：Item 类中铜装备相关常量")
    void probeCopperConstants() {
        section("探测2：Item 类中铜装备相关常量");

        String[] candidates = {
                "COPPER_SWORD", "COPPER_PICKAXE", "COPPER_AXE",
                "COPPER_SHOVEL", "COPPER_HOE", "COPPER_SHEARS",
                "COPPER_HELMET", "COPPER_CHESTPLATE",
                "COPPER_LEGGINGS", "COPPER_BOOTS",
                "COPPER_INGOT", "COPPER_BLOCK", "COPPER_ORE"
        };

        int foundCount = 0;
        for (String name : candidates) {
            try {
                Field f = Item.class.getField(name);
                int value = f.getInt(null);
                print("✓ " + name, value);
                foundCount++;
            } catch (NoSuchFieldException e) {
                print("✗ " + name, "常量不存在");
            } catch (Exception e) {
                print("✗ " + name, "访问异常: " + e.getMessage());
            }
        }
        print("找到铜装备常量数量", foundCount);
    }

    // ============================================================
    //  探测 3：尝试用 fromString 创建铜装备
    // ============================================================

    @Test
    @DisplayName("探测3：尝试用 fromString 创建铜装备")
    void probeFromStringCopper() {
        section("探测3：fromString 创建铜装备");

        String[] namespaces = {
                "minecraft:copper_sword",
                "minecraft:copper_pickaxe",
                "minecraft:copper_axe",
                "minecraft:copper_shovel",
                "minecraft:copper_hoe",
                "minecraft:copper_helmet",
                "minecraft:copper_chestplate",
                "minecraft:copper_leggings",
                "minecraft:copper_boots"
        };

        Method fromString = null;
        try {
            fromString = Item.class.getMethod("fromString", String.class);
            print("fromString 方法", "存在 ✓");
        } catch (NoSuchMethodException e) {
            print("fromString 方法", "不存在 ✗ —— 无法通过 namespace 创建物品");
            return;
        }

        for (String ns : namespaces) {
            try {
                Item item = (Item) fromString.invoke(null, ns);
                print(ns, "id=" + item.getId()
                        + ", damage=" + item.getDamage()
                        + ", count=" + item.getCount()
                        + ", class=" + item.getClass().getSimpleName());
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                print(ns, "失败: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }

    // ============================================================
    //  探测 4：已知物品的 namespace（验证 getNamespaceId 是否可用）
    // ============================================================

    @Test
    @DisplayName("探测4：已知物品的 namespace 值")
    void probeKnownItemNamespace() {
        section("探测4：已知物品的 namespace");

        Method getNamespaceId = null;
        try {
            getNamespaceId = Item.class.getMethod("getNamespaceId");
            print("getNamespaceId 方法", "存在 ✓");
        } catch (NoSuchMethodException e) {
            print("getNamespaceId 方法", "不存在 ✗");
            // 尝试其他可能的方法名
            for (String alt : new String[]{"getNamespace", "getNamespacedId", "getVanillaName"}) {
                try {
                    getNamespaceId = Item.class.getMethod(alt);
                    print("替代方法 " + alt, "存在 ✓");
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }

        int[][] knownItems = {
                {Item.STONE, 0, 1},
                {Item.DIAMOND_SWORD, 0, 1},
                {Item.IRON_INGOT, 0, 1},
                {Item.APPLE, 0, 1}
        };

        for (int[] spec : knownItems) {
            Item item = Item.get(spec[0], spec[1], spec[2]);
            print("Item.get(" + spec[0] + ")",
                    "id=" + item.getId()
                            + ", damage=" + item.getDamage()
                            + ", name=" + item.getName()
                            + ", class=" + item.getClass().getSimpleName());
            if (getNamespaceId != null) {
                try {
                    String ns = (String) getNamespaceId.invoke(item);
                    print("  → namespace", ns);
                } catch (Exception e) {
                    print("  → namespace", "调用失败: " + e.getMessage());
                }
            }
        }
    }

    // ============================================================
    //  探测 5：模拟大 ID 物品的 short 截断问题
    // ============================================================

    @Test
    @DisplayName("探测5：模拟大 ID 物品的 short 截断")
    void probeShortTruncation() {
        section("探测5：short 截断模拟");

        int[] testIds = {1, 276, 32000, 32767, 32768, 40000, 50000, 65535};

        for (int id : testIds) {
            short truncated = (short) id;
            int restored = truncated;           // getShort 返回的值
            int unsigned = restored & 0xFFFF;   // 如果用 & 0xFFFF 还原

            print("id=" + id,
                    "→ (short)=" + truncated
                            + ", getShort=" + restored
                            + ", &0xFFFF=" + unsigned
                            + (id != restored && id != unsigned ? " ❌ 截断!" : " ✓"));
        }

        System.out.println();
        System.out.println("  结论：如果铜装备 ID > 32767，putShort/getShort 会截断。");
        System.out.println("  即使 Item.get() 内部用 id & 0xFFFF，负数 ID 也可能导致查找失败。");
    }

    // ============================================================
    //  探测 6：尝试创建铜装备并测试编解码往返
    // ============================================================

    @Test
    @DisplayName("探测6：铜装备编解码往返（如果可创建）")
    void probeCopperCodecRoundTrip() {
        section("探测6：铜装备编解码往返");

        // 尝试通过常量创建铜装备
        Item copperItem = tryGetCopperItem();
        if (copperItem == null) {
            print("结果", "无法创建铜装备实例（常量和 fromString 均不可用），跳过往返测试");
            return;
        }

        print("铜装备原始信息",
                "id=" + copperItem.getId()
                        + ", damage=" + copperItem.getDamage()
                        + ", count=" + copperItem.getCount()
                        + ", class=" + copperItem.getClass().getSimpleName());

        // 尝试获取 namespace
        try {
            Method getNs = Item.class.getMethod("getNamespaceId");
            String ns = (String) getNs.invoke(copperItem);
            print("铜装备 namespace", ns);
        } catch (Exception e) {
            print("铜装备 namespace", "无法获取: " + e.getMessage());
        }

        // Base64 编解码往返
        try {
            String encoded = ItemCodec.encode(copperItem);
            print("Base64 编码", encoded.isEmpty() ? "(空 — 被当作空气!)" : encoded);

            Item decoded = ItemCodec.decode(encoded);
            print("Base64 解码",
                    "id=" + decoded.getId()
                            + ", damage=" + decoded.getDamage()
                            + ", count=" + decoded.getCount()
                            + ", class=" + decoded.getClass().getSimpleName());

            boolean idMatch = copperItem.getId() == decoded.getId();
            print("ID 匹配", idMatch ? "✓ 一致" : "❌ 不一致！原始=" + copperItem.getId() + " 解码=" + decoded.getId());
        } catch (Exception e) {
            print("Base64 往返异常", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // JSON 编解码往返
        try {
            String json = ItemCodec.encodeJson(copperItem);
            print("JSON 编码", json);

            Item decoded = ItemCodec.decodeJson(json);
            print("JSON 解码",
                    "id=" + decoded.getId()
                            + ", damage=" + decoded.getDamage()
                            + ", count=" + decoded.getCount()
                            + ", class=" + decoded.getClass().getSimpleName());

            boolean idMatch = copperItem.getId() == decoded.getId();
            print("ID 匹配", idMatch ? "✓ 一致" : "❌ 不一致！原始=" + copperItem.getId() + " 解码=" + decoded.getId());
        } catch (Exception e) {
            print("JSON 往返异常", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 尝试通过各种方式获取铜装备实例。
     *
     * @return 铜装备实例，或 null（如果无法创建）
     */
    private Item tryGetCopperItem() {
        // 方式1：通过常量
        String[] constNames = {"COPPER_SWORD", "COPPER_PICKAXE", "COPPER_AXE"};
        for (String name : constNames) {
            try {
                Field f = Item.class.getField(name);
                int id = f.getInt(null);
                Item item = Item.get(id);
                print("通过常量 " + name + " 创建", "成功，id=" + item.getId());
                return item;
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                print("通过常量 " + name + " 创建", "失败: " + e.getMessage());
            }
        }

        // 方式2：通过 fromString
        try {
            Method fromString = Item.class.getMethod("fromString", String.class);
            String[] namespaces = {"minecraft:copper_sword", "minecraft:copper_pickaxe", "minecraft:copper_axe"};
            for (String ns : namespaces) {
                try {
                    Item item = (Item) fromString.invoke(null, ns);
                    if (item != null && item.getId() != Item.AIR) {
                        print("通过 fromString(" + ns + ") 创建", "成功，id=" + item.getId());
                        return item;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (NoSuchMethodException ignored) {
        }

        return null;
    }
}
