package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

import java.util.List;

/**
 * 栈式导航 —— 替换后视图（replaceThis 目标）。
 * <p>
 * 由 {@link NavigationDemoView} 通过 {@link FormView#replaceThis(FormView)} 替换而来。
 * 与 {@link FormView#addStack(FormView)} 的关键区别（可从界面上的栈快照直观看出）：
 * <ul>
 *   <li>栈深度不变 —— {@code NavigationDemoView} 被本视图「原地取代」，而非在其之上新增一层</li>
 *   <li>栈中已不再有 {@code NavigationDemoView} —— 因此点 {@link #goBack()} 会回到它的父视图
 *       （演示总菜单），而不会回到 {@code NavigationDemoView}</li>
 * </ul>
 */
public class NavigationReplacedView extends FormView {

    @SuppressWarnings("unused")
    private final Player player;

    public NavigationReplacedView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7当前视图栈（§8底 → 顶§7）：\n"
                + stackSnapshot()
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f注意：栈里已经 §c没有 NavigationDemoView §f了！\n"
                + "§f它是被 §ereplaceThis §f「原地替换」掉的，\n"
                + "§f所以点 §e返回 §f会直接跳到演示总菜单。";

        return new SimpleForm("§b§l替换后视图")
                .content(content)
                // goBack：因 replaceThis 保留了父视图关系，此处返回到演示总菜单
                .button("§7↩ 返回（goBack → 演示总菜单）", ctx -> goBack());
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
