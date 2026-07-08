package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import cn.nukkit.form.handler.FormResponseHandler;
import io.github.JiangHu.jframe.form.data.ViewDataBus;
import io.github.JiangHu.jframe.form.response.FormResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单玩家视图管理器。
 * <p>
 * 接管并管理某一个玩家的界面显示。内部维护一个 {@link Deque 视图栈}，
 * 栈顶元素即为当前向玩家展示的界面。通过压栈 / 出栈操作，
 * 可实现「进入子菜单」「返回上一级」等界面导航逻辑。
 * <p>
 * <strong>新架构相对旧版（SinglePlayerViewManager）的增强：</strong>
 * <ul>
 *   <li><strong>数据总线</strong> —— 内置 {@link ViewDataBus}，支持窗口间数据塞入与通知更新</li>
 *   <li><strong>栈的增删改查</strong> —— 除 push/pop 外，新增 {@link #insert}、{@link #remove}、
 *       {@link #replace}、{@link #snapshot}、{@link #clear} 等操作</li>
 *   <li><strong>智能刷新</strong> —— 玩家提交后，仅当当前栈顶仍是该视图时才自动刷新，
 *       使导航（goBack/replace/close）与刷新互不干扰</li>
 *   <li><strong>生命周期回调</strong> —— 自动调用视图的 {@link FormView#onShow} / {@link FormView#onClose}</li>
 * </ul>
 * 通常由 {@link ViewAPI} 为每位玩家创建并管理一个实例。
 *
 * @see FormView
 * @see ViewDataBus
 * @see ViewAPI
 */
public class ViewManager {

    /** 该管理器所属的玩家。 */
    private final Player player;

    /** 视图栈：栈底是最早打开的界面，栈顶是当前显示的界面。 */
    private final Deque<FormView> views = new ArrayDeque<>();

    /** 数据总线：该玩家所有视图共享的数据通道。 */
    private final ViewDataBus dataBus = new ViewDataBus();

    /**
     * 是否正处于「玩家回应处理」过程中。
     * <p>
     * 为 {@code true} 时，{@link #send()} 不会立即发送界面（直接返回），
     * 由 {@link #handleResponse} 在处理完毕后统一调用 {@link #doSend()} 发送一次栈顶。
     * 这样回调中无论进行何种导航（goBack / addStack / replace / refresh），
     * 最终都只会发送一次，避免重复注册响应处理器。
     */
    private boolean handlingResponse = false;

    /**
     * 创建一个绑定指定玩家的视图管理器。
     *
     * @param player 所属玩家
     */
    public ViewManager(Player player) {
        this.player = player;
    }

    /** 所属玩家。 */
    public Player player() {
        return player;
    }

    /** 数据总线。 */
    public ViewDataBus dataBus() {
        return dataBus;
    }

    // -------------------- 导航：压栈 / 出栈 --------------------

    /**
     * 将视图压入栈顶，并为其注入当前管理器引用。
     * <p>
     * 注意：此方法仅修改栈结构，不会向玩家发送界面。
     * 如需同时显示，请使用 {@link #pushAndSend(FormView)}。
     *
     * @param view 要压入的视图
     */
    public void push(FormView view) {
        view.bind(this);
        views.push(view);
    }

    /**
     * 压入视图并向其传递一次性数据（触发其 {@link FormView#onData}）。
     *
     * @param view 要压入的视图
     * @param data 传递给视图的数据
     */
    public void push(FormView view, Object data) {
        push(view);
        view.receiveData(data);
    }

    /**
     * 压入一个新视图并立即向玩家发送（显示）。
     *
     * @param view 要打开的视图
     */
    public void pushAndSend(FormView view) {
        push(view);
        send();
    }

    /**
     * 弹出栈顶视图并触发其 {@link FormView#onClose}。
     *
     * @return 被弹出的栈顶视图，栈空时为 {@code null}
     */
    public FormView pop() {
        if (views.isEmpty()) return null;
        FormView view = views.pop();
        view.handleClose();
        return view;
    }

    /**
     * 返回上一级界面（弹出当前视图，并重新发送栈顶的父界面）。
     * <p>
     * 弹出当前视图后，会自动调用 {@link #send()} 重新显示新的栈顶（即上一级界面），
     * 因此调用方无需手动发送。
     * <p>
     * 若当前已是栈底（栈中仅剩一个视图），则不弹出，避免清空整个栈。
     */
    public void goBack() {
        if (views.size() > 1) {
            pop();
            send();
        }
    }

    // -------------------- 栈的增删改查 --------------------

    /** 当前栈顶视图（即正在显示的界面），栈空时为 {@code null}。 */
    public FormView current() {
        return views.peek();
    }

    /** 栈中视图数量。 */
    public int size() {
        return views.size();
    }

    /** 栈是否为空。 */
    public boolean isEmpty() {
        return views.isEmpty();
    }

    /**
     * 获取栈的快照（不可变列表，栈底在前、栈顶在后）。
     * <p>
     * 用于调试或批量操作。
     *
     * @return 视图列表的副本
     */
    public List<FormView> snapshot() {
        List<FormView> list = new ArrayList<>(views);
        java.util.Collections.reverse(list);
        return List.copyOf(list);
    }

    /**
     * 在指定位置（从栈底起算，0 = 栈底）插入一个视图。
     * <p>
     * 用于在导航中途插入一个中间界面。
     *
     * @param index 插入位置（0 ~ size）
     * @param view  要插入的视图
     */
    public void insert(int index, FormView view) {
        if (index < 0 || index > views.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + views.size());
        }
        view.bind(this);
        if (index == views.size()) {
            views.push(view);
            return;
        }
        // 转为列表操作（栈操作不直接支持中间插入）
        List<FormView> tmp = new ArrayList<>(views);
        java.util.Collections.reverse(tmp); // 栈底在前
        tmp.add(index, view);
        views.clear();
        for (int i = tmp.size() - 1; i >= 0; i--) {
            views.push(tmp.get(i));
        }
    }

    /**
     * 移除指定位置（从栈底起算）的视图，并触发其 {@link FormView#onClose}。
     *
     * @param index 要移除的位置（0 ~ size-1）
     * @return 被移除的视图
     */
    public FormView remove(int index) {
        if (index < 0 || index >= views.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + views.size());
        }
        List<FormView> tmp = new ArrayList<>(views);
        java.util.Collections.reverse(tmp); // 栈底在前
        FormView removed = tmp.remove(index);
        views.clear();
        for (int i = tmp.size() - 1; i >= 0; i--) {
            views.push(tmp.get(i));
        }
        removed.handleClose();
        return removed;
    }

    /**
     * 用新视图替换栈中的旧视图（同层替换）。
     * <p>
     * 典型场景：在同一层级内切换界面，且不希望保留返回路径。
     *
     * @param oldView 要被替换的视图（必须在栈中）
     * @param newView 替换为的新视图
     */
    public void replace(FormView oldView, FormView newView) {
        List<FormView> tmp = new ArrayList<>(views);
        java.util.Collections.reverse(tmp); // 栈底在前
        int idx = tmp.indexOf(oldView);
        if (idx < 0) return;
        oldView.handleClose();
        newView.bind(this);
        tmp.set(idx, newView);
        views.clear();
        for (int i = tmp.size() - 1; i >= 0; i--) {
            views.push(tmp.get(i));
        }
    }

    /**
     * 用新视图替换栈中的旧视图，并立即发送新视图。
     * <p>
     * 替换后保持父视图关系不变：新视图在栈中的位置与旧视图相同，
     * 其下方的父视图不受影响。此方法由 {@link FormView#replaceThis} 调用。
     *
     * @param oldView 要被替换的视图（必须在栈中）
     * @param newView 替换为的新视图
     */
    public void replaceAndSend(FormView oldView, FormView newView) {
        replace(oldView, newView);
        send();
    }

    /**
     * 清空整个视图栈，依次触发每个视图的 {@link FormView#onClose}。
     */
    public void clear() {
        while (!views.isEmpty()) {
            views.pop().handleClose();
        }
    }

    /**
     * 清空当前视图栈，以新视图作为根视图重新开始。
     * <p>
     * 等价于先 {@link #clear()}（触发所有旧视图的 {@link FormView#onClose}），
     * 再以 {@code newRoot} 为唯一视图重新建立栈并立即发送。
     * <p>
     * 典型场景：完成某个流程后「回到首页」、登录后进入主界面等
     * 需要彻底重置导航路径的场景。
     *
     * @param newRoot 新的根视图
     */
    public void restartWith(FormView newRoot) {
        clear();
        push(newRoot);
        send();
    }

    // -------------------- 发送与响应处理 --------------------

    /**
     * 向玩家发送（显示）当前栈顶界面。
     * <p>
     * 若当前正处于玩家回应处理过程中（{@link #handleResponse}），本方法不会立即发送
     * （直接返回），待回应处理完毕后由 {@link #handleResponse} 统一调用 {@link #doSend()}
     * 发送一次。这样回调中无论进行何种导航（goBack / addStack / replace / refresh），
     * 最终都只会发送一次栈顶界面。
     * <p>
     * 非回应上下文（如命令触发、异步通知）调用本方法会立即发送。
     * 实际发送逻辑见 {@link #doSend()}。
     */
    public void send() {
        if (handlingResponse) {
            return;
        }
        doSend();
    }

    /**
     * 实际执行界面发送。
     * <p>
     * 流程：重新构建表单 → {@link FormView#onShow} → 转换为原生窗口 →
     * 注册响应处理器 → {@link Player#showFormWindow}。
     */
    private void doSend() {
        if (views.isEmpty()) return;
        FormView view = views.peek();

        // 根据构建策略决定是否重新构建（ALWAYS 每次重建，ON_DEMAND 仅在脏时重建）
        if (view.shouldRebuild()) {
            view.rebuild();
        }
        view.onShow();

        // 转换为原生窗口（每次均为新对象，供后续读取响应）
        cn.nukkit.form.window.FormWindow window = view.form().toNukkit();

        // 注册响应处理器：玩家提交 / 关闭后由本管理器统一处理
        window.addHandler(FormResponseHandler.withoutPlayer(id -> handleResponse(view)));

        player.showFormWindow(window);
    }

    /**
     * 重新构建并刷新当前栈顶界面（不改变栈结构）。
     */
    public void refreshCurrent() {
        send();
    }

    /**
     * 处理玩家的表单响应（提交或关闭）。
     * <p>
     * <strong>统一重发机制：</strong>无论玩家是提交表单还是直接关闭窗口，
     * 处理完回调后，只要视图栈不空，就重新发送当前栈顶界面。
     * 回调过程中所有 {@link #send()} 调用都会被延迟（{@link #handlingResponse}），
     * 最终至多实际发送一次，避免重复注册响应处理器。
     * <p>
     * 程序员通过在回调中操作视图栈来控制导航：
     * <ul>
     *   <li>{@link FormView#goBack} —— 弹出当前视图，重发其父界面</li>
     *   <li>{@link FormView#addStack} —— 压入新视图，重发新视图</li>
     *   <li>{@link FormView#close} —— 清空整个栈，栈空则不再发送任何界面</li>
     *   <li>什么都不做 —— 栈不变，重发当前栈顶（关闭窗口时即「窗口弹回」）</li>
     * </ul>
     *
     * @param view 触发本次响应的视图
     */
    private void handleResponse(FormView view) {
        handlingResponse = true;
        try {
            if (view.form().wasClosed()) {
                // 玩家直接关闭窗口（点 X）—— 交给视图决定后续行为
                // 默认（未弹出当前视图）会在方法末尾重发栈顶；若想真正关闭，
                // 程序员需在 onCloseAttempt 中调用 goBack / close 弹出当前视图
                view.handleCloseAttempt();
            } else {
                // 构造统一结果并分发（按钮级回调在此触发，可能执行导航）
                FormResult result = view.form().buildResult(player);
                view.handleResult(result);
            }
        } finally {
            handlingResponse = false;
        }

        // 统一重发：回应处理后，只要栈不空，就重新发送当前栈顶
        // （只有栈空——如回调中 close() 清空了栈——才没有界面显示）
        if (!isEmpty()) {
            doSend();
        }
    }

    // -------------------- 批量刷新通知 --------------------

    /**
     * 刷新指定视图（标记为脏，若该视图是当前栈顶则立即重新发送）。
     * <p>
     * 用于通知某个特定视图其依赖的数据已变化，需要重建。
     *
     * @param view 要刷新的视图（必须在栈中）
     */
    public void refreshView(FormView view) {
        if (views.contains(view)) {
            view.markDirty();
            if (current() == view) {
                send();
            }
        }
    }

    /**
     * 刷新栈中所有视图（全部标记为脏，并重新发送当前栈顶）。
     * <p>
     * 用于全局数据变化后通知所有视图更新。
     */
    public void refreshAll() {
        for (FormView v : views) {
            v.markDirty();
        }
        send();
    }
}
