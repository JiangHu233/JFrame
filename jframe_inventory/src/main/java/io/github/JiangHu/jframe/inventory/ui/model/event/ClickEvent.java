package io.github.JiangHu.jframe.inventory.ui.model.event;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;

/**
 * 格子点击事件。
 * <p>
 * 当玩家点击 {@link SlotType#BUTTON} 类型的格子时触发。
 * 组件通过重写 {@code onClick} 接收本事件，执行业务逻辑。
 *
 * @param player  点击的玩家
 * @param slot    被点击的格子序号（绝对序号，0-based）
 * @param item    格子上的当前物品（外观快照，修改不影响实际显示）
 */
public record ClickEvent(
        Player player,
        int slot,
        Item item
) {
}
