package io.github.JiangHu.jframe.inventory.ui.model.event;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;

/**
 * 格子存取前置事件（可取消）。
 *
 * <p>当玩家对 {@link SlotType#STORAGE} 格子的交易动作<b>即将提交</b>时触发。
 * 组件在此时机可以否决本次存取：取消后整笔交易终止（客户端物品弹回），
 * 且不触发 {@link StoreEvent} 通知。
 *
 * <p>接入方式（存取守卫）：
 * <ul>
 *   <li>重写组件的 {@code onBeforeStore} 钩子（基类 {@code InventoryComponent} 提供，默认放行）</li>
 *   <li>{@code StorageBox} 提供 {@code filter} / {@code withdrawFilter} / {@code beforeStore} 便捷注册</li>
 * </ul>
 *
 * <h3>动作形态判定与方向判定</h3>
 * <table border="1">
 * <tr><th>方法</th><th>含义</th></tr>
 * <tr><td>{@link #isDeposit()}</td><td>整格放入（源为空 → 目标非空）</td></tr>
 * <tr><td>{@link #isWithdraw()}</td><td>整格取出（源非空 → 目标为空）</td></tr>
 * <tr><td>{@link #isReplace()}</td><td>交换（两者皆非空且为不同物品）</td></tr>
 * <tr><td>{@link #isEntering()}</td><td>方向：有物品<b>进入</b>本格（放入/换入/并入堆叠导致数量增加）</td></tr>
 * <tr><td>{@link #isLeaving()}</td><td>方向：有物品<b>离开</b>本格（取出/换出/右键拿一半导致数量减少）</td></tr>
 * </table>
 *
 * <p>方向判定供放入/取出过滤器使用：交换动作 {@code isEntering()} 与 {@code isLeaving()}
 * 同时为 true（换入与换出双向发生）；并入堆叠只有 {@code isEntering()}，右键拿一半只有
 * {@code isLeaving()}。同 ID 物品的 NBT 差异不参与形态判定（属极端边缘场景，
 * 如需精确处理请在 {@code beforeStore} 回调中自行判断）。
 *
 * <p>与 {@link StoreEvent} 的关系：本事件在交易<b>提交前</b>触发（可否决）；
 * {@code StoreEvent} 在交易<b>提交后</b>触发（仅通知，不可取消）。
 *
 * @see StoreEvent
 * @see io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent
 */
public class BeforeStoreEvent {

    /** 操作的玩家 */
    private final Player player;
    /** 发生变化的格子序号（绝对序号，0-based） */
    private final int slot;
    /** 操作前的物品 */
    private final Item sourceItem;
    /** 操作后的物品 */
    private final Item targetItem;

    /** 是否被否决 */
    private boolean cancelled = false;
    /** 否决时发送给玩家的提示消息（可选） */
    private String denyMessage = null;

    /**
     * 创建存取前置事件。
     *
     * <p>{@code sourceItem} / {@code targetItem} 为 {@code null} 时按空气物品处理。
     *
     * @param player     操作的玩家
     * @param slot       发生变化的格子序号（绝对序号，0-based）
     * @param sourceItem 操作前的物品
     * @param targetItem 操作后的物品
     */
    public BeforeStoreEvent(Player player, int slot, Item sourceItem, Item targetItem) {
        this.player = player;
        this.slot = slot;
        this.sourceItem = sourceItem != null ? sourceItem : Item.get(Item.AIR);
        this.targetItem = targetItem != null ? targetItem : Item.get(Item.AIR);
    }

    /**
     * 否决本次存取（无提示消息）。
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 否决本次存取，并向玩家发送提示消息。
     *
     * @param message 提示消息（{@code null} 或空串视为无消息）
     */
    public void cancel(String message) {
        this.cancelled = true;
        if (message != null && !message.isEmpty()) {
            this.denyMessage = message;
        }
    }

    /**
     * 本次存取是否被否决。
     *
     * @return {@code true} 表示已否决
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 获取否决提示消息。
     *
     * @return 提示消息，未设置时为 {@code null}
     */
    public String denyMessage() {
        return denyMessage;
    }

    /**
     * 判断是否为「整格放入」（格子从空变为有物品）。
     *
     * @return {@code true} 表示放入了物品
     */
    public boolean isDeposit() {
        return sourceItem.isNull() && !targetItem.isNull();
    }

    /**
     * 判断是否为「整格取出」（格子从有物品变为空）。
     *
     * @return {@code true} 表示取出了物品
     */
    public boolean isWithdraw() {
        return !sourceItem.isNull() && targetItem.isNull();
    }

    /**
     * 判断是否为「交换」（放入与取出同时发生，两者皆非空且为不同物品）。
     *
     * @return {@code true} 表示交换
     */
    public boolean isReplace() {
        return !sourceItem.isNull() && !targetItem.isNull()
                && !isSameStack(sourceItem, targetItem);
    }

    /**
     * 是否有物品进入本格（放入 / 换入 / 并入堆叠导致数量增加）。
     * <p>交换动作本方法与 {@link #isLeaving()} 同时为 {@code true}。
     *
     * @return {@code true} 表示有物品进入
     */
    public boolean isEntering() {
        if (targetItem.isNull()) {
            return false;
        }
        if (sourceItem.isNull()) {
            return true;
        }
        if (isSameStack(sourceItem, targetItem)) {
            return targetItem.getCount() > sourceItem.getCount();
        }
        return true;
    }

    /**
     * 是否有物品离开本格（取出 / 换出 / 右键拿一半导致数量减少）。
     * <p>交换动作本方法与 {@link #isEntering()} 同时为 {@code true}。
     *
     * @return {@code true} 表示有物品离开
     */
    public boolean isLeaving() {
        if (sourceItem.isNull()) {
            return false;
        }
        if (targetItem.isNull()) {
            return true;
        }
        if (isSameStack(sourceItem, targetItem)) {
            return targetItem.getCount() < sourceItem.getCount();
        }
        return true;
    }

    /** 操作的玩家 */
    public Player player() {
        return player;
    }

    /** 发生变化的格子序号（绝对序号，0-based） */
    public int slot() {
        return slot;
    }

    /** 操作前的物品 */
    public Item sourceItem() {
        return sourceItem;
    }

    /** 操作后的物品 */
    public Item targetItem() {
        return targetItem;
    }

    /**
     * 判断两个物品是否为同类堆叠（同 ID 且同 damage，忽略数量与 NBT 差异）。
     */
    private static boolean isSameStack(Item a, Item b) {
        return a.getId() == b.getId() && a.getDamage() == b.getDamage();
    }
}
