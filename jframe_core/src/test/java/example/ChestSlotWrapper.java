package example;

import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.transaction.action.InventoryAction;
import cn.nukkit.inventory.transaction.action.SlotChangeAction;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import io.github.JiangHu.jframe.core.event.routing.ObjectEventRouter;

/**
 * 示例 5：箱子第 N 格 Wrapper（全局提取器 + 复杂 filter）。
 * <p>
 * 场景：监听一个箱子中<b>第 N 个格子</b>，当钻石被放入该格子时触发。
 * <p>
 * <b>路由方式</b>：使用<b>全局提取器</b>（不需要 RoutingSpec）。
 * {@code InventoryTransactionEvent} 继承自 {@code InventoryEvent}，
 * 全局提取器自动调用 {@code getInventory()} 提取箱子 Inventory 作为 Key。
 * <p>
 * <b>精细过滤</b>：Key 只能区分"哪个箱子"，无法区分"哪个格子"。
 * 格子级别的过滤通过 {@code filter} 方法实现——在 Java 方法中遍历交易动作，
 * 检查是否有钻石被放入目标格子。
 *
 * <h3>注册方式</h3>
 * <pre>{@code
 * // chestInventory 是箱子的 Inventory 对象
 * ChestSlotWrapper slot3 = ChestSlotWrapper.create(chestInventory, 3, router);
 *
 * // 内部调用：router.register(slot3, chestInventory)
 * //                              ^^^^^^^^^^^^^^  Key = Inventory（全局提取器自动提取）
 * }</pre>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 玩家在箱子中操作 → InventoryTransactionEvent 触发
 *     ↓
 * ObjectEventRouter.dispatch()
 *     ↓
 * ② 全局提取器：event.getInventory() → 得到箱子 Inventory
 *     ↓
 * 比对 Key：操作的箱子 == 注册时的 chestInventory？
 *     ↓ 匹配
 * filter 检查：遍历交易动作，是否有钻石放入第 3 格？
 *     ↓ 通过
 * 调用 onDiamondPlaced() → 触发逻辑
 * </pre>
 *
 * <h3>对比：何时需要 RoutingSpec？</h3>
 * <ul>
 *   <li>本例：按箱子路由（全局提取器已支持），格子过滤在 filter 中做 → <b>不需要</b> RoutingSpec</li>
 *   <li>如果需要按"箱子+格子"组合路由（如多个 wrapper 监听同一箱子的不同格子，
 *       且不想每个格子都收到事件再 filter），则可用 RoutingSpec 提取复合 Key</li>
 * </ul>
 *
 * @see ObjectEventRouter#register(Object, Object)
 */
public class ChestSlotWrapper {

    private final Inventory inventory;
    private final int slot;
    private final ObjectEventRouter router;

    public ChestSlotWrapper(Inventory inventory, int slot, ObjectEventRouter router) {
        this.inventory = inventory;
        this.slot = slot;
        this.router = router;
    }

    /**
     * 创建并注册箱子格子 Wrapper。
     * <p>
     * 使用全局提取器（InventoryEvent → getInventory()），Key = 箱子 Inventory 对象。
     * 格子级别的精细过滤在 {@link #isDiamondPlacedInSlot} 中实现。
     *
     * @param inventory 箱子的 Inventory 对象
     * @param slot      目标格子序号（0-based）
     * @param router    事件路由器
     * @return 已注册的 Wrapper 实例
     */
    public static ChestSlotWrapper create(Inventory inventory, int slot, ObjectEventRouter router) {
        ChestSlotWrapper wrapper = new ChestSlotWrapper(inventory, slot, router);
        // Key = Inventory 对象，框架从 InventoryEvent 中提取 getInventory() 并匹配
        router.register(wrapper, inventory);
        return wrapper;
    }

    /**
     * 注销 Wrapper，停止监听。
     */
    public void destroy() {
        router.unregister(this);
    }

    // ==================== 事件处理方法 ====================

    /**
     * 当钻石被放入目标格子时触发。
     * <p>
     * 由于 Key = Inventory，此方法会收到该箱子的<b>所有</b>交易事件。
     * filter 方法 {@link #isDiamondPlacedInSlot} 负责精确过滤：
     * 只有"钻石被放入第 N 格"时才放行。
     */
    @NukkitEvent(filter = "isDiamondPlacedInSlot")
    public void onDiamondPlaced(InventoryTransactionEvent event) {
        System.out.println("[箱子监听] 钻石被放入了第 " + slot + " 格！");
        // 示例：统计钻石数量、触发成就、播放音效等
        countDiamond();
    }

    /**
     * 筛选方法：判断交易中是否有钻石被放入目标格子。
     * <p>
     * 遍历交易的所有动作（InventoryAction），对于格子变更动作（SlotChangeAction）：
     * <ol>
     *   <li>检查操作的箱子是否是我们的箱子</li>
     *   <li>检查格子序号是否是目标格子</li>
     *   <li>检查目标物品（放入后的物品）是否是钻石</li>
     * </ol>
     *
     * @param event 库存交易事件
     * @return 如果钻石被放入目标格子，返回 true
     */
    private boolean isDiamondPlacedInSlot(InventoryTransactionEvent event) {
        for (InventoryAction action : event.getTransaction().getActions()) {
            // 只关心格子变更动作
            if (!(action instanceof SlotChangeAction slotAction)) {
                continue;
            }
            // 检查是否是我们的箱子的目标格子
            if (slotAction.getInventory() != inventory || slotAction.getSlot() != slot) {
                continue;
            }
            // 检查放入后的物品是否是钻石
            Item targetItem = slotAction.getTargetItem();
            if (targetItem != null && targetItem.getId() == Item.DIAMOND) {
                return true;
            }
        }
        return false;
    }

    // ==================== 业务逻辑（示意） ====================

    private int diamondCount = 0;

    private void countDiamond() {
        diamondCount++;
        System.out.println("[箱子监听] 第 " + slot + " 格累计收到 " + diamondCount + " 次钻石");
    }
}
