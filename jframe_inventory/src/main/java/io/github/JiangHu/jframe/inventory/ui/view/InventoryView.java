package io.github.JiangHu.jframe.inventory.ui.view;

import cn.nukkit.Player;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.JFrameLog;
import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.component.StorageBox;
import io.github.JiangHu.jframe.inventory.ui.manager.InventoryManager;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.SlotType;

import java.util.Arrays;

/**
 * 箱子界面视图基类。
 * <p>
 * 开发者继承本类，在 {@link #buildRoot()} 中构建组件树，
 * 框架自动处理渲染、事件分发、增量更新。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>管理 {@link SlotData} 数组（格子数据）</li>
 *   <li>渲染管线：重置格子 → 渲染组件树 → 同步到 Nukkit 库存</li>
 *   <li>事件分发：根据格子 {@link SlotType} 决定取消/放行交易</li>
 *   <li>增量更新：{@link #repaint()} 重新渲染并发送给玩家</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class ShopView extends InventoryView {
 *
 *     public ShopView() {
 *         super(3, "§6武器商店");
 *     }
 *
 *     @Override
 *     protected InventoryComponent buildRoot() {
 *         Panel root = new Panel(9, 3);
 *         root.add(new Button(SlotAppearance.builder()
 *                 .type(Item.DIAMOND_SWORD)
 *                 .name("§b购买武器")
 *                 .build()).onClick(e -> {
 *             e.player().sendMessage("购买成功！");
 *         }), 0, 0);
 *         return root;
 *     }
 * }
 * }</pre>
 *
 * @see InventoryComponent
 * @see RenderContext
 * @see SlotType
 */
public abstract class InventoryView {

    /** 箱子列数（固定为 9） */
    public static final int COLS = 9;

    // -------------------- 配置 --------------------

    /** 行数 */
    private final int rows;
    /** 总格子数 */
    private final int size;
    /** 标题 */
    private final String title;
    /** 库存类型 */
    private final InventoryType inventoryType;

    // -------------------- 运行时状态 --------------------

    /** 格子数据 */
    private final SlotData[] slots;
    /** 根组件 */
    private InventoryComponent root;
    /** 当前查看的玩家 */
    private Player viewer;
    /** Nukkit 桥接 */
    private VirtualInventory inventory;
    /** 插件实例（由 InventoryManager 注入，供 open() 调度延迟任务及子类扩展使用） */
    private Plugin plugin;
    /** 是否已清理（防止 InventoryCloseEvent 与 close() 重复清理） */
    private boolean cleanedUp = false;

    /** 窗口是否已注册到玩家（addWindow 已完成，防止打开过程中 InventoryCloseEvent 误触发清理） */
    private boolean windowRegistered = false;

    /** 本次打开实际使用的延迟（tick），由 open(Player, int) 记录，供重复打开防抖窗口计算 */
    private int openDelayTicks = OPEN_DELAY_TICKS;

    /**
     * 打开延迟（tick）：调用 {@code open()} 后等待多少 tick 再注册窗口（{@code addWindow}）。
     * <p>
     * 延迟结束后，{@code addWindow} → {@link VirtualInventory#onOpen} 内<b>同步</b>完成：
     * 发送客户端假方块（{@code UpdateBlockPacket} + {@code BlockEntityDataPacket}）+
     * {@code ContainerOpenPacket} + {@code sendContents}。
     * <p>
     * 默认 10 tick（500ms），与经过验证的 FakeInventories 库方案一致：
     * <ul>
     *   <li>过短（< 5 tick）：客户端可能尚未准备好，网易版窗口打不开</li>
     *   <li>过长（> 15 tick）：玩家感知明显延迟</li>
     * </ul>
     * 可通过 {@code InventoryView.OPEN_DELAY_TICKS = N} 调整。
     */
    public static int OPEN_DELAY_TICKS = 10;

    // -------------------- 构造 --------------------

    /**
     * 创建视图。
     *
     * @param rows  行数（1-6）
     * @param title 标题
     */
    protected InventoryView(int rows, String title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be 1-6, got: " + rows);
        }
        this.rows = rows;
        this.size = rows * COLS;
        this.title = title;
        this.inventoryType = (rows <= 3) ? InventoryType.CHEST : InventoryType.DOUBLE_CHEST;
        this.slots = new SlotData[size];
        Arrays.fill(slots, SlotData.empty());
    }

    /**
     * 创建默认 3 行单箱视图。
     *
     * @param title 标题
     */
    protected InventoryView(String title) {
        this(3, title);
    }

    // -------------------- 开发者实现 --------------------

    /**
     * 构建根组件（开发者实现）。
     * <p>
     * 在此方法中创建组件树，返回根组件。框架会在首次渲染时调用。
     *
     * @return 根组件
     */
    protected abstract InventoryComponent buildRoot();

    // -------------------- 生命周期回调 --------------------

    /**
     * 视图打开时调用（开发者可重写）。
     */
    protected void onOpen() {
    }

    /**
     * 视图关闭时调用（开发者可重写）。
     */
    protected void onClose() {
    }

    // -------------------- 渲染管线（框架内部） --------------------

    /**
     * 执行完整渲染。
     * <p>
     * 重置格子 → 渲染组件树 → 同步到 Nukkit 库存。
     */
    void render() {
        // 1. 重置所有格子为默认
        Arrays.fill(slots, SlotData.empty());

        // 2. 创建根组件（如果还没创建）
        if (root == null) {
            root = buildRoot();
            root.mountTree(this);
        }

        // 3. 创建渲染上下文（根组件从 (0,0) 开始）
        RenderContext ctx = new RenderContext(this, root, 0, 0);

        // 4. 渲染根组件树
        root.render(ctx);

        // 5. 同步到 Nukkit 库存
        syncToInventory();
    }

    /**
     * 将 SlotData[] 同步到 VirtualInventory。
     */
    private void syncToInventory() {
        if (inventory == null) return;
        for (int i = 0; i < size; i++) {
            SlotData data = slots[i];
            if (data.appearance() != null) {
                // 框架管理外观（BUTTON/DISPLAY/LOCKED）：直接覆盖
                inventory.setItem(i, data.appearance().toItem(), false);
            }
            // STORAGE 格子（appearance 为 null）：保留玩家放入的物品，不做处理
        }
    }

    /**
     * 增量更新（重新渲染并发送给玩家）。
     */
    public void repaint() {
        render();
        if (inventory != null && viewer != null) {
            inventory.sendContents(viewer);
        }
    }

    // -------------------- 格子操作（被 RenderContext 调用） --------------------

    /**
     * 设置格子数据。
     *
     * @param absRow     绝对行
     * @param absCol     绝对列
     * @param appearance 物品外观（null 表示不设置物品，仅标记类型）
     * @param type       格子类型
     * @param owner      拥有者组件
     */
    void setSlot(int absRow, int absCol, SlotAppearance appearance,
                 SlotType type, InventoryComponent owner) {
        int index = absRow * COLS + absCol;
        if (index < 0 || index >= size) return;
        slots[index] = new SlotData(appearance, type, owner);
    }

    /**
     * 清空格子（恢复为默认 LOCKED）。
     *
     * @param absRow 绝对行
     * @param absCol 绝对列
     */
    void clearSlot(int absRow, int absCol) {
        int index = absRow * COLS + absCol;
        if (index < 0 || index >= size) return;
        slots[index] = SlotData.empty();
    }

    // -------------------- 格子查询（被 InventoryManager 调用） --------------------

    /**
     * 获取格子类型。
     *
     * @param index 格子序号
     * @return 格子类型
     */
    public SlotType slotType(int index) {
        if (index < 0 || index >= size) return SlotType.LOCKED;
        return slots[index].type();
    }

    /**
     * 获取格子拥有者。
     *
     * @param index 格子序号
     * @return 拥有者组件
     */
    InventoryComponent slotOwner(int index) {
        if (index < 0 || index >= size) return null;
        return slots[index].owner();
    }

    /**
     * 获取指定格子上的物品（原生物品）。
     * <p>
     * 供组件（如 {@link StorageBox}）
     * 实现物品加载/导出功能。STORAGE 格子返回玩家放入的物品，框架管理的格子
     * （BUTTON/DISPLAY/LOCKED）返回其外观物品。
     *
     * @param index 绝对格子序号
     * @return 格子上的物品，视图未打开或序号越界时返回空气物品
     */
    public Item slotItem(int index) {
        if (inventory == null || index < 0 || index >= size) {
            return Item.get(Item.AIR);
        }
        Item item = inventory.getItem(index);
        return item != null ? item : Item.get(Item.AIR);
    }

    /**
     * 设置指定格子上的物品（原生物品）。
     * <p>
     * 供组件（如 {@link StorageBox}）
     * 实现物品加载/导出功能。仅对 STORAGE 格子有意义：向框架管理的格子写入会被
     * 下次 {@link #repaint()} 覆盖。
     *
     * @param index 绝对格子序号
     * @param item  要设置的物品，{@code null} 视为空气（清空格子）
     * @param send  是否同步给在线查看的玩家
     */
    public void setSlotItem(int index, Item item, boolean send) {
        if (inventory == null || index < 0 || index >= size) {
            return;
        }
        inventory.setItem(index, item != null ? item : Item.get(Item.AIR), send);
    }

    /**
     * 窗口是否已注册到玩家（{@code addWindow} 已完成）。
     * <p>
     * 用于 {@link InventoryManager} 防止
     * 在窗口打开过程中（延迟期间或 addWindow 调用链中）{@code InventoryCloseEvent}
     * 误触发清理。
     *
     * @return 已注册返回 true
     */
    public boolean isWindowRegistered() {
        return windowRegistered;
    }

    /**
     * 视图是否已关闭（资源已清理）。
     * <p>
     * 用于 {@link InventoryManager} 判断玩家当前视图是否仍然活跃：
     * <ul>
     *   <li>{@code false}：视图活跃（正在打开或已打开），{@code openView} 会忽略重复请求</li>
     *   <li>{@code true}：视图已关闭，可被新视图替换</li>
     * </ul>
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return cleanedUp;
    }

    // -------------------- 事件分发（被 InventoryManager 调用） --------------------

    /**
     * 分发点击事件。
     *
     * @param slot   格子序号
     * @param player 玩家
     * @param item   格子上的物品
     */
    public void handleClick(int slot, Player player, Item item) {
        InventoryComponent owner = slotOwner(slot);
        if (owner != null) {
            owner.dispatchClick(slot, player, item);
        }
    }

    /**
     * 分发存取事件。
     *
     * @param slot       格子序号
     * @param player     玩家
     * @param sourceItem 操作前物品
     * @param targetItem 操作后物品
     */
    public void handleStore(int slot, Player player, Item sourceItem, Item targetItem) {
        InventoryComponent owner = slotOwner(slot);
        if (owner != null) {
            owner.dispatchStore(slot, player, sourceItem, targetItem);
        }
    }

    // -------------------- 打开/关闭（被 InventoryManager 调用） --------------------

    /**
     * 绑定插件实例（由 {@link InventoryManager} 在打开前注入）。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 获取插件实例（包级访问，供 {@link VirtualInventory} 调度延迟任务使用）。
     *
     * @return 插件实例，未注入时为 null
     */
    Plugin plugin() {
        return plugin;
    }

    /**
     * 打开视图（延迟注册窗口策略，使用默认延迟 {@link #OPEN_DELAY_TICKS}）。
     * <p>
     * 等价于 {@code open(player, OPEN_DELAY_TICKS)}。
     *
     * @param player 玩家
     * @see #open(Player, int)
     */
    public void open(Player player) {
        open(player, OPEN_DELAY_TICKS);
    }

    /**
     * 打开视图（延迟注册窗口策略，自定义延迟）。
     * <p>
     * 延迟 {@code delayTicks} tick 后调用 {@code player.addWindow()}，
     * 由 {@link VirtualInventory#onOpen} <b>同步</b>完成：
     * <ol>
     *   <li>发送客户端假方块（{@link cn.nukkit.network.protocol.UpdateBlockPacket} +
     *       {@link cn.nukkit.network.protocol.BlockEntityDataPacket}），使网易版客户端
     *       通过方块校验（runtimeId 基于玩家 GameVersion）</li>
     *   <li>发送 {@code ContainerOpenPacket} + {@code sendContents}</li>
     * </ol>
     * <p>
     * 该方案与经过验证的 FakeInventories 库完全一致，确保网易版客户端兼容。
     * <p>
     * 本次实际使用的延迟会记录到实例状态（见 {@link #openDelayTicks()}），
     * 供 {@link InventoryManager} 计算"重复打开防抖"窗口，
     * 避免自定义长延迟时防抖窗口过短导致界面被误关重开。
     *
     * @param player     玩家
     * @param delayTicks 打开延迟（tick），负值视为 0（立即打开）；
     *                   网易版客户端兼容建议不低于 5 tick（过短可能导致窗口打不开）
     */
    public void open(Player player, int delayTicks) {
        this.viewer = player;
        this.cleanedUp = false;
        // 记录本次打开实际使用的延迟（负值归一化为 0，表示立即打开）
        this.openDelayTicks = Math.max(0, delayTicks);

        if (plugin == null) {
            JFrameLog.error("InventoryView", "plugin 未注入，无法打开界面");
            return;
        }

        try {
            // 1. 创建 Nukkit 库存（ContainerInventory 子类）
            this.inventory = new VirtualInventory(this, inventoryType, title, size);

            // 2. 渲染（将组件树同步到库存物品）
            render();

            VirtualInventory inv = this.inventory;

            // 3. 延迟 openDelayTicks 后注册窗口：
            //    addWindow → VirtualInventory.onOpen 内同步发送客户端假方块 + ContainerOpenPacket
            cn.nukkit.Server.getInstance().getScheduler()
                    .scheduleDelayedTask(plugin, () -> {
                        // 延迟期间玩家可能下线或视图被关闭
                        if (!player.isOnline() || cleanedUp) {
                            return;
                        }
                        player.addWindow(inv);
                        this.windowRegistered = true;
                        // 生命周期回调（窗口已注册后调用）
                        onOpen();
                    }, this.openDelayTicks);
        } catch (Exception e) {
            JFrameLog.error("InventoryView", "Failed to open inventory for " + player.getName(), e);
        }
    }

    /**
     * 关闭视图（主动关闭，调用 removeWindow）。
     * <p>
     * 注意：{@code removeWindow} 会同步触发 {@code InventoryCloseEvent}，
     * 进而调用 {@link #cleanup()}。本方法在调用 {@code removeWindow} 前
     * 先执行清理逻辑，确保 {@code onClose()} 只被调用一次。
     */
    public void close() {
        // 保存引用（cleanup 会将它们置 null）
        Player player = viewer;
        VirtualInventory inv = inventory;
        // 先清理（执行 onClose、卸载组件树）
        // cleanup() 内部会设置 cleanedUp = true，
        // 这样 InventoryCloseEvent 触发时不会重复清理
        cleanup();
        if (player != null && inv != null) {
            player.removeWindow(inv);
        }
    }

    /**
     * 清理视图（不调用 removeWindow，由玩家关闭箱子时触发）。
     * <p>
     * 幂等操作：多次调用安全，{@code onClose()} 只触发一次。
     */
    public void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        this.windowRegistered = false;

        onClose();
        if (root != null) {
            root.unmountTree();
            root = null;
        }
        viewer = null;
        inventory = null;
    }

    // -------------------- Getter --------------------

    /**
     * 获取当前查看的玩家。
     *
     * @return 玩家，未打开时为 null
     */
    public Player viewer() {
        return viewer;
    }

    /**
     * 获取行数。
     *
     * @return 行数
     */
    public int rows() {
        return rows;
    }

    /**
     * 获取总格子数。
     *
     * @return 格子数
     */
    public int size() {
        return size;
    }

    /**
     * 获取本次打开实际使用的延迟（tick）。
     * <p>
     * 由 {@link #open(Player, int)} 记录，未打开过时为默认值 {@link #OPEN_DELAY_TICKS}。
     * 供 {@link InventoryManager} 计算"重复打开防抖"窗口。
     *
     * @return 打开延迟（tick）
     */
    public int openDelayTicks() {
        return openDelayTicks;
    }

    /**
     * 获取标题。
     *
     * @return 标题
     */
    public String title() {
        return title;
    }

    /**
     * 获取 Nukkit 库存（框架内部使用）。
     *
     * @return 库存
     */
    VirtualInventory inventory() {
        return inventory;
    }

    // -------------------- 组件查找 --------------------

    /**
     * 获取根组件。
     *
     * @return 根组件，未渲染时为 null
     */
    public InventoryComponent root() {
        return root;
    }

    /**
     * 在组件树中按名称查找组件。
     *
     * @param name 组件名称
     * @return 匹配的组件，未找到返回 null
     */
    public InventoryComponent findComponent(String name) {
        return root != null ? root.findByName(name) : null;
    }

    /**
     * 在组件树中按类型查找组件。
     *
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配的组件，未找到返回 null
     */
    public <T extends InventoryComponent> T findComponent(Class<T> type) {
        return root != null ? root.findByType(type) : null;
    }

    /**
     * 在组件树中按名称和类型查找组件。
     *
     * @param name 组件名称
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配的组件，未找到返回 null
     */
    public <T extends InventoryComponent> T findComponent(String name, Class<T> type) {
        return root != null ? root.findByName(name, type) : null;
    }

    /**
     * 在组件树中按名称查找所有匹配的组件。
     *
     * @param name 组件名称
     * @return 匹配的组件列表（可能为空）
     */
    public java.util.List<InventoryComponent> findAllComponents(String name) {
        return root != null ? root.findAllByName(name) : java.util.Collections.emptyList();
    }

    /**
     * 在组件树中按类型查找所有匹配的组件。
     *
     * @param type 期望类型
     * @param <T>  组件类型
     * @return 匹配的组件列表（可能为空）
     */
    public <T extends InventoryComponent> java.util.List<T> findAllComponents(Class<T> type) {
        return root != null ? root.findAllByType(type) : java.util.Collections.emptyList();
    }
}
