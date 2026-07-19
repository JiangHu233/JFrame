package io.github.JiangHu.jframe.form.response;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.Button;

/**
 * 按钮点击事件上下文。
 * <p>
 * 当玩家在 {@link io.github.JiangHu.jframe.form.window.SimpleForm 简单表单} 中点击某个按钮时，
 * 框架会构造本对象并传递给 {@link Button#onClick(java.util.function.Consumer) 按钮回调}。
 * <p>
 * 通过本对象可获取：点击的玩家、按钮索引、按钮对象本身，以及所属视图。
 * 同时提供 {@link #goBack()}、{@link #refresh()}、{@link #close()} 等便捷导航方法，
 * 使回调内部无需持有视图引用即可完成常见导航。
 *
 * @see Button#onClick(java.util.function.Consumer)
 */
public final class ButtonClick {

    private final Player player;
    private final int index;
    private final Button button;
    private final FormView view;

    public ButtonClick(Player player, int index, Button button, FormView view) {
        this.player = player;
        this.index = index;
        this.button = button;
        this.view = view;
    }

    /** 触发本次点击的玩家。 */
    public Player player() {
        return player;
    }

    /** 被点击按钮的索引（从 0 开始）。 */
    public int index() {
        return index;
    }

    /** 被点击的按钮对象。 */
    public Button button() {
        return button;
    }

    /** 本次点击所属的视图。 */
    public FormView view() {
        return view;
    }

    // -------------------- 便捷导航 --------------------

    /** 返回上一级界面。等价于 {@code view().goBack()}。 */
    public void goBack() {
        view.goBack();
    }

    /** 重新构建并刷新当前界面。等价于 {@code view().refresh()}。 */
    public void refresh() {
        view.refresh();
    }

    /** 关闭当前界面栈。等价于 {@code view().close()}。 */
    public void close() {
        view.close();
    }

    /**
     * 用新视图替换当前视图（保持父视图关系不变）。
     * 等价于 {@code view().replaceThis(newView)}。
     *
     * @param newView 要替换为的新视图
     */
    public void replaceThis(FormView newView) {
        view.replaceThis(newView);
    }

    /**
     * 清空视图栈并以新视图作为根视图重新开始。
     * 等价于 {@code view().restartWith(newRoot)}。
     *
     * @param newRoot 新的根视图
     */
    public void restartWith(FormView newRoot) {
        view.restartWith(newRoot);
    }

    // -------------------- 通知刷新 --------------------

    /**
     * 通知指定视图刷新（标记为脏，若为当前栈顶则立即重发）。
     * 等价于 {@code view().notifyRefresh(target)}。
     *
     * @param target 要刷新的目标视图
     */
    public void notifyRefresh(FormView target) {
        view.notifyRefresh(target);
    }

    /**
     * 通知栈中所有视图刷新（全部标记为脏，重发当前栈顶）。
     * 等价于 {@code view().notifyRefreshAll()}。
     */
    public void notifyRefreshAll() {
        view.notifyRefreshAll();
    }
}
