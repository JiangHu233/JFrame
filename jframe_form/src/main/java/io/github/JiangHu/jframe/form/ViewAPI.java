package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面服务（全局视图管理入口）。
 * <p>
 * 负责为每位在线玩家维护一个独立的 {@link SinglePlayerViewManager}，
 * 是外部调用方与表单系统交互的统一入口。
 * <p>
 * 典型生命周期：
 * <ol>
 *   <li>玩家上线时调用 {@link #create(Player)} 创建其专属管理器</li>
 *   <li>需要打开界面时调用 {@link #sendForm(FormView, Player)}</li>
 *   <li>玩家下线时调用 {@link #remove(Player)} 清理资源</li>
 * </ol>
 *
 * @see SinglePlayerViewManager
 */
public class ViewAPI {

    /**
     * 玩家 -> 单玩家视图管理器 的映射表。
     * <p>
     * 每位玩家对应一个 {@link SinglePlayerViewManager}，互不影响。
     */
    @Getter
    private final ConcurrentHashMap<Player, SinglePlayerViewManager> managers = new ConcurrentHashMap<>();

    /**
     * 移除指定玩家的视图管理器（清理资源）。
     * <p>
     * 通常在玩家退出游戏时调用。
     *
     * @param player 要清理的玩家
     */
    public void remove(Player player) {
        managers.remove(player);
    }

    /**
     * 为指定玩家创建一个空的视图管理器。
     * <p>
     * 通常在玩家进入游戏时调用。若该玩家已存在管理器，则会被覆盖。
     *
     * @param player 要创建管理器的玩家
     */
    public void create(Player player) {
        managers.put(player, new SinglePlayerViewManager());
    }

    /**
     * 向指定玩家打开（发送）一个界面。
     * <p>
     * 内部会获取该玩家的 {@link SinglePlayerViewManager}，
     * 将视图压栈并立即显示。
     * <p>
     * 注意：调用前需确保已通过 {@link #create(Player)} 为该玩家创建管理器，
     * 否则会因获取到 {@code null} 而抛出空指针异常。
     *
     * @param view   要打开的视图
     * @param player 目标玩家
     */
    public void sendForm(FormView view, @NonNull Player player) {
        if (!managers.containsKey(player)) create(player);
        managers.get(player).pushAndSend(view, player);
    }
}
