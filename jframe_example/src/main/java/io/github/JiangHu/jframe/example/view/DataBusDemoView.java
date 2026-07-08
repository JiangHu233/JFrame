package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

import java.util.function.Consumer;

/**
 * 数据总线（ViewDataBus）演示。
 * <p>
 * 演示同一玩家所有视图共享的 {@link io.github.JiangHu.jframe.form.data.ViewDataBus 数据总线}，
 * 以「金币」为示例数据，展示「数据变化即刷新」的响应式更新：
 * <ul>
 *   <li>{@link #onShow()} —— 订阅 {@code coins} 键（仅订阅一次，避免重复注册）</li>
 *   <li>{@link #putData(String, Object)} —— 修改金币，自动通知所有订阅者</li>
 *   <li>{@link #getData(String, Object)} —— 读取当前金币</li>
 *   <li>订阅回调 {@code v -> refresh()} —— 金币变化时自动重建界面</li>
 *   <li>{@link #notifyRefreshAll()} —— 显式通知栈中所有视图刷新</li>
 *   <li>{@link #onClose()} —— 视图关闭时取消订阅，避免残留回调</li>
 * </ul>
 * <p>
 * 与一次性数据传递（{@link #onData(Object)}）的区别：数据总线是基于「键」的持久化共享状态，
 * 适合多个视图共同读写；{@code onData} 是一次性对象传递，适合「带参打开子界面」。
 *
 * @see OnDataDemoView
 */
public class DataBusDemoView extends FormView {

    private final Player player;

    /** 金币订阅回调（持有引用，以便关闭时取消订阅） */
    private Consumer<Object> coinsListener;
    /** 是否已订阅（onShow 每次显示都会调用，需避免重复注册） */
    private boolean subscribed;

    public DataBusDemoView(Player player) {
        this.player = player;
    }

    /**
     * 每次显示前调用：首次进入时订阅金币变化。
     * <p>
     * 注意：onShow 在每次发送时都会触发，故用 {@code subscribed} 标志保证只订阅一次。
     */
    @Override
    protected void onShow() {
        if (!subscribed) {
            coinsListener = v -> refresh();
            subscribe("coins", coinsListener);
            subscribed = true;
        }
    }

    /**
     * 视图关闭时取消订阅，避免回调残留导致内存泄漏或多余刷新。
     */
    @Override
    protected void onClose() {
        if (coinsListener != null && manager() != null) {
            manager().dataBus().unsubscribe("coins", coinsListener);
            coinsListener = null;
            subscribed = false;
        }
    }

    @Override
    protected JForm onBuild() {
        int coins = getData("coins", 0);

        String content = "§7数据总线（ViewDataBus）演示\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e当前金币：§f" + coins + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f本界面（金币面板）订阅了 §ecoins §f键：\n"
                + "§f• 本界面改金币 → §a自动刷新§f（subscribe 回调）\n"
                + "§f• 商店里改金币 → §a返回后自动显示最新值§f\n"
                + " §7（跨视图更新，二者共享同一总线）";

        return new SimpleForm("§e§l数据总线演示")
                .content(content)
                // putData 修改金币 → 自动触发 subscribe 回调 → refresh，无需手动刷新
                .button("§a💰 金币 +100", ctx -> {
                    int c = getData("coins", 0);
                    putData("coins", c + 100);
                })
                .button("§c💰 金币 -100", ctx -> {
                    int c = getData("coins", 0);
                    putData("coins", Math.max(0, c - 100));
                })
                // 进入商店子界面：演示跨视图数据传输与更新通知
                .button("§6🛒 进入商店（跨视图联动）", ctx -> addStack(new ShopSubView(player)))
                // notifyRefreshAll：显式通知栈中所有视图刷新（标记脏 + 重发栈顶）
                .button("§d📢 通知所有视图刷新", ctx -> {
                    notifyRefreshAll();
                    player.sendMessage("§e已调用 notifyRefreshAll，栈中所有视图已标记刷新");
                })
                .button("§7↩ 返回", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
