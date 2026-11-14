package io.github.JiangHu.jframe.floatingtext.nukkit;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.floatingtext.FloatingTextLog;
import io.github.JiangHu.jframe.floatingtext.FloatingTextManager;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;

import java.util.function.BiConsumer;

/**
 * 观众协调监听器 — 玩家离线清账本；进服/重生/换世界延迟对账补发（设计文档 §4.3）。
 *
 * <h3>事件面</h3>
 * <table border="1">
 *   <tr><th>事件</th><th>客户端状态</th><th>动作</th></tr>
 *   <tr><td>PlayerQuitEvent</td><td>连接断开，会话作废</td><td>清全部账本记录（不发包）</td></tr>
 *   <tr><td>PlayerJoinEvent</td><td>全新会话，无任何假实体</td><td>延迟 2 tick 对账补发常驻</td></tr>
 *   <tr><td>PlayerRespawnEvent</td><td>客户端重生清空实体</td><td>延迟 2 tick 对账补发</td></tr>
 *   <tr><td>EntityLevelChangeEvent（玩家）</td><td>跨维度清空实体</td><td>延迟对账（双向：补 Add / 摘 Remove）</td></tr>
 * </table>
 *
 * <p>对账幂等（{@link FloatingTextManager#resync(TextViewer)} 账本去重），
 * join/respawn 连续触发无害；周期对账（manager.reconcile）兜底事件丢失。
 * 瞬态条目不补发（寿命几秒，重连漏看可接受）。
 */
public class ViewerResyncListener implements PluginAware, Listener {

    /** join/respawn 后延迟对账的 tick 数（等客户端世界就绪，立即发可能被吞） */
    private static final int RESYNC_DELAY_TICKS = 2;

    private final FloatingTextManager manager;
    private Plugin plugin;
    private boolean active;

    /**
     * 构造监听器。
     *
     * @param manager 管理器
     */
    public ViewerResyncListener(FloatingTextManager manager) {
        this.manager = manager;
    }

    /**
     * 绑定插件并注册事件。
     *
     * @param plugin 插件
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        try {
            activate();
        } catch (Exception e) {
            FloatingTextLog.error("观众协调监听器注册失败", e);
        }
    }

    /**
     * 注册事件（幂等）。
     */
    public synchronized void activate() {
        if (active || plugin == null) {
            return;
        }
        registerEvent(PlayerQuitEvent.class,
                (listener, event) -> handleQuit(((PlayerQuitEvent) event).getPlayer()), true);
        registerEvent(PlayerJoinEvent.class,
                (listener, event) -> handleJoin(((PlayerJoinEvent) event).getPlayer()), true);
        registerEvent(PlayerRespawnEvent.class,
                (listener, event) -> handleRespawn(((PlayerRespawnEvent) event).getPlayer()), true);
        registerEvent(EntityLevelChangeEvent.class,
                (listener, event) -> {
                    Entity entity = ((EntityLevelChangeEvent) event).getEntity();
                    if (entity instanceof Player player) {
                        handleLevelChange(player);
                    }
                }, true);
        active = true;
    }

    /**
     * 注销状态（事件分发前检查；Nukkit 无逐事件反注册，置非活跃即静默）。
     */
    public synchronized void deactivate() {
        active = false;
    }

    private void registerEvent(Class<? extends Event> type,
                               BiConsumer<Listener, Event> executor,
                               boolean ignoreCancelled) {
        Server.getInstance().getPluginManager().registerEvent(
                type, this, EventPriority.NORMAL, executor::accept, plugin, ignoreCancelled);
    }

    // ==================== 分发入口（包级可见，直调可测） ====================

    /**
     * 玩家进服 → 延迟对账。
     *
     * @param player 玩家
     */
    void handleJoin(Player player) {
        if (!active) {
            return;
        }
        delayedResync(player, "join");
    }

    /**
     * 玩家重生 → 延迟对账。
     *
     * @param player 玩家
     */
    void handleRespawn(Player player) {
        if (!active) {
            return;
        }
        delayedResync(player, "respawn");
    }

    /**
     * 玩家换世界 → 延迟对账（双向：补 Add / 摘不匹配 Remove）。
     *
     * @param player 玩家
     */
    void handleLevelChange(Player player) {
        if (!active) {
            return;
        }
        delayedResync(player, "level-change");
    }

    /**
     * 玩家离线 → 清账本（不发包）。
     *
     * @param player 玩家
     */
    void handleQuit(Player player) {
        if (!active) {
            return;
        }
        manager.onViewerQuit(player.getUniqueId());
    }

    private void delayedResync(Player player, String reason) {
        if (plugin != null && Server.getInstance() != null) {
            Server.getInstance().getScheduler().scheduleDelayedTask(plugin,
                    () -> {
                        try {
                            if (player.isOnline()) {
                                manager.resync(TextViewer.of(player));
                            }
                        } catch (Exception e) {
                            FloatingTextLog.error("延迟对账异常（" + reason + "）", e);
                        }
                    }, RESYNC_DELAY_TICKS);
        } else {
            manager.resync(TextViewer.of(player));
        }
    }
}
