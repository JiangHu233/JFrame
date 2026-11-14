package io.github.JiangHu.jframe.inventory.ui.model;

import cn.nukkit.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SlotAppearance namespace 支持单测 — 1.21+ 新物品（仅有 namespace 无数字 ID）
 * 的 UI 渲染路径。
 * <p>
 * 注意：单元测试环境 RuntimeItems 未初始化，{@code fromString} 对未知串返回
 * id=0 的通用物品——测试断言的是<b>路径选择</b>（namespace 优先、数字 ID 回退），
 * 非完整物品语义（与 ItemCodecNamespaceTest 同款取舍）。
 */
@DisplayName("SlotAppearance namespace 支持")
class SlotAppearanceNamespaceTest {

    @Test
    @DisplayName("namespace 设置时 toItem 走 fromString 路径（不使用数字 ID）")
    void namespaceTakesPrecedenceOverNumericId() {
        SlotAppearance appearance = SlotAppearance.builder()
                .namespace("minecraft:copper_chestplate")
                .type(999) // 数字 ID 故意设为无效值
                .name("铜胸甲")
                .count(2)
                .build();

        Item item = appearance.toItem();
        assertNotNull(item, "namespace 路径应构造出物品");
        // 走了 fromString 路径：测试环境未知串 → id=0 通用物品；若错误走了数字路径会是 999
        assertEquals(0, item.getId(), "namespace 优先——不应使用数字 ID 999");
        assertEquals(2, item.getCount(), "count 应传递");
        assertEquals("铜胸甲", item.getCustomName(), "name 应传递");
    }

    @Test
    @DisplayName("未设置 namespace 时回退数字 ID 路径（向后兼容）")
    void numericIdFallbackWhenNoNamespace() {
        SlotAppearance appearance = SlotAppearance.builder()
                .type(Item.DIRT)
                .count(3)
                .build();

        Item item = appearance.toItem();
        assertEquals(Item.DIRT, item.getId(), "无 namespace 时走数字 ID");
        assertEquals(3, item.getCount());
    }

    @Test
    @DisplayName("空串 namespace 等同未设置（回落数字路径）")
    void blankNamespaceTreatedAsUnset() {
        SlotAppearance appearance = SlotAppearance.builder()
                .namespace("")
                .type(Item.STONE)
                .build();
        assertNull(appearance.namespace(), "空串应归一化为 null");
        assertEquals(Item.STONE, appearance.toItem().getId());
    }

    @Test
    @DisplayName("fromItem：无 RuntimeItems 环境下 namespace 为 null 时保持数字 ID（降级安全）")
    void fromItemDegradesSafelyWithoutRuntimeItems() {
        Item stone = Item.get(Item.STONE, 0, 1);
        SlotAppearance appearance = SlotAppearance.fromItem(stone);
        assertNotNull(appearance);
        // 测试环境 getNamespaceId 不可用 → namespace null → 数字路径仍正确
        assertEquals(Item.STONE, appearance.itemId());
        assertEquals(Item.STONE, appearance.toItem().getId(), "降级路径往返一致");
    }
}
