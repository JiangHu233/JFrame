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

    /** 每玩家最后一次 openView 的时间戳（毫秒），用于重复打开防抖 */
    private final Map<Player, Long> lastOpenTime = new ConcurrentHashMap<>();

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
     * 打开视图（鲁棒模式，带重复打开防抖）。
     * <p>
     * 采用<b>时间窗口防抖</b>策略，兼顾两种场景：
     * <ul>
     *   <li><b>网易版重复右键</b>：玩家在防抖窗口内（默认约 1 秒）重复调用 openView，
     *       框架<b>忽略</b>后续请求，避免"先关旧视图再开新视图"导致界面秒关。</li>
     *   <li><b>丢包恢复</b>：如果上一次打开的 {@code ContainerOpenPacket} 因丢包未送达客户端，
     *       玩家本地看不到界面。玩家在防抖窗口<b>之外</b>再次右键时，框架会关闭旧视图
     *       并重新打开，避免界面卡死。</li>
     * </ul>
     * <p>
     * 防抖窗口大小 = {@link InventoryView#OPEN_DELAY_TICKS} × 50ms + 500ms 缓冲，
     * 覆盖视图从"开始打开"到"完全弹出"的整个过程。
     * <p>
     * <b>界面切换场景</b>（如点击按钮从界面 A 切到界面 B）请使用 {@link #forceOpenView}，
     * 它不检查防抖，直接关闭当前视图后打开新视图。
     *
     * @param player 玩家
     * @param view   视图
     */
    public void openView(Player player, InventoryView view) {
        long now = System.currentTimeMillis();
        InventoryView current = views.get(player);

        // 重复打开防抖：玩家有活跃视图 + 防抖窗口内 → 忽略（吸收网易版重复右键）
        if (current != null && !current.isClosed()) {
            Long last = lastOpenTime.get(player);
            if (last != null && now - last < openDebounceMs()) {
                return;
            }
            // 超过防抖窗口：可能是丢包导致界面未弹出，关闭旧视图后重开
        }

        // 关闭旧视图（如果有活跃的）
        views.remove(player);
        if (current != null && !current.isClosed()) {
            current.close();
        }

        // 记录打开时间，打开新视图
        lastOpenTime.put(player, now);
        view.bindPlugin(plugin);
        view.open(player);
        views.put(player, view);
    }

    /**
     * 计算重复打开的防抖窗口（毫秒）。
     * <p>
     * = {@link InventoryView#OPEN_DELAY_TICKS} × 50ms（打开延迟）+ 500ms 缓冲。
     * 动态读取 {@code OPEN_DELAY_TICKS}，适应运行时调整。
     *
     * @return 防抖窗口毫秒数
     */
    private long openDebounceMs() {
        return InventoryView.OPEN_DELAY_TICKS * 50L + 500L;
    }

    /**
     * 强制打开视图（关闭当前视图后打开新视图）。
     * <p>
     * 用于<b>界面切换</b>场景：无论玩家当前是否有活跃视图，都会先关闭旧的再打开新的。
     * 不检查防抖窗口，适合界面内按钮跳转。
     * <p>
     * 普通打开请使用 {@link #openView}，它带防抖保护，能吸收网易版重复右键。
     *
     * @param player 玩家
     * @param view   视图
     */
    public void forceOpenView(Player player, InventoryView view) {
        // 强制关闭当前视图
        InventoryView current = views.remove(player);
        if (current != null && !current.isClosed()) {
            current.close();
        }

        // 打开新视图
        lastOpenTime.put(player, System.currentTimeMillis());
        view.bindPlugin(plugin);
        view.open(player);
        views.put(player, view);
    }

    /**
     * 关闭玩家的视图。
     *
     * @param player 玩家
     */
    public void closeView(Player player) {
        lastOpenTime.remove(player);
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
        lastOpenTime.clear();
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
        lastOpenTime.remove(event.getPlayer());
    }
}
