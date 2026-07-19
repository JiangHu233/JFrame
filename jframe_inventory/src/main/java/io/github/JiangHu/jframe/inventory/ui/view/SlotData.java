package io.github.JiangHu.jframe.inventory.ui.view;

import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;

/**
 * 格子数据（内部模型）。
 * <p>
 * 记录箱子中每个格子的渲染结果：外观、类型、拥有者组件。
 * 渲染阶段由组件通过 {@link RenderContext} 写入，事件阶段由 {@link InventoryView} 查询分发。
 *
 * @param appearance 物品外观（{@code null} 表示该格子由玩家自由管理，如 STORAGE 空槽）
 * @param type       格子类型
 * @param owner      拥有该格子的组件（事件回调目标）
 */
record SlotData(
        SlotAppearance appearance,
        SlotType type,
        InventoryComponent owner
) {

    /**
     * 创建一个空的格子数据（默认 LOCKED 类型，无拥有者）。
     *
     * @return 空格子数据
     */
    static SlotData empty() {
        return new SlotData(null, SlotType.LOCKED, null);
    }
}
