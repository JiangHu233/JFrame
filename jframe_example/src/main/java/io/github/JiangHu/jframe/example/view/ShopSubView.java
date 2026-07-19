package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

/**
 * 数据总线演示 —— 商店子界面。
 * <p>
 * 与 {@link DataBusDemoView 金币面板} 共享同一个 {@link io.github.JiangHu.jframe.form.data.ViewDataBus 数据总线}，
 * 用于直观体现数据总线的两大核心能力：
 * <ul>
 *   <li><b>跨视图数据传输</b> —— 本界面通过 {@link #getData(String, Object)} 读取金币，
 *       与金币面板看到的是<b>同一份数据</b>，二者无需互相持有引用</li>
 *   <li><b>跨视图更新通知</b> —— 本界面通过 {@link #putData(String, Object)} 修改金币时，
 *       会自动通知金币面板（订阅者）刷新；即使金币面板当前不在前台，
 *       返回时也会显示最新值</li>
 * </ul>
 * <p>
 * 操作流程：金币面板（看金币）→ 进入商店（读到相同金币）→ 购买/出售（修改金币）→
 * 返回金币面板（自动显示最新金币）。
 *
 * @see DataBusDemoView
 */
public class ShopSubView extends FormView {

    private final Player player;

    public ShopSubView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        // 从数据总线读取金币 —— 与金币面板是同一份数据，体现「跨视图数据传输」
        int coins = getData("coins", 0);

        String content = "§7商店子界面（与金币面板共享数据总线）\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e当前金币（getData 读取）：§f" + coins + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f这里的金币与金币面板是 §a同一份数据§f。\n"
                + "§f购买 / 出售会通过 §eputData §f更新金币，\n"
                + "§f并自动通知金币面板（订阅者）刷新。\n"
                + "§f返回金币面板即可看到最新值。";

        return new SimpleForm("§6§l商店（数据总线联动）")
                .content(content)
                // putData 修改金币 → 自动触发金币面板的 subscribe 回调（标记刷新）
                .button("§a🛒 购买商品（金币 -50）", ctx -> {
                    int c = getData("coins", 0);
                    if (c >= 50) {
                        putData("coins", c - 50);
                        player.sendMessage("§a购买成功！金币 -50，金币面板已收到更新通知");
                    } else {
                        player.sendMessage("§c金币不足，无法购买！");
                    }
                })
                .button("§b💰 出售物品（金币 +30）", ctx -> {
                    int c = getData("coins", 0);
                    putData("coins", c + 30);
                    player.sendMessage("§a出售成功！金币 +30，金币面板已收到更新通知");
                })
                // goBack：金币面板已成为栈顶且被标记脏，会自动重建显示最新金币
                .button("§7↩ 返回金币面板（goBack）", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
