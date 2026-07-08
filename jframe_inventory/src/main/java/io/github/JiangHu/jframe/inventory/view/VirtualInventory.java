package io.github.JiangHu.jframe.inventory.view;

import cn.nukkit.Player;
import cn.nukkit.inventory.ContainerInventory;
import cn.nukkit.inventory.FakeBlockMenu;
import cn.nukkit.inventory.InventoryHolder;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;

/**
 * 虚拟库存（Nukkit 桥接层）。
 * <p>
 * 本类是 jframe 声明式界面框架与 Nukkit-MOT 库存系统的桥接。它继承
 * {@link ContainerInventory}，复用 Nukkit 原生的 {@code ContainerOpenPacket} /
 * {@code ContainerClosePacket} 发包逻辑（与经过验证的 FakeInventories 库方案一致），
 * 并通过 {@link FakeBlockHelper} 在世界中放置临时真实方块作为容器坐标锚点。
 *
 * <h3>打开流程（客户端假方块 + 延迟注册窗口）</h3>
 * <p>
 * 网易版客户端在收到 {@code ContainerOpenPacket} 时，会校验目标坐标是否存在容器方块。
 * 如果方块不存在于客户端本地世界，客户端会静默拒绝打开界面（不报错，界面不出现）。
 * 因此必须先发送 {@code UpdateBlockPacket} 让客户端"看到"方块，再发送打开包。
 * <p>
 * 打开流程为：
 * <ol>
 *   <li>{@link InventoryView#open} 延迟 {@code OPEN_DELAY_TICKS} tick 后调用
 *       {@code player.addWindow}。</li>
 *   <li>{@link #onOpen} 内<b>同步</b>完成：
 *     {@link FakeBlockHelper#create} 发送客户端假方块（{@code UpdateBlockPacket} +
 *     {@code BlockEntityDataPacket}，runtimeId 基于玩家 GameVersion），
 *     然后 {@code super.onOpen}（{@link ContainerInventory#onOpen}）发送
 *     {@code ContainerOpenPacket} + {@code sendContents}。</li>
 * </ol>
 * 该方案与经过验证的 FakeInventories 库完全一致，确保网易版客户端兼容。
 *
 * <h3>关闭流程</h3>
 * <ol>
 *   <li>{@link #onClose} 调用 {@code super.onClose}（{@link ContainerInventory#onClose}）
 *       同步发送 {@code ContainerClosePacket}。</li>
 *   <li>{@link FakeBlockHelper#remove} 恢复世界中原方块。</li>
 * </ol>
 *
 * <h3>为什么继承 ContainerInventory 而非 BaseInventory</h3>
 * 早期实现继承 {@code BaseInventory} 并手动构造 {@code ContainerOpenPacket}，但手动发包
 * 容易遗漏 Nukkit 内部状态同步（如 viewer 集合、windowId 映射）。{@link ContainerInventory}
 * 是 Nukkit 为「方块容器库存」（箱子、熔炉等）设计的基类，其 {@code onOpen}/{@code onClose}
 * 已正确处理 {@code ContainerOpenPacket}/{@code ContainerClosePacket} 的构造与发送，
 * 与参考插件使用的 {@code ChestFakeInventory}（同样继承 {@code ContainerInventory}）一致。
 *
 * @see InventoryView
 * @see FakeBlockHelper
 */
public class VirtualInventory extends ContainerInventory {

    /** 所属视图 */
    private final InventoryView view;

    /** 自定义标题 */
    private final String title;

    /** 自定义格子数（支持 1-6 行 = 9-54 格） */
    private final int customSize;

    /** 假方块助手（负责在世界中放置/移除临时容器方块） */
    private final FakeBlockHelper fakeBlock;

    /** 持有者（FakeBlockMenu，同时充当 ContainerOpenPacket 的坐标来源） */
    private final FakeBlockMenu fakeBlockHolder;

    /**
     * 创建虚拟库存。
     *
     * @param view  所属视图
     * @param type  库存类型（CHEST / DOUBLE_CHEST）
     * @param title 标题
     * @param size  格子数
     */
    VirtualInventory(InventoryView view, InventoryType type, String title, int size) {
        super(null, type);
        this.view = view;
        this.title = title;
        this.customSize = size;
        this.fakeBlock = FakeBlockHelper.forType(type);
        // FakeBlockMenu 同时是 InventoryHolder 和 Position（Vector3 子类），
        // ContainerInventory.onOpen 会通过 getHolder() 读取其坐标来发送 ContainerOpenPacket。
        // holder 是 BaseInventory 的 protected 字段，子类可直接赋值。
        this.fakeBlockHolder = new FakeBlockMenu(this, new Position(0, 0, 0));
        this.holder = this.fakeBlockHolder;
    }

    @Override
    public String getTitle() {
        return title != null ? title : super.getTitle();
    }

    @Override
    public int getSize() {
        return customSize;
    }

    /**
     * 打开库存——同步发送客户端假方块 + {@code ContainerOpenPacket}。
     * <p>
     * 由 {@code player.addWindow(this)} → {@code inventory.open(player)} 调用。
     * 在 {@code super.onOpen} 之前，先通过 {@link FakeBlockHelper#create} 发送客户端假方块
     * （{@code UpdateBlockPacket} + {@code BlockEntityDataPacket}），使网易版客户端
     * 通过方块校验（runtimeId 基于玩家 GameVersion），然后 {@code super.onOpen} 同步发送
     * {@code ContainerOpenPacket} + {@code sendContents}。
     */
    @Override
    public void onOpen(Player player) {
        // 1. 在世界中放置真实容器方块，获取坐标
        Vector3 position = this.fakeBlock.create(player, this.getTitle());
        // 2. 更新 holder 坐标
        this.fakeBlockHolder.x = position.x;
        this.fakeBlockHolder.y = position.y;
        this.fakeBlockHolder.z = position.z;
        // 3. ContainerInventory.onOpen：同步发送 ContainerOpenPacket（使用 holder 坐标）+ sendContents
        super.onOpen(player);
    }

    /**
     * 关闭库存——同步发送关闭包，恢复世界原方块。
     */
    @Override
    public void onClose(Player player) {
        // 1. ContainerInventory.onClose：同步发送 ContainerClosePacket + 移除 viewer
        super.onClose(player);

        // 2. 恢复世界中该坐标的原方块（真实方块方案必须恢复，否则方块残留）
        this.fakeBlock.remove();
    }

    /**
     * 获取所属视图。
     *
     * @return 视图
     */
    public InventoryView view() {
        return view;
    }
}
