package io.github.JiangHu.jframe.inventory.ui.manager;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.inventory.InventoryCloseEvent;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.transaction.action.InventoryAction;
import cn.nukkit.inventory.transaction.action.SlotChangeAction;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.inventory.ui.InventoryAPI;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;
import io.github.JiangHu.jframe.inventory.ui.view.VirtualInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 箱子界面管理器。
 * <p>
 * 负责：
 * <ul>
 *   <li>每玩家视图管理（{@code Map<Player, InventoryView>}）</li>
 *   <li>监听 Nukkit 库存事件，路由到对应的 {@link InventoryView}</li>
 *   <li>根据 {@link SlotType} 决定取消/放行交易</li>
 * </ul>
 *
 * <h3>交易拦截规则</h3>
 * <table border="1">
 * <tr><th>格子类型</th><th>行为</th></tr>
 * <tr><td>{@link SlotType#BUTTON}</td><td>触发点击回调，取消交易（禁止拿走物品）</td></tr>
 * <tr><td>{@link SlotType#DISPLAY}</td><td>取消交易（仅展示，禁止操作）</td></tr>
 * <tr><td>{@link SlotType#LOCKED}</td><td>取消交易（默认锁定，禁止操作）</td></tr>
 * <tr><td>{@link SlotType#STORAGE}</td><td>允许交易，触发存取回调</td></tr>
 * </table>
 *
 * @see InventoryView
 * @see SlotType
 */
public class InventoryManager implements Listener {

    /** 每玩家当前打开的视图 */
    private final Map<Player, InventoryView> views = new ConcurrentHashMap<>();

    /** 插件实例（由 InventoryAPI 注入，用于向 Nukkit 注册事件监听器） */
    private Plugin plugin;

    /**
     * 绑定插件实例（由 {@link InventoryAPI} 调用）。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 打开视图。
     * <p>
     * 如果玩家已有打开的视图，会先关闭旧视图。
     *
     * @param player 玩家
     * @param view   视图
     */
    public void openView(Player player, InventoryView view) {
        // 如果已有打开的视图，先关闭
        InventoryView current = views.remove(player);
        if (current != null) {
            current.close();
        }

        // 注入插件实例（保留兼容，子类可能需要）
        view.bindPlugin(plugin);

        // 打开新视图
        view.open(player);
        views.put(player, view);
    }

    /**
     * 关闭玩家的视图。
     *
     * @param player 玩家
     */
    public void closeView(Player player) {
        InventoryView view = views.remove(player);
        if (view != null) {
            view.close();
        }
    }

    /**
     * 获取玩家当前打开的视图。
     *
     * @param player 玩家
     * @return 视图，没有则返回 null
     */
    public InventoryView getView(Player player) {
        return views.get(player);
    }

    /**
     * 关闭所有视图（插件禁用时调用）。
     */
    public void closeAll() {
        for (Map.Entry<Player, InventoryView> entry : views.entrySet()) {
            entry.getValue().close();
        }
        views.clear();
    }

    // -------------------- Nukkit 事件处理 --------------------

    /**
     * 处理库存交易事件。
     * <p>
     * 遍历交易操作，根据 {@link SlotType} 决定取消/放行：
     * <ul>
     *   <li>BUTTON：触发 {@link InventoryView#handleClick}，取消交易</li>
     *   <li>DISPLAY/LOCKED：取消交易</li>
     *   <li>STORAGE：允许交易，触发 {@link InventoryView#handleStore}</li>
     * </ul>
     * <p>
     * 如果交易中混合了 STORAGE 和非 STORAGE 操作（如拖拽），
     * 整个交易会被取消（因为无法部分执行）。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransaction(InventoryTransactionEvent event) {
        Player player = event.getTransaction().getSource();
        InventoryView view = views.get(player);
        if (view == null) return;

        boolean hasNonStorage = false;
        List<SlotChangeAction> buttonActions = new ArrayList<>();
        List<SlotChangeAction> storageActions = new ArrayList<>();

        for (InventoryAction action : event.getTransaction().getActionList()) {
            if (!(action instanceof SlotChangeAction)) continue;
            SlotChangeAction slotAction = (SlotChangeAction) action;

            Inventory inv = slotAction.getInventory();
            if (!(inv instanceof VirtualInventory)) continue;

            VirtualInventory vinv = (VirtualInventory) inv;
            if (vinv.view() != view) continue;

            int slot = slotAction.getSlot();
            SlotType type = view.slotType(slot);

            switch (type) {
                case BUTTON:
                    buttonActions.add(slotAction);
                    hasNonStorage = true;
                    break;
                case DISPLAY:
                case LOCKED:
                    hasNonStorage = true;
                    break;
                case STORAGE:
                    storageActions.add(slotAction);
                    break;
            }
        }

        // 如果有非 STORAGE 操作，取消整个交易
        if (hasNonStorage) {
            event.setCancelled(true);
        }

        // 触发 BUTTON 点击回调
        for (SlotChangeAction slotAction : buttonActions) {
            view.handleClick(slotAction.getSlot(), player, slotAction.getSourceItem());
        }

        // 如果交易没有被取消（全是 STORAGE 操作），触发存取回调
        if (!hasNonStorage) {
            for (SlotChangeAction slotAction : storageActions) {
                view.handleStore(slotAction.getSlot(), player,
                        slotAction.getSourceItem(), slotAction.getTargetItem());
            }
        }
    }

    /**
     * 处理库存关闭事件。
     * <p>
     * 玩家主动关闭箱子时触发，清理视图资源。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        InventoryView view = views.get(event.getPlayer());
        if (view == null) return;

        // 窗口尚未注册（addWindow 未完成或延迟期间），跳过清理
        // 防止打开过程中 InventoryCloseEvent 误触发
        if (!view.isWindowRegistered()) return;

        Inventory inv = event.getInventory();
        if (!(inv instanceof VirtualInventory)) return;

        VirtualInventory vinv = (VirtualInventory) inv;
        if (vinv.view() != view) return;

        // 清理视图（不调用 removeWindow，因为窗口已经被玩家关闭）
        view.cleanup();
        views.remove(event.getPlayer());
    }
}
