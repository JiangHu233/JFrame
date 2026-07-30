package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;
import io.github.JiangHu.jframe.thread.ThreadAPI;

/**
 * 统计详情子界面。
 * <p>
 * 演示新架构表单的「栈式导航」：由  通过 {@code addStack} 进入，
 * 点击「返回主菜单」后通过 {@code goBack()} 回到主菜单。
 * <p>
 * 相比旧版用 {@code replaceThis(重新构造主菜单)}，{@code goBack()} 直接弹出当前视图，
 * 既简化代码，又能保留主菜单的原始状态。
 */
public class StatsView extends FormView {

    private final ThreadAPI threadAPI;
    private final Player player;

    public StatsView(ThreadAPI threadAPI, Player player) {
        this.threadAPI = threadAPI;
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7玩家：§f" + player.getName() + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§7━━━━━━━━━━━━━━━━";

        // goBack() 弹出当前视图，回应处理后框架会自动重发栈顶（主菜单）
        return new SimpleForm("§a§l我的统计")
                .content(content)
                .button("§c↩ 返回主菜单", ctx -> goBack());
    }

    /**
     * 玩家点击窗口右上角 X 关闭本界面时，返回主菜单（而非弹回当前界面）。
     * <p>
     * 新架构下，关闭窗口后框架默认会重发栈顶（窗口弹回）；
     * 此处主动调用 {@link #goBack()} 弹出当前视图，从而改为重发主菜单。
     */
    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
