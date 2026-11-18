package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;
import io.github.JiangHu.jframe.inventory.ui.model.event.BeforeStoreEvent;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * （{@link #loadItems(Item[])}），便于配合 {@code jframe_file} 等持久化方案
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
 * <h3>存取守卫（继承自 InventoryComponent 的 BeforeStoreEvent 便捷层）</h3>
 * <p>
 * StorageBox 在基类 {@code onBeforeStore} 钩子之上提供三层便捷 API，
 * 按执行顺序：
 * <ol>
 *   <li>{@code filter(Predicate<Item> [, denyMessage])} — 放入校验：
 *       有物品进入本格时测试进入的物品（放入/换入/并入堆叠）</li>
 *   <li>{@code withdrawFilter(Predicate<Item> [, denyMessage])} — 取出校验：
 *       有物品离开本格时测试离开的物品（取出/换出/右键拿一半）</li>
 *   <li>{@code beforeStore(Consumer<BeforeStoreEvent>)} — 高级守卫：兜底一切场景
 *       （限量放入、条件禁取等复杂策略）</li>
 * </ol>
 * 任一层否决（{@code cancel}）则整笔交易取消，客户端物品弹回，可选发送提示消息；
 * 未设置任何守卫时行为与无守卫完全一致。
 *
 * <pre>{@code
 * // 白名单回收槽：只收钻石，其他物品弹回并提示
 * box.filter(item -> item.getId() == Item.DIAMOND, "§c此处仅回收钻石");
 *
 * // 押金槽：付款前禁止取出
 * box.withdrawFilter(item -> paid, "§e需先完成付款");
 *
 * // 限量放入（合计不超过 64 个）
 * box.beforeStore(e -> {
 *     if (e.isEntering()) {
 *         int total = Arrays.stream(box.exportItems()).mapToInt(Item::getCount).sum();
 *         if (total + e.targetItem().getCount() > 64) {
 *             e.cancel("§c押金槽最多存放 64 个");
 *         }
 *     }
 * });
 * }</pre>
 *
 * <p><strong>时序要求</strong>：{@code loadItems} / {@code exportItems} 必须在视图
 * 已打开（{@link InventoryView#onOpen} 之后）时调用，此时底层库存已就绪。
 * 视图未打开时 {@code exportItems} 返回全空气数组，{@code loadItems} 不执行任何操作。
 *
 * <h3>物品退还</h3>
 * <p>
 * {@link #returnItems()} 将存储格内所有物品退还到玩家背包，背包满时剩余物品
 * 掉落到玩家脚下，随后清空存储格。{@link #returnOnClose(boolean)} 开关开启后，
 * 视图关闭时（{@code onUnmount} 阶段）自动执行退还，确保物品不会因界面关闭而丢失。
 *
 * <pre>{@code
 * StorageBox box = new StorageBox(3, 1);
 * box.returnOnClose(true);  // 关闭界面时自动退还物品到玩家背包
 *
 * // 也可手动退还（如点击"提取全部"按钮）
 * Button extract = new Button(...);
 * extract.onClick(click -> {
 *     Item[] returned = box.returnItems();  // 退还并清空存储格
 * });
 * }</pre>
 *
 * @see SlotType#STORAGE
 */
public class StorageBox extends InventoryComponent {

    /** 存取回调（可选） */
    private Consumer<StoreEvent> storeHandler;

    /** 放入校验过滤器（可选）：有物品进入本格（放入/换入/并入堆叠）时测试进入的物品，未通过则否决 */
    private Predicate<Item> depositFilter;

    /** 放入校验拒绝提示（可选） */
    private String filterDenyMessage;

    /** 取出校验过滤器（可选）：有物品离开本格（取出/换出/右键拿一半）时测试离开的物品，未通过则否决 */
    private Predicate<Item> withdrawFilter;

    /** 取出校验拒绝提示（可选） */
    private String withdrawDenyMessage;

    /** 高级存取守卫回调（可选，在 filter / withdrawFilter 之后执行，可覆盖一切场景） */
    private Consumer<BeforeStoreEvent> beforeStoreHandler;

    /** 关闭界面时是否自动退还物品到玩家背包 */
    private boolean returnOnClose = false;

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

    // -------------------- 存取守卫（BeforeStoreEvent 便捷层） --------------------

    /**
     * 设置放入校验过滤器（无提示）。
     * <p>
     * 有物品进入本格（放入 / 换入 / 并入堆叠）时测试<b>进入的物品</b>（targetItem），
     * 未通过则否决整笔交易，物品弹回。取出不受影响。
     *
     * @param filter 过滤器（物品是否允许放入），传 {@code null} 清除
     * @return 当前组件（链式调用）
     */
    public StorageBox filter(Predicate<Item> filter) {
        return filter(filter, null);
    }

    /**
     * 设置放入校验过滤器（带拒绝提示）。
     *
     * @param filter      过滤器（物品是否允许放入），传 {@code null} 清除
     * @param denyMessage 否决时发送给玩家的提示消息
     * @return 当前组件（链式调用）
     */
    public StorageBox filter(Predicate<Item> filter, String denyMessage) {
        this.depositFilter = filter;
        this.filterDenyMessage = denyMessage;
        return this;
    }

    /**
     * 设置取出校验过滤器（无提示）。
     * <p>
     * 有物品离开本格（取出 / 换出 / 右键拿一半）时测试<b>离开的物品</b>（sourceItem），
     * 未通过则否决整笔交易，物品弹回。放入不受影响。
     *
     * @param filter 过滤器（物品是否允许取出），传 {@code null} 清除
     * @return 当前组件（链式调用）
     */
    public StorageBox withdrawFilter(Predicate<Item> filter) {
        return withdrawFilter(filter, null);
    }

    /**
     * 设置取出校验过滤器（带拒绝提示）。
     *
     * @param filter      过滤器（物品是否允许取出），传 {@code null} 清除
     * @param denyMessage 否决时发送给玩家的提示消息
     * @return 当前组件（链式调用）
     */
    public StorageBox withdrawFilter(Predicate<Item> filter, String denyMessage) {
        this.withdrawFilter = filter;
        this.withdrawDenyMessage = denyMessage;
        return this;
    }

    /**
     * 设置高级存取守卫回调。
     * <p>
     * 在 filter / withdrawFilter 之后执行，可覆盖一切存取场景
     * （包括交换、数量变化等），适合实现限量放入、条件禁取等复杂策略。
     *
     * @param handler 守卫回调（收到 {@link BeforeStoreEvent}，可取消）
     * @return 当前组件（链式调用）
     */
    public StorageBox beforeStore(Consumer<BeforeStoreEvent> handler) {
        this.beforeStoreHandler = handler;
        return this;
    }

    /**
     * 设置关闭界面时是否自动退还物品到玩家背包。
     * <p>
     * 开启后，当视图关闭（玩家关箱子或调用 {@code close()}）时，
     * 自动调用 {@link #returnItems()} 将存储格内物品退还给玩家。
     * 默认关闭（{@code false}），不影响原有行为。
     *
     * @param enabled 是否自动退还
     * @return 当前组件（链式调用）
     */
    public StorageBox returnOnClose(boolean enabled) {
        this.returnOnClose = enabled;
        return this;
    }

    /**
     * 查询是否启用了关闭时自动退还。
     *
     * @return 是否自动退还
     */
    public boolean isReturnOnClose() {
        return returnOnClose;
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
     * 将存储格内所有物品退还到玩家背包，并清空存储格。
     * <p>
     * 退还逻辑：
     * <ol>
     *   <li>导出当前所有物品快照（{@link #exportItems()}）</li>
     *   <li>将非空物品添加到玩家背包（{@code player.getInventory().addItem()}）</li>
     *   <li>背包满时，剩余物品掉落到玩家脚下（{@code level.dropItem()}）</li>
     *   <li>清空存储格（{@link #loadItems(Item[])} 填入全空气）</li>
     * </ol>
     * <p>
     * 必须在视图已打开后调用。视图未打开或无查看玩家时，返回全空气数组且不执行退还。
     *
     * @return 退还前的物品快照数组（含空气格子），视图未打开时返回全空气
     */
    public Item[] returnItems() {
        Item[] snapshot = exportItems();
        Player player = viewer();
        if (view == null || player == null) {
            return snapshot;
        }
        // 收集非空物品
        List<Item> toReturn = new ArrayList<>();
        for (Item item : snapshot) {
            if (item != null && !item.isNull()) {
                toReturn.add(item);
            }
        }
        if (!toReturn.isEmpty()) {
            // 添加到玩家背包，返回值为无法放入的剩余物品
            Item[] remaining = player.getInventory().addItem(toReturn.toArray(new Item[0]));
            // 背包满时掉落到玩家脚下
            for (Item drop : remaining) {
                if (drop != null && !drop.isNull()) {
                    player.getLevel().dropItem(player, drop);
                }
            }
        }
        // 清空存储格（null 视为空气）
        loadItems(new Item[width * height]);
        return snapshot;
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

    /**
     * 存取守卫默认实现：① 放入校验（filter）→ ② 取出校验（withdrawFilter）→ ③ 高级守卫（beforeStore）。
     * <p>
     * 交换动作同时触发 ①② 双向校验（换入过 filter、换出过 withdrawFilter），第一个失败即否决。
     * 子类重写时须调用 {@code super.onBeforeStore(event)} 以保留上述行为。
     */
    @Override
    protected void onBeforeStore(BeforeStoreEvent event) {
        // 1. 放入校验：有物品进入本格（放入/换入/并入堆叠）
        if (depositFilter != null && event.isEntering()
                && !depositFilter.test(event.targetItem())) {
            event.cancel(filterDenyMessage);
            return;
        }
        // 2. 取出校验：有物品离开本格（取出/换出/右键拿一半）
        if (withdrawFilter != null && event.isLeaving()
                && !withdrawFilter.test(event.sourceItem())) {
            event.cancel(withdrawDenyMessage);
            return;
        }
        // 3. 高级守卫
        if (beforeStoreHandler != null) {
            beforeStoreHandler.accept(event);
        }
    }

    @Override
    protected void onUnmount() {
        // 关闭界面时自动退还物品到玩家背包（如果开关已开启）
        // 时序安全：onUnmount() 在 this.view 置空前调用，此时 view、viewer、inventory 均有效
        if (returnOnClose) {
            returnItems();
        }
    }
}
