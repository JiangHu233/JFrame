package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.example.wrapper.PlayerStatWrapper;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.thread.ThreadAPI;
import moe.him188.gui.window.FormSimple;

/**
 * 统计详情子界面。
 * <p>
 * 演示表单的「栈式导航」：由 {@link MainMenuView} 通过 {@code addStack} 进入，
 * 点击「返回主菜单」后通过 {@code replaceThis} 回到主菜单。
 */
public class StatsView extends FormView {

    private final ThreadAPI threadAPI;
    private final Player player;

    public StatsView(ThreadAPI threadAPI, Player player) {
        this.threadAPI = threadAPI;
        this.player = player;
    }

    @Override
    public void buildForm() {
        PlayerStatWrapper stat = PlayerStatWrapper.get(player);
        int move = stat == null ? 0 : stat.getMoveCount();
        int chat = stat == null ? 0 : stat.getChatCount();

        String content = "§7玩家：§f" + player.getName() + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e移动事件次数：§f" + move + "\n"
                + "§e聊天次数：§f" + chat + "\n"
                + "§7━━━━━━━━━━━━━━━━";

        form = new FormSimple("§a§l我的统计", content, "§c↩ 返回主菜单");
    }

    @Override
    protected void onClicked(int id) {
        // 用新的主菜单替换当前视图：弹出本视图、压入主菜单，框架随后会重新发送主菜单
        replaceThis(new MainMenuView(threadAPI, player));
    }
}
