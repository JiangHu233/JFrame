package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 存储格组件。
 * <p>
 * 将指定区域内的格子标记为 {@link SlotType#STORAGE}，允许玩家自由放入/取出物品。
 * 物品变化时触发 {@code onStore} 回调。
 * <p>
 * <strong>注意</strong>：STORAGE 格子不设置框架外观（appearance 为 null），
 * 因此不会覆盖玩家放入的物品。
 *
 * <h3>物品加载与导出（持久化）</h3>
 * <p>
 * StorageBox 支持将存储格内的物品内容导出（{@link #exportItems()}）与加载
 * （{@link #loadItems(Item[])}），便于配合 {@code jframe_data} 等持久化方案
 * 在玩家关闭/打开界面时保存与恢复物品。
 *
 * <pre>{@code
 * StorageBox box = new StorageBox(3, 1);  // 3 格宽、1 格高的存储区
 * box.onStore(event -> {
 *     if (event.isDeposit()) {
 *         player.sendMessage("你放入了物品！");
 *     }
 * });
 *
 * // 视图打开后恢复物品（通常在 onOpen 中调用）
 * box.loadItems(savedItems);
 *
 * // 视图关闭前保存物品（通常在 onClose 中调用）
 * Item[] snapshot = box.exportItems();
 * }</pre>
 *
 * <p><strong>时序要求</strong>：{@code loadItems} / {@code exportItems} 必须在视图
 * 已打开（{@link InventoryView#onOpen} 之后）时调用，此时底层库存已就绪。
 * 视图未打开时 {@code exportItems} 返回全空气数组，{@code loadItems} 不执行任何操作。
 *
 * @see SlotType#STORAGE
 */
public class StorageBox extends InventoryComponent {

    /** 存取回调（可选） */
    private Consumer<StoreEvent> storeHandler;

    /** 渲染时记录的绝对基准行（用于计算格子绝对序号，供加载/导出使用） */
    private int absBaseRow = 0;
    /** 渲染时记录的绝对基准列（用于计算格子绝对序号，供加载/导出使用） */
    private int absBaseCol = 0;

    /**
     * 创建存储格组件。
     *
     * @param width  宽度（格子数）
     * @param height 高度（格子数）
     */
    public StorageBox(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * 设置存取回调。
     *
     * @param handler 回调函数
     * @return 当前组件（链式调用）
     */
    public StorageBox onStore(Consumer<StoreEvent> handler) {
        this.storeHandler = handler;
        return this;
    }

    /**
     * 导出存储格中的所有物品（用于持久化保存）。
     * <p>
     * 按行优先顺序返回（下标 {@code relRow * width + relCol}），长度恒为
     * {@code width * height}。空格子返回空气物品（{@link Item#AIR}）。
     * <p>
     * 返回的是当前物品的快照（克隆），修改返回数组或其中的物品不会影响库存内部状态。
     *
     * @return 物品快照数组，视图未打开时返回全空气数组
     */
    public Item[] exportItems() {
        Item[] result = new Item[width * height];
        if (view == null) {
            Arrays.fill(result, Item.get(Item.AIR));
            return result;
        }
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                Item item = view.slotItem(absIndex(r, c));
                result[r * width + c] = (item != null) ? item.clone() : Item.get(Item.AIR);
            }
        }
        return result;
    }

    /**
     * 从持久化数据加载物品到存储格（用于恢复物品）。
     * <p>
     * 按行优先顺序填入（下标 {@code relRow * width + relCol}）：数组长度不足时剩余
     * 格子保持原样，超出容量的物品被忽略。空气物品（{@link Item#AIR}）会清空
     * 对应格子。设置后会同步给在线查看的玩家。
     * <p>
     * 必须在视图已打开后调用，否则不执行任何操作。
     *
     * @param items 物品数组，{@code null} 时不执行任何操作
     */
    public void loadItems(Item[] items) {
        if (view == null || items == null) {
            return;
        }
        int total = width * height;
        for (int i = 0; i < total && i < items.length; i++) {
            int r = i / width;
            int c = i % width;
            view.setSlotItem(absIndex(r, c), items[i], true);
        }
    }

    /**
     * 计算相对坐标对应的绝对格子序号。
     *
     * @param relRow 相对行
     * @param relCol 相对列
     * @return 绝对格子序号
     */
    private int absIndex(int relRow, int relCol) {
        return (absBaseRow + relRow) * InventoryView.COLS + (absBaseCol + relCol);
    }

    @Override
    protected void onRender(RenderContext ctx) {
        // 记录当前组件在箱子中的绝对基准坐标（供 exportItems/loadItems 计算格子序号）
        this.absBaseRow = ctx.baseRow();
        this.absBaseCol = ctx.baseCol();
        // 将区域内所有格子标记为 STORAGE 类型
        // appearance 传 null：不覆盖玩家放入的物品
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                ctx.slot(r, c, (SlotAppearance) null, SlotType.STORAGE);
            }
        }
    }

    @Override
    protected void onStore(StoreEvent event) {
        if (storeHandler != null) {
            storeHandler.accept(event);
        }
    }
}
