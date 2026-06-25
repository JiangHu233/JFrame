package io.github.JiangHu.jframe.form;

import lombok.Getter;
import lombok.Setter;
import moe.him188.gui.window.FormSimple;


/**
 * 表单视图（界面）的抽象基类。
 * <p>
 * 每一个 {@code FormView} 代表玩家可见的一个 GUI 界面。
 * 多个界面通过 {@link SinglePlayerViewManager} 以「栈」的形式组织，
 * 从而支持「进入子菜单 / 返回上一级」这类常见的界面导航场景。
 * <p>
 * 使用方式：继承本类，在 {@link #buildForm()} 中构建具体的 {@link FormSimple}，
 * 并在 {@link #onClicked(int)} 中处理玩家点击按钮后的逻辑。
 *
 * @see SinglePlayerViewManager
 */
public abstract class FormView {

    /**
     * 当前视图所属的单玩家视图管理器。
     * 由 {@link SinglePlayerViewManager#push(FormView)} 在压栈时自动注入，
     * 子类一般无需手动设置。
     */
    @Setter
    private SinglePlayerViewManager manager;


    /**
     * 当前视图实际展示的表单窗口对象。
     * 由子类在 {@link #buildForm()} 中构建并赋值。
     */
    @Getter
    protected FormSimple form;

    /**
     * 构建表单内容。
     * <p>
     * 子类需在此方法中创建 {@link FormSimple} 并设置标题、按钮等内容，
     * 同时将其赋值给 {@link #form} 字段。
     * 该方法会在每次向玩家发送界面前由管理器调用。
     */
    public abstract void buildForm();

    /**
     * 玩家点击按钮后的回调。
     * <p>
     * 默认空实现，子类按需重写。{@code id} 为被点击按钮的索引。
     *
     * @param id 被点击的按钮索引（从 0 开始）
     */
    protected void onClicked(int id) {}

    /**
     * 用新视图替换当前视图（弹出当前界面，压入新界面）。
     * <p>
     * 典型场景：在同一层级内切换界面，且不希望保留返回路径。
     *
     * @param view 要替换为的新视图
     */
    public void replaceThis(FormView view) {
        manager.pop();
        manager.push(view);
    }

    /**
     * 在当前视图之上压入一个新视图（进入子界面）。
     * <p>
     * 典型场景：进入下一级菜单，之后可通过返回回到当前界面。
     *
     * @param view 要进入的新视图
     */
    public void addStack(FormView view) {
        manager.push(view);
    }


}
