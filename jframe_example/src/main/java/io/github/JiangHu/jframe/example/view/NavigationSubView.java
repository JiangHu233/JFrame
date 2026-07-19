package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

import java.util.List;

/**
 * 栈式导航 —— 子界面（addStack 目标）。
 * <p>
 * 由 {@link NavigationDemoView} 通过 {@link FormView#addStack(FormView)} 压入。
 * 与 {@link FormView#replaceThis(FormView)} 的关键区别（可从界面上的栈快照直观看出）：
 * <ul>
 *   <li>栈深度 +1 —— 本视图被「叠在」NavigationDemoView 之上，而非替换它</li>
 *   <li>栈中仍保留 {@code NavigationDemoView} —— 点 {@link #goBack()} 会回到它</li>
 * </ul>
 * 可连续「再进入一层」观察栈深度持续递增，再逐层 {@link #goBack()} 返回。
 */
public class NavigationSubView extends FormView {

    private final Player player;

    public NavigationSubView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7当前视图栈（§8底 → 顶§7）：\n"
                + stackSnapshot()
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f注意：栈里 §a仍保留着 NavigationDemoView §f！\n"
                + "§f本界面是 §eaddStack §f「叠」在它之上的，\n"
                + "§f所以点 §e返回 §f会回到 NavigationDemoView。";

        return new SimpleForm("§6§l子界面（addStack）")
                .content(content)
                // 连续 addStack：栈深度持续递增
                .button("§a⬇ 再进入一层（addStack）", ctx -> addStack(new NavigationSubView(player)))
                // goBack：回到上一层
                .button("§7↩ 返回上一层（goBack）", ctx -> goBack());
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
