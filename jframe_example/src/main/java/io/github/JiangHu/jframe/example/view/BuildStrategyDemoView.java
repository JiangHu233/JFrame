package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

import java.time.LocalTime;

/**
 * 构建策略（BuildStrategy）演示。
 * <p>
 * 演示 {@link BuildStrategy#ON_DEMAND} 策略：{@link #onBuild()} 仅在首次构建或
 * {@link #markDirty()} 后才会重新执行，而非每次发送都重建（默认 {@link BuildStrategy#ALWAYS}）。
 * <p>
 * 通过观察下方三个数据可直观对比：
 * <ul>
 *   <li><b>onBuild 执行次数</b> —— 每次真正重建都会 +1</li>
 *   <li><b>业务计数</b> —— 仅在主动 {@code counter++} 时变化</li>
 *   <li><b>上次构建时间</b> —— 重建时刷新为当前时间</li>
 * </ul>
 * 三个按钮的对比：
 * <ul>
 *   <li>「计数 +1（markDirty）」—— {@code counter++} 并 {@link #markDirty()}，
 *       下次发送会重建（次数 +1、时间更新）</li>
 *   <li>「仅重发不重建」—— 不调用 markDirty，下次发送虽重发界面但 <b>不重建</b>
 *       （次数、时间、计数均不变），证明 ON_DEMAND 的「按需」特性</li>
 *   <li>「强制刷新（refresh）」—— 等价于 markDirty + 立即发送，必定重建</li>
 * </ul>
 */
public class BuildStrategyDemoView extends FormView {

    private final Player player;

    /** onBuild 执行次数（用于观察是否重建） */
    private int buildCount = 0;
    /** 业务计数 */
    private int counter = 0;
    /** 上次构建时间 */
    private String buildTime = "未构建";

    public BuildStrategyDemoView(Player player) {
        this.player = player;
        // 关键：切换为按需构建策略，onBuild 仅在首次或 markDirty 后执行
        buildStrategy(BuildStrategy.ON_DEMAND);
    }

    @Override
    protected JForm onBuild() {
        buildCount++;
        buildTime = LocalTime.now().withNano(0).toString();

        String content = "§7构建策略（BuildStrategy）演示\n"
                + "§7当前策略：§eON_DEMAND（按需构建）\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§eonBuild 执行次数：§f" + buildCount + "\n"
                + "§e业务计数：§f" + counter + "\n"
                + "§e上次构建时间：§f" + buildTime + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f对比下方三个按钮，观察数据变化：\n"
                + "§a计数+1 §f会重建（次数/时间变）；\n"
                + "§b仅重发 §f不重建（数据全不变）；\n"
                + "§d强制刷新 §f必定重建。";

        return new SimpleForm("§3§l构建策略演示")
                .content(content)
                // markDirty：标记脏，下次发送会重建
                .button("§a➕ 计数 +1（markDirty）", ctx -> {
                    counter++;
                    markDirty();
                })
                // 不 markDirty：ON_DEMAND 下重发不会重建，数据保持不变
                .button("§b🔁 仅重发不重建", ctx -> {
                    String now = LocalTime.now().withNano(0).toString();
                    player.sendMessage("§7真实时间 §e" + now + " §7在走，但界面构建时间仍停在 §e"
                            + buildTime + " §7—— onBuild 未重新执行（数据不变）");
                })
                // refresh：强制重建（等价于 markDirty + 立即发送）
                .button("§d🔄 强制刷新（refresh）", ctx -> refresh())
                .button("§7↩ 返回", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
