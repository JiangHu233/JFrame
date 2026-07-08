package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.data.ViewDataBus;
import io.github.JiangHu.jframe.form.response.FormResult;
import io.github.JiangHu.jframe.form.window.JForm;

import java.util.function.Consumer;

/**
 * 表单视图（界面）的抽象基类。
 * <p>
 * 每一个 {@code FormView} 代表玩家可见的一个 GUI 界面。多个界面通过
 * {@link ViewManager} 以「栈」的形式组织，从而支持「进入子菜单 / 返回上一级」等导航场景。
 * <p>
 * <strong>核心特性：</strong>
 * <ul>
 *   <li><strong>面向对象构建</strong> —— 子类在 {@link #onBuild()} 中返回一个 {@link JForm}，
 *       用链式 Builder 描述布局，彻底告别 {@code switch(id)}</li>
 *   <li><strong>构建策略</strong> —— {@link BuildStrategy#ALWAYS}（每次发送前重建，默认）
 *       或 {@link BuildStrategy#ON_DEMAND}（按需重建，配合 {@link #markDirty()}）</li>
 *   <li><strong>关闭回调</strong> —— 重写 {@link #onCloseAttempt()} 可在玩家关闭窗口后
 *       自行决定后续行为；框架默认会重发栈顶，需真正关闭时在回调中调用 goBack / close</li>
 *   <li><strong>统一结果</strong> —— 无论哪种表单类型，提交结果都通过 {@link #onResult(FormResult)} 统一处理</li>
 *   <li><strong>生命周期</strong> —— {@link #onShow()}（每次显示前）、{@link #onClose()}（关闭时）</li>
 *   <li><strong>数据访问</strong> —— {@link #putData} / {@link #getData} / {@link #subscribe}
 *       委托给 {@link ViewDataBus}，实现窗口间数据塞入与通知更新</li>
 *   <li><strong>导航快捷方法</strong> —— {@link #goBack()}、{@link #close()}、{@link #refresh()}、
 *       {@link #restartWith(FormView)}</li>
 * </ul>
 *
 * @see ViewManager
 * @see JForm
 * @see ViewDataBus
 * @see BuildStrategy
 */
public abstract class FormView {

    /**
     * 表单构建策略。
     * <p>
     * 控制 {@link ViewManager#send()} 在发送界面前是否重新调用 {@link FormView#onBuild()}。
     */
    public enum BuildStrategy {
        /**
         * 每次发送前都重新构建（默认）。
         * <p>
         * 适合内容会动态变化的视图（如展示实时数据）。
         */
        ALWAYS,
        /**
         * 仅在首次构建或 {@link #markDirty()} 后重新构建。
         * <p>
         * 适合内容基本静态、或希望精确控制刷新时机的视图。
         * 需要刷新时调用 {@link #markDirty()} 标记脏，下次 {@code send} 即会重建。
         * 也可随时调用 {@link #refresh()} 强制重建。
         */
        ON_DEMAND
    }

    /**
     * 当前视图所属的单玩家视图管理器。
     * <p>
     * 由 {@link ViewManager#push(FormView)} 在压栈时自动注入，子类一般无需手动设置。
     */
    private ViewManager manager;

    /**
     * 当前视图构建出的表单布局对象。
     * <p>
     * 由 {@link #onBuild()} 产生，根据 {@link #buildStrategy} 决定是否每次发送前重建。
     */
    private JForm form;

    /** 构建策略，默认 {@link BuildStrategy#ALWAYS}。 */
    private BuildStrategy buildStrategy = BuildStrategy.ALWAYS;

    /** 是否需要重新构建（脏标志）。 */
    private boolean needsRebuild = true;

    // -------------------- 子类可重写的生命周期方法 --------------------

    /**
     * 构建表单布局（核心方法）。
     * <p>
     * 子类在此方法中创建并返回一个 {@link JForm}，用链式 Builder 描述界面内容。
     * 调用时机由 {@link #buildStrategy} 控制：
     * <ul>
     *   <li>{@link BuildStrategy#ALWAYS} —— 每次 {@code send} 前调用</li>
     *   <li>{@link BuildStrategy#ON_DEMAND} —— 首次或 {@link #markDirty()} 后调用</li>
     * </ul>
     *
     * @return 表单布局对象
     */
    protected abstract JForm onBuild();

    /**
     * 生命周期：每次显示前调用。
     * <p>
     * 在 {@link #onBuild()} 之后、实际发送给玩家之前调用。
     * 可用于记录日志、播放音效、订阅数据等。默认空实现。
     */
    protected void onShow() {}

    /**
     * 生命周期：视图被关闭时调用。
     * <p>
     * 触发时机：玩家直接关闭窗口（点 X）且 {@link #onCloseAttempt()} 未阻止、
     * 被弹出栈、管理器被清空。
     * 默认空实现。
     */
    protected void onClose() {}

    /**
     * 统一的表单提交结果处理。
     * <p>
     * 无论底层是简单表单、自定义表单还是模态框，玩家提交后都会调用本方法。
     * 对于简单表单 / 模态框，按钮级回调已在 {@link JForm#dispatch} 中先行触发，
     * 本方法作为「兜底」统一处理结果（例如自定义表单的输入值读取）。
     * <p>
     * 若玩家直接关闭窗口，本方法<strong>不会</strong>被调用（改走 {@link #onCloseAttempt()}）。
     * 默认空实现。
     *
     * @param result 提交结果
     */
    protected void onResult(FormResult result) {}

    /**
     * 接收其他视图塞入的数据。
     * <p>
     * 当其他视图通过 {@link #passDataTo(FormView, Object)} 向本视图传递一次性数据时，
     * 本方法会被调用。与 {@link ViewDataBus} 的区别：
     * <ul>
     *   <li>{@link ViewDataBus} —— 基于「键」的持久化数据，适合共享状态</li>
     *   <li>{@code onData} —— 一次性对象传递，适合「带参打开子界面」</li>
     * </ul>
     * 默认空实现。
     *
     * @param data 被塞入的数据
     */
    protected void onData(Object data) {}

    /**
     * 玩家关闭窗口时的回调。
     * <p>
     * 当玩家点击窗口右上角 X（或按 ESC）关闭窗口时，框架调用本方法。
     * 窗口已经在客户端关闭（此过程不可阻止）。
     * <p>
     * <strong>默认行为：</strong>本方法返回后，框架会重新发送当前视图栈的栈顶界面。
     * 也就是说，若本方法什么都不做，被关闭的窗口会立刻重新弹出（「窗口弹回」）。
     * <p>
     * 若不希望关闭后重发当前界面，程序员需在本方法中主动操作视图栈：
     * <ul>
     *   <li>{@code goBack()} —— 弹出当前视图，改为重发其父界面（返回上一级）</li>
     *   <li>{@code close()} —— 清空整个视图栈，栈空则不再发送任何界面（真正关闭）</li>
     *   <li>其他自定义操作（如弹出确认框、播放音效等）</li>
     * </ul>
     * 默认空实现（关闭后重发当前栈顶）。
     *
     * <pre>{@code
     * @Override
     * protected void onCloseAttempt() {
     *     // 玩家关闭后真正关闭整个界面（而非弹回）
     *     close();
     * }
     * }</pre>
     */
    protected void onCloseAttempt() {
    }

    // -------------------- 构建策略 --------------------

    /**
     * 当前构建策略。
     *
     * @return 构建策略枚举
     * @see BuildStrategy
     */
    public BuildStrategy buildStrategy() {
        return buildStrategy;
    }

    /**
     * 设置构建策略（可在构造函数中调用）。
     *
     * @param strategy 构建策略
     * @return 当前视图，便于链式调用
     */
    public FormView buildStrategy(BuildStrategy strategy) {
        this.buildStrategy = strategy;
        return this;
    }

    /**
     * 标记当前视图需要重新构建（设置脏标志）。
     * <p>
     * 在 {@link BuildStrategy#ON_DEMAND} 模式下，调用此方法后，
     * 下次 {@link ViewManager#send()} 会重新执行 {@link #onBuild()}。
     * <p>
     * 在 {@link BuildStrategy#ALWAYS} 模式下无实际效果（因为本就每次重建）。
     */
    public void markDirty() {
        this.needsRebuild = true;
    }

    // -------------------- 框架内部调用 --------------------

    /**
     * 由 {@link ViewManager} 在压栈时注入管理器引用。
     *
     * @param manager 所属管理器
     */
    void bind(ViewManager manager) {
        this.manager = manager;
    }

    /**
     * 判断是否需要重新构建（由管理器在发送前调用）。
     *
     * @return {@code true} 表示需要重建
     */
    boolean shouldRebuild() {
        if (form == null || needsRebuild) {
            return true;
        }
        return buildStrategy == BuildStrategy.ALWAYS;
    }

    /**
     * 执行重建并清除脏标志（由管理器在发送前调用）。
     */
    void rebuild() {
        form = onBuild();
        needsRebuild = false;
    }

    /** 当前构建出的表单布局对象。 */
    public JForm form() {
        return form;
    }

    /** 所属管理器（压栈后可用）。 */
    protected ViewManager manager() {
        return manager;
    }

    /**
     * 由管理器在玩家提交后调用，分发结果。
     */
    void handleResult(FormResult result) {
        form.dispatch(this, result);
        onResult(result);
    }

    /**
     * 由管理器在玩家关闭窗口（且 {@link #onCloseAttempt()} 允许）时调用。
     */
    void handleClose() {
        onClose();
    }

    /**
     * 由管理器在玩家关闭窗口时调用，触发 {@link #onCloseAttempt}。
     * <p>
     * 框架不自动弹出父窗口或重新发送，一切由 {@link #onCloseAttempt} 中程序员的决定为准。
     */
    void handleCloseAttempt() {
        onCloseAttempt();
    }

    /**
     * 由管理器在压栈 / 传递数据时调用，触发 {@link #onData}。
     */
    void receiveData(Object data) {
        onData(data);
    }

    // -------------------- 导航（委托给 ViewManager） --------------------

    /**
     * 返回上一级界面（弹出当前视图，显示栈中上一个界面）。
     * <p>
     * 若当前已是栈底，则不做任何操作。
     */
    public void goBack() {
        if (manager != null) {
            manager.goBack();
        }
    }

    /**
     * 在当前视图之上压入一个新视图（进入子界面）。
     * <p>
     * 典型场景：进入下一级菜单，之后可通过 {@link #goBack()} 返回。
     *
     * @param view 要进入的新视图
     */
    public void addStack(FormView view) {
        if (manager != null) {
            manager.pushAndSend(view);
        }
    }

    /**
     * 压入新视图并向其传递一次性数据。
     *
     * @param view 要进入的新视图
     * @param data 传递给新视图的数据（通过其 {@link #onData} 接收）
     */
    public void addStack(FormView view, Object data) {
        if (manager != null) {
            manager.push(view, data);
            manager.send();
        }
    }

    /**
     * 用新视图替换当前视图（弹出当前界面，压入新界面）。
     * <p>
     * <strong>替换后保持父视图关系不变</strong>：新视图在栈中的位置与旧视图相同，
     * 其下方的父视图不受影响，后续仍可通过 {@link #goBack()} 返回父界面。
     * <p>
     * 替换后框架会自动发送新视图。
     * <p>
     * 典型场景：在同一层级内切换界面（如「编辑 → 预览」），且不希望增加栈深度。
     *
     * @param view 要替换为的新视图
     */
    public void replaceThis(FormView view) {
        if (manager != null) {
            manager.replaceAndSend(this, view);
        }
    }

    /**
     * 关闭当前界面栈（清空全部视图）。
     * <p>
     * 触发栈中所有视图的 {@link #onClose()}。
     */
    public void close() {
        if (manager != null) {
            manager.clear();
        }
    }

    /**
     * 重新构建并刷新当前界面（强制重建，不受 {@link BuildStrategy} 影响）。
     * <p>
     * 适用于「数据变化后立即更新显示」的场景。
     * <p>
     * <strong>仅当本视图是当前栈顶时才会立即重发</strong>；若本视图不在栈顶
     * （例如被其他子界面覆盖、或已被弹出），则只标记为脏（下次显示时重建），
     * 不会强行重发栈顶的其他界面，避免打断玩家当前操作。
     * <p>
     * 这一行为与 {@link #notifyRefresh(FormView)} 一致，二者可互换使用。
     */
    public void refresh() {
        if (manager != null) {
            needsRebuild = true;
            // 仅当本视图是当前栈顶时才立即发送；否则只标记脏，
            // 待本视图再次成为栈顶时自然重建
            if (manager.current() == this) {
                manager.send();
            }
        }
    }

    /**
     * 清空当前视图栈，以新视图作为根视图重新开始。
     * <p>
     * 等价于先 {@link #close()}（触发所有旧视图的 {@link #onClose()}），
     * 再以 {@code newRoot} 为唯一视图重新建立栈并立即发送。
     * <p>
     * 典型场景：完成某个流程后「回到首页」、登录后进入主界面等需要彻底重置导航路径的场景。
     *
     * @param newRoot 新的根视图
     */
    public void restartWith(FormView newRoot) {
        if (manager != null) {
            manager.restartWith(newRoot);
        }
    }

    // -------------------- 数据访问（委托给 ViewDataBus） --------------------

    /**
     * 塞入（更新）一个数据项到数据总线，并通知订阅者。
     *
     * @param key   数据键
     * @param value 数据值
     * @see ViewDataBus#put
     */
    public void putData(String key, Object value) {
        if (manager != null) {
            manager.dataBus().put(key, value);
        }
    }

    /**
     * 从数据总线读取一个数据项。
     *
     * @param key 数据键
     * @param <T> 值类型
     * @return 数据值，不存在时为 {@code null}
     * @see ViewDataBus#get(String)
     */
    public <T> T getData(String key) {
        return manager == null ? null : manager.dataBus().get(key);
    }

    /**
     * 从数据总线读取一个数据项，不存在时返回默认值。
     *
     * @param key    数据键
     * @param defVal 默认值
     * @param <T>    值类型
     * @return 数据值或默认值
     * @see ViewDataBus#get(String, Object)
     */
    public <T> T getData(String key, T defVal) {
        return manager == null ? defVal : manager.dataBus().get(key, defVal);
    }

    /**
     * 订阅数据总线中某个键的变化。
     *
     * @param key      数据键
     * @param listener 回调
     * @see ViewDataBus#subscribe
     */
    public void subscribe(String key, Consumer<Object> listener) {
        if (manager != null) {
            manager.dataBus().subscribe(key, listener);
        }
    }

    /**
     * 向另一个视图传递一次性数据（触发其 {@link #onData}）。
     *
     * @param target 目标视图
     * @param data   数据
     */
    public void passDataTo(FormView target, Object data) {
        target.receiveData(data);
    }

    // -------------------- 通知刷新（委托给 ViewManager） --------------------

    /**
     * 通知指定视图刷新（标记为脏，若该视图是当前栈顶则立即重新发送）。
     * <p>
     * 用于通知某个特定视图其依赖的数据已变化，需要重建。
     *
     * @param target 要刷新的目标视图
     */
    public void notifyRefresh(FormView target) {
        if (manager != null) {
            manager.refreshView(target);
        }
    }

    /**
     * 通知所有视图刷新（全部标记为脏，并重新发送当前栈顶）。
     * <p>
     * 用于全局数据变化后通知所有视图更新。
     */
    public void notifyRefreshAll() {
        if (manager != null) {
            manager.refreshAll();
        }
    }
}
