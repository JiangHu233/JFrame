package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.example.wrapper.PlayerStatWrapper;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.thread.ThreadAPI;
import moe.him188.gui.window.FormSimple;

/**
 * 主菜单界面。
 * <p>
 * 演示表单模块的用法：
 * <ul>
 *   <li>{@link #buildForm()} —— 每次发送前都会重新构建，因此内容始终展示最新统计</li>
 *   <li>{@link #onClicked(int)} —— 按钮回调，进入子菜单或提交异步任务</li>
 *   <li>{@link FormSimple} —— GUI 库的简单表单（标题 + 内容 + 按钮）</li>
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
    public void buildForm() {
        // 直接读取事件系统维护的玩家统计（对象级处理器的实例数据）
        PlayerStatWrapper stat = PlayerStatWrapper.get(player);
        int move = stat == null ? 0 : stat.getMoveCount();
        int chat = stat == null ? 0 : stat.getChatCount();

        String content = "§7本界面集成了事件、表单、线程三大模块。\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e移动次数：§f" + move + "    §e聊天次数：§f" + chat + "\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§8（点击窗口右上角 X 可关闭菜单）";

        // FormSimple(标题, 内容, 按钮...)，按钮索引从 0 开始
        form = new FormSimple("§6§lJFrame 示例菜单", content,
                "§a📊 查看详细统计",
                "§b⚡ 执行异步任务");
    }

    @Override
    protected void onClicked(int id) {
        switch (id) {
            case 0 -> addStack(new StatsView(threadAPI, player)); // 进入子菜单
            case 1 -> runAsyncTask();                                 // 提交异步任务
            default -> { /* 未知按钮，忽略 */ }
        }
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
