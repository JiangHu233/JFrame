package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

/**
 * 一次性数据传递 —— 详情界面（onData 接收方）。
 * <p>
 * 由 {@link OnDataDemoView} 通过 {@link FormView#addStack(FormView, Object)} 或
 * {@link FormView#passDataTo(FormView, Object)} 传入数据，本视图在
 * {@link #onData(Object)} 中接收并保存，随后在 {@link #onBuild()} 中展示。
 * <p>
 * 时序：{@code onData} 在压栈 / 传参时立即触发（早于首次 {@code onBuild}），
 * 因此 {@code onBuild} 能读到已保存的数据。
 */
public class OnDataDetailView extends FormView {

    private final Player player;

    /** 接收到的数据（默认提示无数据） */
    private String receivedData = "§8（未收到数据）";

    public OnDataDetailView(Player player) {
        this.player = player;
    }

    /**
     * 接收父视图塞入的一次性数据。
     * <p>
     * 本方法在压栈（addStack(view, data)）或显式传参（passDataTo）时触发，
     * 早于首次 onBuild，故可在此保存数据供构建时使用。
     */
    @Override
    protected void onData(Object data) {
        receivedData = String.valueOf(data);
        player.sendMessage("§a[onData] §f已收到数据：§e" + receivedData);
    }

    @Override
    protected JForm onBuild() {
        String content = "§7详情界面（通过 onData 接收数据）\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e收到的数据：\n§f" + receivedData + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§8该数据是一次性传递的，不会持久化。";

        return new SimpleForm("§c§l数据详情")
                .content(content)
                .button("§7↩ 返回", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
