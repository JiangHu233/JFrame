package io.github.JiangHu.jframe.form.window;

import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.response.FormResult;

/**
 * 表单布局的统一抽象基类。
 * <p>
 * 这是新架构相对旧版 {@code moe.him188.gui.window.FormSimple} 的核心改进：
 * 将「简单表单 / 自定义表单 / 模态框」三种异构的 Nukkit 原生窗口
 * （{@code FormWindowSimple} / {@code FormWindowCustom} / {@code FormWindowModal}）
 * 统一为一套面向对象的构建与响应读取接口。
 * <p>
 * 本类是布局层与原生 API 之间的<strong>隔离层</strong>：
 * <ul>
 *   <li>对上层（{@link FormView}）：提供 {@link #type()}、{@link #buildResult(Player)}、
 *       {@link #dispatch(FormView, FormResult)} 等统一方法，使视图无需关心表单类型差异</li>
 *   <li>对下层（Nukkit）：通过 {@link #buildWindow()} 转换为原生窗口，
 *       未来若更换底层 GUI 库，只需修改此处转换逻辑，视图层零改动</li>
 * </ul>
 * 三种实现：
 * <ul>
 *   <li>{@link SimpleForm} —— 按钮列表（最常用）</li>
 *   <li>{@link CustomForm} —— 输入元素集合</li>
 *   <li>{@link ModalForm} —— 二选一确认框</li>
 * </ul>
 *
 * @see SimpleForm
 * @see CustomForm
 * @see ModalForm
 */
public abstract class JForm {

    /** 表单类型枚举。 */
    public enum Type {
        /** 简单表单（按钮列表）。 */
        SIMPLE,
        /** 自定义表单（输入元素集合）。 */
        CUSTOM,
        /** 模态框（二选一）。 */
        MODAL
    }

    /** 表单标题。 */
    protected String title;

    /**
     * 转换后的原生窗口（{@link #toNukkit()} 后缓存，用于后续读取响应）。
     */
    protected FormWindow window;

    /**
     * 创建表单。
     *
     * @param title 表单标题
     */
    protected JForm(String title) {
        this.title = title;
    }

    /** 表单标题。 */
    public String title() {
        return title;
    }

    /** 表单类型。 */
    public abstract Type type();

    /**
     * 组装 Nukkit 原生窗口（由子类实现）。
     * <p>
     * 子类需将自身维护的按钮 / 元素转换为原生对象并构造对应的 {@link FormWindow}。
     *
     * @return Nukkit 原生窗口
     */
    protected abstract FormWindow buildWindow();

    /**
     * 从缓存的 {@link #window} 读取响应，构造统一的 {@link FormResult}。
     * <p>
     * 调用前需先执行 {@link #toNukkit()} 并由 Nukkit 回填响应。
     *
     * @param player 提交表单的玩家
     * @return 统一的表单结果
     */
    public abstract FormResult buildResult(Player player);

    /**
     * 分发各自的回调。
     * <p>
     * <ul>
     *   <li>{@link SimpleForm} —— 调用被点击按钮的 {@link Button#onClick} 回调</li>
     *   <li>{@link ModalForm} —— 调用 {@code onConfirm} 或 {@code onCancel} 回调</li>
     *   <li>{@link CustomForm} —— 无按钮级回调，结果统一交由 {@link FormView#onResult} 处理</li>
     * </ul>
     *
     * @param view   所属视图（用于构造 {@link io.github.JiangHu.jframe.form.response.ButtonClick}）
     * @param result 表单结果
     */
    public abstract void dispatch(FormView view, FormResult result);

    /**
     * 转换为 Nukkit 原生窗口并缓存。
     * <p>
     * 框架在向玩家发送表单前调用此方法，转换结果会被 Nukkit 回填响应后用于
     * {@link #buildResult(Player)} 与 {@link #wasClosed()}。
     *
     * @return Nukkit 原生窗口
     */
    public final FormWindow toNukkit() {
        window = buildWindow();
        return window;
    }

    /**
     * 玩家是否直接关闭了窗口（未提交）。
     * <p>
     * 依赖 {@link #window} 已被 {@link #toNukkit()} 转换且 Nukkit 已回填响应。
     *
     * @return {@code true} 表示玩家关闭了窗口
     */
    public boolean wasClosed() {
        return window != null && window.wasClosed();
    }
}
