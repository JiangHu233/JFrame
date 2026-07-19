package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面服务（全局视图管理入口）。
 * <p>
 * 负责为每位在线玩家维护一个独立的 {@link ViewManager}，
 * 是外部调用方与表单系统交互的统一入口。
 * <p>
 * <strong>新架构相对旧版的增强：</strong>
 * <ul>
 *   <li><strong>玩家退出自动清理</strong> —— 实现 {@link PluginAware}，
 *       绑定插件后自动注册 {@link PlayerQuitEvent} 监听器，
 *       玩家退出时自动清理其 {@link ViewManager} 与 {@link io.github.JiangHu.jframe.form.data.ViewDataBus}，
 *       无需业务插件手动处理（解决旧版内存泄漏问题）</li>
 *   <li><strong>暴露管理器</strong> —— 新增 {@link #manager(Player)}，
 *       供高级调用方直接操作栈（增删改查、数据总线）</li>
 * </ul>
 * 典型生命周期：
 * <ol>
 *   <li>插件启用时由 {@link PluginAware} 机制自动绑定插件实例并注册退出监听</li>
 *   <li>需要打开界面时调用 {@link #sendForm(FormView, Player)}</li>
 *   <li>玩家下线时自动清理资源（无需手动调用 {@link #remove(Player)}）</li>
 * </ol>
 *
 * @see ViewManager
 */
public class ViewAPI implements PluginAware, Listener {

    /** 玩家 -> 单玩家视图管理器 的映射表。每位玩家对应一个 {@link ViewManager}，互不影响。 */
    private final ConcurrentHashMap<Player, ViewManager> managers = new ConcurrentHashMap<>();

    /** 绑定的插件实例（用于注册事件监听器）。 */
    private Plugin plugin;

    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        // 注册玩家退出监听器，实现自动清理
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 玩家退出事件：自动清理其视图管理器与数据总线。
     * <p>
     * 由 {@link PluginAware} 机制注册，业务插件无需手动监听。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    /**
     * 移除指定玩家的视图管理器（清理资源）。
     * <p>
     * 会清空该玩家视图栈中所有视图的 {@link FormView#onClose} 回调，
     * 并清空其 {@link io.github.JiangHu.jframe.form.data.ViewDataBus 数据总线}。
     * <p>
     * 通常无需手动调用 —— 玩家退出时会由内置监听器自动触发。
     *
     * @param player 要清理的玩家
     */
    public void remove(Player player) {
        ViewManager manager = managers.remove(player);
        if (manager != null) {
            manager.clear();
        }
    }

    /**
     * 为指定玩家创建一个空的视图管理器。
     * <p>
     * 通常无需手动调用 —— {@link #sendForm} 会在需要时自动创建。
     * 若该玩家已存在管理器，则会被覆盖。
     *
     * @param player 要创建管理器的玩家
     */
    public void create(Player player) {
        managers.put(player, new ViewManager(player));
    }

    /**
     * 获取指定玩家的视图管理器（不存在时返回 {@code null}）。
     * <p>
     * 供高级调用方直接操作栈（增删改查、数据总线）。
     *
     * @param player 目标玩家
     * @return 视图管理器，未创建时为 {@code null}
     */
    public ViewManager manager(Player player) {
        return managers.get(player);
    }

    /**
     * 获取或创建指定玩家的视图管理器。
     * <p>
     * 不存在时自动创建一个空的 {@link ViewManager}。
     *
     * @param player 目标玩家
     * @return 视图管理器
     */
    public ViewManager managerOrCreate(Player player) {
        return managers.computeIfAbsent(player, ViewManager::new);
    }

    /**
     * 向指定玩家打开（发送）一个界面。
     * <p>
     * 内部会获取（或创建）该玩家的 {@link ViewManager}，
     * 将视图压栈并立即显示。
     *
     * @param view   要打开的视图
     * @param player 目标玩家
     */
    public void sendForm(FormView view, Player player) {
        managerOrCreate(player).pushAndSend(view);
    }

    /**
     * 向指定玩家打开一个界面，并传递一次性数据。
     * <p>
     * 数据会通过新视图的 {@link FormView#onData} 接收。
     *
     * @param view   要打开的视图
     * @param player 目标玩家
     * @param data   传递给视图的数据
     */
    public void sendForm(FormView view, Player player, Object data) {
        ViewManager manager = managerOrCreate(player);
        manager.push(view, data);
        manager.send();
    }

    /** 全部玩家的视图管理器映射（主要用于调试 / 监控）。 */
    public ConcurrentHashMap<Player, ViewManager> managers() {
        return managers;
    }
}
