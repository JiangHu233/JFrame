package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.ModalForm;

/**
 * 模态框（ModalForm）演示。
 * <p>
 * 模态框仅含两个按钮，常用于「确认 / 取消」这类二元决策。本视图演示：
 * <ul>
 *   <li>{@link ModalForm#content(String)} —— 设置正文</li>
 *   <li>{@link ModalForm#buttons(String, String)} —— 设置两个按钮文本</li>
 *   <li>{@link ModalForm#onConfirm(Runnable)} —— 点击第一个按钮（确认）的回调</li>
 *   <li>{@link ModalForm#onCancel(Runnable)} —— 点击第二个按钮（取消）的回调</li>
 * </ul>
 * 点击任一按钮后都会返回演示总菜单（goBack）。
 * <p>
 * 此外还可使用 {@link ModalForm#onClick(java.util.function.Consumer)} 统一处理两个按钮，
 * 回调参数为 {@code true} 表示点击了第一个按钮。
 */
public class ModalFormDemoView extends FormView {

    private final Player player;

    public ModalFormDemoView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        return new ModalForm("§b§l确认传送")
                .content("§7是否花费 §e100 金币 §7传送到主城？\n\n§8点击确认立即传送，点击取消返回。")
                .buttons("§a✔ 确认传送", "§c✘ 取消")
                .onConfirm(() -> {
                    player.sendMessage("§a[传送] §f已传送到主城，扣除 §e100 金币");
                    // 确认后返回演示总菜单
                    goBack();
                })
                .onCancel(() -> {
                    player.sendMessage("§7[传送] §f已取消传送");
                    goBack();
                });
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
