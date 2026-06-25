package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import lombok.Getter;

import java.util.Stack;

/**
 * 单玩家视图管理器。
 * <p>
 * 用于接管并管理某一个玩家的界面显示。内部维护一个 {@link Stack 视图栈}，
 * 栈顶元素即为当前向玩家展示的界面。通过压栈 / 出栈操作，
 * 可以实现「进入子菜单」「返回上一级」等界面导航逻辑。
 * <p>
 * 通常由 {@link ViewAPI} 为每位玩家创建并管理一个实例。
 *
 * @see FormView
 * @see ViewAPI
 */
public class SinglePlayerViewManager {

    /**
     * 视图栈：保存该玩家当前打开的所有界面层级。
     * <p>
     * 栈底是最早打开的界面，栈顶（最后压入的元素）是当前显示的界面。
     */
    @Getter
    private final Stack<FormView> views = new Stack<>();

    /**
     * 压入一个新视图并立即向玩家发送（显示）。
     * <p>
     * 等价于先 {@link #push(FormView)} 再 {@link #send(Player)}。
     *
     * @param view   要打开的视图
     * @param player 目标玩家
     */
    public void pushAndSend(FormView view, Player player) {
        push(view);
        send(player);
    }

    /**
     * 将视图压入栈顶，并为其注入当前管理器引用。
     * <p>
     * 注意：此方法仅修改栈结构，不会向玩家发送界面。
     * 如需同时显示，请使用 {@link #pushAndSend(FormView, Player)}。
     *
     * @param view 要压入的视图
     */
    public void push(FormView view) {
        // 注入管理器引用，使视图能够调用 pop/push 等导航方法
        view.setManager(this);

        views.push(view);
    }

    /**
     * 弹出栈顶视图并返回。
     * <p>
     * 注意：当栈为空时调用会抛出 {@link java.util.EmptyStackException}。
     *
     * @return 被弹出的栈顶视图
     */
    public FormView pop() {
        return views.pop();
    }

    /**
     * 向玩家发送（显示）当前栈顶界面。
     * <p>
     * 发送前会先调用栈顶视图的 {@link FormView#buildForm()} 构建表单内容，
     * 然后通过 Nukkit 的 {@link Player#showFormWindow} 展示。
     * 玩家点击按钮后会触发回调：先执行视图的 {@link FormView#onClicked(int)}，
     * 再重新发送当前界面，从而实现「点击后刷新」的效果。
     *
     * @param player 目标玩家
     */
    protected void send(Player player) {
        // 栈为空时没有可显示的界面，直接返回
        if (this.views.isEmpty()) return;

        // 取栈顶视图（当前应显示的界面）
        FormView view = this.views.peek();

        // 每次发送前重新构建表单，保证内容为最新
        view.buildForm();


        // 向玩家展示表单，并注册点击回调
        player.showFormWindow(view.getForm().onClicked(
                id -> {
                    // 将点击事件交给视图处理
                    view.onClicked(id);
                    // 处理完毕后重新发送当前界面（刷新）
                    this.send(player);
                }
        ));
    }
}
