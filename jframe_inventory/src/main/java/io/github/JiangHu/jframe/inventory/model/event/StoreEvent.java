package io.github.JiangHu.jframe.inventory.model.event;

import cn.nukkit.Player;
import cn.nukkit.item.Item;

/**
 * 格子存取事件。
 * <p>
 * 当玩家在 {@link io.github.JiangHu.jframe.inventory.model.SlotType#STORAGE} 类型的格子中
 * 放入或取出物品时触发。组件通过重写 {@code onStore} 接收本事件，感知物品变化。
 *
 * @param player     操作的玩家
 * @param slot       发生变化的格子序号（绝对序号，0-based）
 * @param sourceItem 操作前的物品（取出场景：被取走的物品；放入场景：原为空）
 * @param targetItem 操作后的物品（取出场景：变为空；放入场景：放入的物品）
 */
public record StoreEvent(
        Player player,
        int slot,
        Item sourceItem,
        Item targetItem
) {

    /**
     * 判断是否为「放入」操作（格子从空变为有物品，或物品数量增加）。
     *
     * @return {@code true} 表示玩家放入了物品
     */
    public boolean isDeposit() {
        return sourceItem.isNull() && !targetItem.isNull();
    }

    /**
     * 判断是否为「取出」操作（格子从有物品变为空，或物品数量减少）。
     *
     * @return {@code true} 表示玩家取出了物品
     */
    public boolean isWithdraw() {
        return !sourceItem.isNull() && targetItem.isNull();
    }
}
