package io.github.JiangHu.jframe.inventory.ui;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.inventory.ui.manager.InventoryManager;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;

/**
 * 箱子界面 API（公开门面）。
 * <p>
 * 开发者通过本类使用箱子界面框架，无需直接接触 {@link InventoryManager}。
 * 本类实现 {@link PluginAware}，在模块导入时自动接收插件实例，
 * 并向 Nukkit 注册事件监听器。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired
 * private InventoryAPI inventoryAPI;
 *
 * public void openShop(Player player) {
 *     inventoryAPI.openView(player, new ShopView());
 * }
 * }</pre>
 *
 * @see InventoryView
 * @see InventoryManager
 */
public class InventoryAPI implements PluginAware {

    private final InventoryManager manager;

    /**
     * 创建 API 实例。
     *
     * @param manager 管理器
     */
    public InventoryAPI(InventoryManager manager) {
        this.manager = manager;
    }

    @Override
    public void bindPlugin(Plugin plugin) {
        // 注入插件实例，用于向 Nukkit 注册事件监听器
        manager.bindPlugin(plugin);
        // 注册事件监听器
        Server.getInstance().getPluginManager().registerEvents(manager, plugin);
    }

    /**
     * 打开视图（鲁棒模式，带重复打开保护）。
     * <p>
     * 如果玩家已有活跃视图（正在打开或已打开），<b>忽略</b>本次请求，避免重复打开。
     * 这能有效吸收网易版客户端右键时短时间内重复发送的事件，防止界面秒关。
     * <p>
     * <b>界面切换</b>请使用 {@link #forceOpenView}。
     *
     * @param player 玩家
     * @param view   视图
     */
    public void openView(Player player, InventoryView view) {
        manager.openView(player, view);
    }

    /**
     * 强制打开视图（关闭当前视图后打开新视图）。
     * <p>
     * 用于<b>界面切换</b>场景：无论玩家当前是否有活跃视图，都会先关闭旧的再打开新的。
     *
     * @param player 玩家
     * @param view   视图
     */
    public void forceOpenView(Player player, InventoryView view) {
        manager.forceOpenView(player, view);
    }

    /**
     * 关闭玩家的视图。
     *
     * @param player 玩家
     */
    public void closeView(Player player) {
        manager.closeView(player);
    }

    /**
     * 获取玩家当前打开的视图。
     *
     * @param player 玩家
     * @return 视图，没有则返回 null
     */
    public InventoryView getView(Player player) {
        return manager.getView(player);
    }

    /**
     * 关闭所有视图（插件禁用时调用）。
     */
    public void closeAll() {
        manager.closeAll();
    }
}
