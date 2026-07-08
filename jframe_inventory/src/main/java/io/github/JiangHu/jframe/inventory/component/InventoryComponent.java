package io.github.JiangHu.jframe.inventory.component;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.model.event.ClickEvent;
import io.github.JiangHu.jframe.inventory.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.view.InventoryView;
import io.github.JiangHu.jframe.inventory.view.RenderContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 箱子界面组件基类（类似 QWidget）。
 * <p>
 * 所有界面元素都是 {@code InventoryComponent} 的子类。组件支持<strong>嵌套</strong>：
 * 一个组件可以包含多个子组件，形成组件树。每个组件负责渲染自己区域内的格子，
 * 并响应格子上的交互事件。
 *
 * <h3>核心属性</h3>
 * <table border="1">
 * <tr><th>属性</th><th>说明</th></tr>
 * <tr><td>{@code row, col}</td><td>在父容器中的相对位置</td></tr>
 * <tr><td>{@code width, height}</td><td>占据的格子数（宽 × 高）</td></tr>
 * <tr><td>{@code layer}</td><td>图层（默认 0，越大越在上层）</td></tr>
 * <tr><td>{@code visible}</td><td>是否可见（false 则跳过渲染与事件）</td></tr>
 * </table>
 *
 * <h3>生命周期回调</h3>
 * <p>
 * 子类可重写以下方法：
 * <ul>
 *   <li>{@link #onRender} —— 渲染阶段，用 {@code ctx.slot()} 填充格子（核心方法）</li>
 *   <li>{@link #onClick} —— 点击 BUTTON 格子时触发</li>
 *   <li>{@link #onStore} —— STORAGE 格子物品变化时触发</li>
 *   <li>{@link #onMount} —— 挂载到箱子时初始化</li>
 *   <li>{@link #onUnmount} —— 卸载时清理</li>
 * </ul>
 *
 * @see RenderContext
 * @see InventoryView
 */
public abstract class InventoryComponent {

    // -------------------- 几何属性 --------------------

    /** 在父容器中的相对行位置 */
    public int row = 0;
    /** 在父容器中的相对列位置 */
    public int col = 0;
    /** 占据的宽度（格子数） */
    public int width = 1;
    /** 占据的高度（格子数） */
    public int height = 1;
    /** 图层（默认 0，越大越在上层） */
    public int layer = 0;

    // -------------------- 关系与状态 --------------------

    /** 父组件 */
    protected InventoryComponent parent;
    /** 子组件列表 */
    private final List<InventoryComponent> children = new ArrayList<>();
    /** 是否可见 */
    protected boolean visible = true;

    /** 所属视图（挂载后由框架注入） */
    protected InventoryView view;

    /** 组件名称（用于 {@link #findByName 查找}，可选） */
    private String name;

    // -------------------- 子类可重写的生命周期方法 --------------------

    /**
     * 渲染阶段回调（核心方法）。
     * <p>
     * 子类在此方法中用 {@code ctx.slot(relRow, relCol, appearance, type)} 填充格子。
     *
     * @param ctx 渲染上下文（已偏移到当前组件位置）
     */
    protected void onRender(RenderContext ctx) {
    }

    /**
     * 点击 BUTTON 格子时触发。
     *
     * @param event 点击事件
     */
    protected void onClick(ClickEvent event) {
    }

    /**
     * STORAGE 格子物品变化时触发。
     *
     * @param event 存取事件
     */
    protected void onStore(StoreEvent event) {
    }

    /**
     * 挂载到箱子时调用（初始化）。
     */
    protected void onMount() {
    }

    /**
     * 卸载时调用（清理）。
     */
    protected void onUnmount() {
    }

    // -------------------- 渲染入口（框架内部调用） --------------------

    /**
     * 渲染当前组件及其子组件树。
     * <p>
     * 由 {@link InventoryView} 在渲染阶段调用，开发者一般无需直接调用。
     *
     * @param parentCtx 父组件的渲染上下文
     */
    public final void render(RenderContext parentCtx) {
        if (!visible) {
            return;
        }
        // 偏移到当前组件的位置
        RenderContext ctx = parentCtx.translate(row, col, this);
        // 子类渲染
        onRender(ctx);
        // 子组件按 layer 从低到高排序后渲染
        List<InventoryComponent> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparingInt(c -> c.layer));
        for (InventoryComponent child : sorted) {
            child.render(ctx);
        }
    }

    // -------------------- 事件分发（框架内部调用） --------------------

    /**
     * 分发点击事件到当前组件。
     *
     * @param slot   格子序号
     * @param player 点击的玩家
     * @param item   格子上的物品
     */
    public final void dispatchClick(int slot, Player player, Item item) {
        onClick(new ClickEvent(player, slot, item));
    }

    /**
     * 分发存取事件到当前组件。
     *
     * @param slot       格子序号
     * @param player     操作的玩家
     * @param sourceItem 操作前的物品
     * @param targetItem 操作后的物品
     */
    public final void dispatchStore(int slot, Player player, Item sourceItem, Item targetItem) {
        onStore(new StoreEvent(player, slot, sourceItem, targetItem));
    }

    // -------------------- 子组件管理 --------------------

    /**
     * 添加子组件（位置由当前布局管理器决定或之前设置的 row/col）。
     *
     * @param child 子组件
     * @return 当前组件（链式调用）
     */
    public InventoryComponent add(InventoryComponent child) {
        child.parent = this;
        child.view = this.view;
        children.add(child);
        if (view != null) {
            child.mountTree(view);
        }
        return this;
    }

    /**
     * 添加子组件并指定位置（手动布局）。
     *
     * @param child 子组件
     * @param row   相对行
     * @param col   相对列
     * @return 当前组件（链式调用）
     */
    public InventoryComponent add(InventoryComponent child, int row, int col) {
        child.row = row;
        child.col = col;
        return add(child);
    }

    /**
     * 移除子组件。
     *
     * @param child 要移除的子组件
     */
    public void remove(InventoryComponent child) {
        if (children.remove(child)) {
            child.parent = null;
            child.unmountTree();
        }
    }

    /**
     * 清空所有子组件。
     */
    public void clearChildren() {
        for (InventoryComponent child : new ArrayList<>(children)) {
            remove(child);
        }
    }

    /**
     * 获取子组件列表（只读视图）。
     *
     * @return 子组件列表
     */
    public List<InventoryComponent> children() {
        return List.copyOf(children);
    }

    // -------------------- 挂载/卸载（框架内部） --------------------

    /**
     * 挂载组件树到视图。
     *
     * @param view 所属视图
     */
    public void mountTree(InventoryView view) {
        this.view = view;
        onMount();
        for (InventoryComponent child : children) {
            child.mountTree(view);
        }
    }

    /**
     * 卸载组件树。
     */
    public void unmountTree() {
        for (InventoryComponent child : children) {
            child.unmountTree();
        }
        onUnmount();
        this.view = null;
    }

    // -------------------- 便捷方法 --------------------

    /**
     * 获取当前查看界面的玩家。
     *
     * @return 玩家，未挂载时为 null
     */
    protected Player viewer() {
        return view != null ? view.viewer() : null;
    }

    /**
     * 请求重绘（增量更新当前视图）。
     */
    protected void repaint() {
        if (view != null) {
            view.repaint();
        }
    }

    // -------------------- 组件树导航与查找 --------------------

    /**
     * 获取父组件。
     *
     * @return 父组件，如果是根组件则返回 null
     */
    public InventoryComponent parent() {
        return parent;
    }

    /**
     * 获取根组件（向上遍历到最顶层）。
     *
     * @return 根组件（如果自身就是根组件则返回自身）
     */
    public InventoryComponent root() {
        InventoryComponent r = this;
        while (r.parent != null) {
            r = r.parent;
        }
        return r;
    }

    /**
     * 在整个组件树中按名称查找第一个匹配的组件。
     * <p>
     * 从根组件开始深度优先搜索。组件需通过 {@link #name(String)} 设置名称后才能被查找到。
     *
     * @param name 组件名称
     * @return 匹配的组件，未找到返回 null
     */
    public InventoryComponent findByName(String name) {
        return root().doFindByName(name);
    }

    /**
     * 在整个组件树中按名称查找第一个匹配的组件，并断言其类型。
     *
     * @param name 组件名称
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配且类型兼容的组件，未找到或类型不匹配返回 null
     */
    public <T extends InventoryComponent> T findByName(String name, Class<T> type) {
        InventoryComponent found = findByName(name);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    /**
     * 在整个组件树中按类型查找第一个匹配的组件。
     *
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配的组件，未找到返回 null
     */
    public <T extends InventoryComponent> T findByType(Class<T> type) {
        return root().doFindByType(type);
    }

    /**
     * 在整个组件树中按名称查找所有匹配的组件。
     *
     * @param name 组件名称
     * @return 匹配的组件列表（可能为空）
     */
    public List<InventoryComponent> findAllByName(String name) {
        List<InventoryComponent> result = new ArrayList<>();
        root().doFindAllByName(name, result);
        return result;
    }

    /**
     * 在整个组件树中按类型查找所有匹配的组件。
     *
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配的组件列表（可能为空）
     */
    public <T extends InventoryComponent> List<T> findAllByType(Class<T> type) {
        List<T> result = new ArrayList<>();
        root().doFindAllByType(type, result);
        return result;
    }

    // ---- 内部递归实现（从当前节点开始搜索子树） ----

    private InventoryComponent doFindByName(String name) {
        if (name != null && name.equals(this.name)) {
            return this;
        }
        for (InventoryComponent child : children) {
            InventoryComponent found = child.doFindByName(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private <T extends InventoryComponent> T doFindByType(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        for (InventoryComponent child : children) {
            T found = child.doFindByType(type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void doFindAllByName(String name, List<InventoryComponent> result) {
        if (name != null && name.equals(this.name)) {
            result.add(this);
        }
        for (InventoryComponent child : children) {
            child.doFindAllByName(name, result);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends InventoryComponent> void doFindAllByType(Class<T> type, List<T> result) {
        if (type.isInstance(this)) {
            result.add((T) this);
        }
        for (InventoryComponent child : children) {
            child.doFindAllByType(type, result);
        }
    }

    // -------------------- Getter / Setter --------------------

    /** 宽度 */
    public int getWidth() {
        return width;
    }

    /** 高度 */
    public int getHeight() {
        return height;
    }

    /** 设置尺寸 */
    public InventoryComponent size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** 设置位置 */
    public InventoryComponent position(int row, int col) {
        this.row = row;
        this.col = col;
        return this;
    }

    /** 设置图层 */
    public InventoryComponent layer(int layer) {
        this.layer = layer;
        return this;
    }

    /** 设置可见性 */
    public InventoryComponent visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** 是否可见 */
    public boolean isVisible() {
        return visible;
    }

    /** 所属视图 */
    public InventoryView view() {
        return view;
    }

    /** 组件名称（用于查找） */
    public String name() {
        return name;
    }

    /** 设置组件名称（用于 {@link #findByName 查找}，链式调用） */
    public InventoryComponent name(String name) {
        this.name = name;
        return this;
    }
}
