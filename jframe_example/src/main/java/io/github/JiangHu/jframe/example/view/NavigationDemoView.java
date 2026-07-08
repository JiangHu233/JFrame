package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;
import io.github.JiangHu.jframe.thread.ThreadAPI;

import java.util.List;

/**
 * 栈式导航演示。
 * <p>
 * 集中演示 {@link FormView} 提供的全部导航方法，每个按钮对应一种导航行为。
 * 界面会实时显示「视图栈快照」（底→顶），通过对比操作前后的栈结构，
 * 可直观理解各方法的差异：
 * <ul>
 *   <li>{@link #addStack(FormView)} —— 压入子界面，栈深度 +1，返回回到本视图</li>
 *   <li>{@link #replaceThis(FormView)} —— 同层替换，栈深度不变，但本视图被新视图取代</li>
 *   <li>{@link #restartWith(FormView)} —— 清空整个栈，以新视图作为根重新开始</li>
 *   <li>{@link #close()} —— 清空整个栈，栈空后不再显示任何界面</li>
 *   <li>{@link #goBack()} —— 弹出当前视图，回到上一级</li>
 * </ul>
 */
public class NavigationDemoView extends FormView {

    private final ThreadAPI threadAPI;
    private final Player player;

    public NavigationDemoView(ThreadAPI threadAPI, Player player) {
        this.threadAPI = threadAPI;
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7当前视图栈（§8底 → 顶§7）：\n"
                + stackSnapshot()
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f各按钮演示不同的导航方式，\n"
                + "§f请对比操作前后的 §e栈结构 §f变化。";

        return new SimpleForm("§6§l栈式导航演示")
                .content(content)
                // addStack：压入子界面，栈深度 +1
                .button("§a⬇ 进入子界面（addStack）", ctx -> addStack(new NavigationSubView(player)))
                // replaceThis：同层替换，栈深度不变，本视图被新视图取代
                .button("§b🔄 同层替换（replaceThis）", ctx -> replaceThis(new NavigationReplacedView(player)))
                // restartWith：清空栈，以主菜单为根重新开始
                .button("§d🏠 重置到主菜单（restartWith）", ctx -> restartWith(new MainMenuView(threadAPI, player)))
                // close：清空栈，栈空后无界面显示
                .button("§c❌ 关闭整个界面（close）", ctx -> close())
                // goBack：弹出当前视图，回到演示总菜单
                .button("§7↩ 返回（goBack）", ctx -> goBack());
    }

    /**
     * 渲染当前视图栈的可读快照：栈底在前，栈顶（当前界面）高亮。
     */
    private String stackSnapshot() {
        if (manager() == null) {
            return " §8（管理器未注入）\n";
        }
        List<FormView> snap = manager().snapshot();
        if (snap.isEmpty()) {
            return " §8（栈为空）\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snap.size(); i++) {
            String name = snap.get(i).getClass().getSimpleName();
            boolean top = (i == snap.size() - 1);
            if (top) {
                sb.append(" §a§l[").append(i).append("] ").append(name).append(" §7← 当前\n");
            } else {
                sb.append(" §8[").append(i).append("] ").append(name).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
