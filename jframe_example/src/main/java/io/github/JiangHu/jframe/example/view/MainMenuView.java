package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.example.wrapper.PlayerStatWrapper;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;
import io.github.JiangHu.jframe.thread.ThreadAPI;

/**
 * 主菜单界面。
 * <p>
 * 演示新架构表单模块的用法：
 * <ul>
 *   <li>{@link #onBuild()} —— 面向对象构建，每次发送前都会重新执行，内容始终展示最新统计</li>
 *   <li>{@link SimpleForm#button(String, java.util.function.Consumer)} —— 按钮自带回调，
 *       无需 {@code switch(id)} 魔法索引</li>
 *   <li>{@link #getData(String, Object)} —— 从数据总线读取共享状态</li>
 * </ul>
 */
public class MainMenuView extends FormView {

    private final ThreadAPI threadAPI;
    private final Player player;

    public MainMenuView(ThreadAPI threadAPI, Player player) {
        this.threadAPI = threadAPI;
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        // 直接读取事件系统维护的玩家统计（对象级处理器的实例数据）
        PlayerStatWrapper stat = PlayerStatWrapper.get(player);
        int move = stat == null ? 0 : stat.getMoveCount();
        int chat = stat == null ? 0 : stat.getChatCount();

        String content = "§7本界面集成了事件、表单、线程三大模块。\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e移动次数：§f" + move + "    §e聊天次数：§f" + chat + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§8（点击窗口右上角 X 可关闭菜单）";

        // 面向对象构建：按钮自带回调，消除 switch(id) 魔法索引
        return new SimpleForm("§6§lJFrame 示例菜单")
                .content(content)
                .button("§a📊 查看详细统计", ctx -> addStack(new StatsView(threadAPI, player)))
                .button("§b⚡ 执行异步任务", ctx -> runAsyncTask())
                .button("§9🧪 表单特性演示", ctx -> addStack(new FormDemoView(threadAPI, player)));
    }

    /**
     * 玩家点击窗口右上角 X 关闭菜单时，真正关闭整个界面（清空视图栈）。
     * <p>
     * 新架构下，关闭窗口后框架默认会重发栈顶（窗口弹回）；
     * 此处主动调用 {@link #close()} 清空栈，从而真正关闭菜单。
     */
    @Override
    protected void onCloseAttempt() {
        close();
    }

    /**
     * 向 "example" 任务队列提交一个异步任务。
     * <p>
     * 该队列为单线程执行器，任务按提交顺序串行执行。
     * 注意：跨线程仅发送简单文本消息；若需操作主线程 API，应调度回主线程。
     */
    private void runAsyncTask() {
        threadAPI.pushTask("example", () -> {
            try {
                Thread.sleep(1000); // 模拟耗时计算
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            player.sendMessage("§b[异步任务] §f耗时计算完成，结果 = "
                    + (int) (Math.random() * 100));
        });
        player.sendMessage("§e已提交异步任务，约 1 秒后返回结果...");
    }
}
