package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

import java.util.function.Consumer;

/**
 * 共享库存切片组件。
 * <p>
 * 从外部库存（由其他插件管理）中截取一块矩形区域，映射到当前视图的 STORAGE 格子。
 * 多个玩家可以各自在自己的视图中放置 {@code SharedInventorySlice}，指向同一个外部库存，
 * 实现多人同时操作同一个库存的片段。
 *
 * <h3>工作原理</h3>
 * <ul>
 *   <li>{@code onRender}：从外部库存读取物品，写入自己的 VirtualInventory</li>
 *   <li>{@code onStore}：玩家操作后，将变化写回外部库存，并通知其他同源切片刷新</li>
 *   <li>收到 {@code onExternalChanged}：重新从外部库存读取变化的格子，更新自己的 VirtualInventory</li>
 * </ul>
 *
 * <h3>与 {@link StorageBox} 的区别</h3>
 * <table border="1">
 * <tr><th></th><th>StorageBox</th><th>SharedInventorySlice</th></tr>
 * <tr><td>数据来源</td><td>玩家自己的 VirtualInventory</td><td>外部库存（共享）</td></tr>
 * <tr><td>多人同步</td><td>不支持（各自独立）</td><td>支持（通过 {@link SharedInventorySync}）</td></tr>
 * <tr><td>典型用途</td><td>个人背包、回收槽</td><td>战利品箱、公共仓库</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 外部库存（由你的插件创建和管理）
 * Inventory lootInventory = new ChestInventory(...);
 *
 * // 视图中使用
 * public class LootView extends InventoryView {
 *     private final Inventory externalLoot;
 *
 *     public LootView(Inventory loot) {
 *         super(3, "战利品箱");
 *         this.externalLoot = loot;
 *     }
 *
 *     @Override
 *     protected InventoryComponent buildRoot() {
 *         Panel root = new Panel(9, 3);
 *         // 截取外部库存的第 0~17 格（2行9列），显示在视图的 (0,0) 位置
 *         SharedInventorySlice lootSlice = new SharedInventorySlice(externalLoot, 0, 9, 2);
 *         lootSlice.onStore(event -> {
 *             if (event.isWithdraw()) {
 *                 event.player().sendMessage("你取走了物品");
 *             }
 *         });
 *         root.add(lootSlice, 0, 0);
 *         return root;
 *     }
 * }
 * }</pre>
 *
 * @see StorageBox
 * @see SharedInventorySync
 */
public class SharedInventorySlice extends InventoryComponent {

    /** 外部库存（由其他插件管理） */
    private final Inventory externalInventory;

    /** 在外部库存中的起始槽位（线性序号） */
    private final int externalStartSlot;

    /** 存取回调（可选） */
    private Consumer<StoreEvent> storeHandler;

    /** 渲染时记录的绝对基准行（用于计算格子绝对序号） */
    private int absBaseRow = 0;
    /** 渲染时记录的绝对基准列（用于计算格子绝对序号） */
    private int absBaseCol = 0;

    /**
     * 创建共享库存切片组件。
     *
     * @param externalInventory 外部库存
     * @param externalStartSlot 在外部库存中的起始槽位（线性序号，0-based）
     * @param width             宽度（格子数）
     * @param height            高度（格子数）
     */
    public SharedInventorySlice(Inventory externalInventory,
                                int externalStartSlot, int width, int height) {
        this.externalInventory = externalInventory;
        this.externalStartSlot = externalStartSlot;
        this.width = width;
        this.height = height;
    }

    /**
     * 设置存取回调。
     * <p>
     * 玩家在切片区域内放入/取出物品时触发。注意：回调触发时，物品变化已经写回外部库存，
     * 且其他玩家的同源切片已经被通知刷新。
     *
     * @param handler 回调函数
     * @return 当前组件（链式调用）
     */
    public SharedInventorySlice onStore(Consumer<StoreEvent> handler) {
        this.storeHandler = handler;
        return this;
    }

    /**
     * 获取外部库存实例。
     *
     * @return 外部库存
     */
    public Inventory externalInventory() {
        return externalInventory;
    }

    // -------------------- 生命周期 --------------------

    @Override
    protected void onMount() {
        // 注册到同步通知器，监听外部库存变化
        SharedInventorySync.register(this);
    }

    @Override
    protected void onUnmount() {
        // 注销，避免内存泄漏
        SharedInventorySync.unregister(this);
    }

    // -------------------- 渲染 --------------------

    @Override
    protected void onRender(RenderContext ctx) {
        this.absBaseRow = ctx.baseRow();
        this.absBaseCol = ctx.baseCol();

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                // 1. 标记为 STORAGE 类型（appearance=null，框架 syncToInventory 不覆盖）
                ctx.slot(r, c, (SlotAppearance) null, SlotType.STORAGE);

                // 2. 从外部库存读取物品，写入自己的 VirtualInventory
                //    render() 后的 syncToInventory() 对 STORAGE 格子跳过，不会覆盖此处写入的物品
                int externalSlot = externalStartSlot + r * width + c;
                Item item = externalInventory.getItem(externalSlot);
                int absSlot = absSlot(r, c);
                if (view != null) {
                    view.setSlotItem(absSlot, item != null ? item : Item.get(Item.AIR), false);
                }
            }
        }
    }

    // -------------------- 存取事件 --------------------

    @Override
    protected void onStore(StoreEvent event) {
        // 1. 计算外部库存中的槽位
        int externalSlot = toExternalSlot(event.slot());

        // 2. 写回外部库存
        Item newItem = event.targetItem();
        externalInventory.setItem(externalSlot, newItem != null ? newItem : Item.get(Item.AIR));

        // 3. 通知其他同源切片刷新（排除自己，避免重复处理）
        SharedInventorySync.notifyChanged(externalInventory, externalSlot, this);

        // 4. 触发用户回调
        if (storeHandler != null) {
            storeHandler.accept(event);
        }
    }

    // -------------------- 外部变更通知（由 SharedInventorySync 调用） --------------------

    /**
     * 外部库存发生变化时，由 {@link SharedInventorySync} 调用。
     * <p>
     * 重新从外部库存读取变化的格子，更新自己的 VirtualInventory 并发送给玩家。
     * <p>
     * 如果变化的槽位不在自己的切片范围内，则忽略。
     *
     * @param externalSlot 外部库存中变化的槽位
     */
    void onExternalChanged(int externalSlot) {
        if (view == null) return;

        // 检查该槽位是否在自己的切片范围内
        int relSlot = externalSlot - externalStartSlot;
        if (relSlot < 0 || relSlot >= width * height) return;

        // 计算自己 VirtualInventory 中的绝对槽位
        int r = relSlot / width;
        int c = relSlot % width;
        int absSlot = absSlot(r, c);

        // 从外部库存读取最新物品
        Item item = externalInventory.getItem(externalSlot);
        // 更新自己的 VirtualInventory 并发送给玩家（send=true）
        view.setSlotItem(absSlot, item != null ? item : Item.get(Item.AIR), true);
    }

    // -------------------- 工具方法 --------------------

    /**
     * 计算相对坐标对应的绝对格子序号。
     *
     * @param relRow 相对行
     * @param relCol 相对列
     * @return 绝对格子序号
     */
    private int absSlot(int relRow, int relCol) {
        return (absBaseRow + relRow) * InventoryView.COLS + (absBaseCol + relCol);
    }

    /**
     * 将视图中的绝对格子序号转换为外部库存中的槽位。
     *
     * @param absSlot 绝对格子序号
     * @return 外部库存中的槽位
     */
    private int toExternalSlot(int absSlot) {
        int relRow = (absSlot / InventoryView.COLS) - absBaseRow;
        int relCol = (absSlot % InventoryView.COLS) - absBaseCol;
        return externalStartSlot + relRow * width + relCol;
    }
}
