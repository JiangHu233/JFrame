package io.github.JiangHu.jframe.inventory.ui.view;

import cn.nukkit.Player;
import cn.nukkit.block.BlockID;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.level.DimensionData;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.BlockEntityDataPacket;
import cn.nukkit.network.protocol.UpdateBlockPacket;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

/**
 * 假方块助手（Nukkit 桥接层，内部使用）。
 * <p>
 * 采用<b>客户端假方块</b>方案：直接向玩家发送 {@link UpdateBlockPacket} 和
 * {@link BlockEntityDataPacket}，使客户端「以为」该坐标存在容器方块，
 * 从而接受 {@code ContainerOpenPacket} 并弹出界面。
 *
 * <h3>为什么用客户端假方块而非服务器真实方块</h3>
 * <p>
 * 网易版（MOT）客户端在收到 {@code ContainerOpenPacket} 时会校验本地世界中
 * 该坐标是否存在对应的容器方块。早期实现使用 {@code level.setBlock()} 在服务器世界
 * 放置<b>真实</b>方块，但服务器世界同步发出的 {@code UpdateBlockPacket} 的
 * {@code blockRuntimeId} 不一定匹配玩家的客户端版本（网易版不同客户端版本有不同的
 * 方块 ID 映射），导致<b>方块校验失败、窗口不显示</b>。
 * <p>
 * 客户端假方块方案通过 {@link GlobalBlockPalette#getOrCreateRuntimeId} 获取与
 * 玩家 {@code GameVersion} 匹配的 {@code blockRuntimeId}，确保兼容所有客户端版本。
 * 该方案与经过验证的 FakeInventories 库（{@code ChestFakeInventory}）完全一致。
 *
 * <h3>方案说明</h3>
 * {@link #create} 对每个坐标发送两个包：
 * <ol>
 *   <li>{@link UpdateBlockPacket}：方块本体（{@code blockRuntimeId} 基于
 *       {@code GlobalBlockPalette.getOrCreateRuntimeId(gameVersion, blockId, 0)}，
 *       {@code flags = 0x0B} 即 NEIGHBORS | NETWORK | PRIORITY）</li>
 *   <li>{@link BlockEntityDataPacket}：方块实体数据（NBT 含 {@code id}、坐标、
 *       {@code CustomName} 标题；双联箱额外含 {@code pairx}/{@code pairz} 配对信息）</li>
 * </ol>
 * {@link #remove} 发送服务器世界中原始方块的 {@code UpdateBlockPacket} 恢复客户端显示。
 *
 * <h3>方块残留保护</h3>
 * 客户端假方块只存在于玩家客户端，服务器世界不变。关闭时通过 {@link #remove}
 * 恢复客户端原始方块显示。玩家下线时客户端自动清理，无需额外处理。
 *
 * @see VirtualInventory
 */
final class FakeBlockHelper {

    /**
     * UpdateBlockPacket flags：NEIGHBORS(1) | NETWORK(2) | PRIORITY(8) = 0x0B。
     * <p>
     * 与 FakeInventories 库一致，确保方块更新立即生效且优先处理。
     */
    private static final int UPDATE_FLAGS = 0x0B;

    /** 方块 ID（如 {@link BlockID#CHEST}） */
    private final int blockId;
    /** 方块实体类型 ID（如 {@link BlockEntity#CHEST}）；null 表示无方块实体 */
    private final String tileId;
    /** 是否为大型容器（双联箱子，需要放置两个方块） */
    private final boolean doubled;

    /** 上次放置假方块的玩家（关闭时发送恢复包） */
    private Player lastPlayer;
    /** 上次放置假方块的坐标列表（关闭时恢复客户端方块） */
    private List<Vector3> lastPositions = Collections.emptyList();

    private FakeBlockHelper(int blockId, String tileId, boolean doubled) {
        this.blockId = blockId;
        this.tileId = tileId;
        this.doubled = doubled;
    }

    /**
     * 根据库存类型创建对应的假方块助手。
     *
     * @param type 库存类型
     * @return 假方块助手
     */
    static FakeBlockHelper forType(InventoryType type) {
        return switch (type) {
            case CHEST -> new FakeBlockHelper(BlockID.CHEST, BlockEntity.CHEST, false);
            case DOUBLE_CHEST -> new FakeBlockHelper(BlockID.CHEST, BlockEntity.CHEST, true);
            case ENDER_CHEST -> new FakeBlockHelper(BlockID.ENDER_CHEST, BlockEntity.ENDER_CHEST, false);
            case FURNACE -> new FakeBlockHelper(BlockID.FURNACE, BlockEntity.FURNACE, false);
            case BREWING_STAND -> new FakeBlockHelper(BlockID.BREWING_STAND_BLOCK, BlockEntity.BREWING_STAND, false);
            case HOPPER -> new FakeBlockHelper(BlockID.HOPPER_BLOCK, BlockEntity.HOPPER, false);
            case SHULKER_BOX -> new FakeBlockHelper(BlockID.SHULKER_BOX, BlockEntity.SHULKER_BOX, false);
            // 工作台没有方块实体，标题无法显示但容器仍可打开
            case WORKBENCH -> new FakeBlockHelper(BlockID.WORKBENCH, null, false);
            case DISPENSER -> new FakeBlockHelper(BlockID.DISPENSER, null, false);
            case DROPPER -> new FakeBlockHelper(BlockID.DROPPER, null, false);
            default -> new FakeBlockHelper(BlockID.CHEST, BlockEntity.CHEST, false);
        };
    }

    /**
     * 是否为大型容器（双联箱）。
     * <p>
     * 双联箱需要放置两个方块并互相配对，客户端处理配对 NBT 需要额外时间，
     * 因此 {@link VirtualInventory#onOpen} 对双联箱延迟发送
     * {@code ContainerOpenPacket}（与 FakeInventories 库的
     * {@code DoubleChestFakeInventory} 一致）。
     *
     * @return true 表示双联箱
     */
    boolean isDoubled() {
        return doubled;
    }

    /**
     * 计算假方块坐标（基于玩家位置与朝向偏移）。
     *
     * @param player 玩家
     * @return 坐标列表（单箱 1 个，双联箱 2 个）；若超出维度高度范围则返回空列表
     */
    List<Vector3> getPositions(Player player) {
        Vector3 blockPosition = player.getPosition().add(getOffset(player)).floor();
        DimensionData dimensionData = player.getLevel().getDimensionData();
        if (blockPosition.getFloorY() < dimensionData.getMinHeight()
                || blockPosition.getFloorY() >= dimensionData.getMaxHeight()) {
            return Collections.emptyList();
        }
        if (!doubled) {
            return Collections.singletonList(blockPosition);
        }
        // 双联箱：根据 X 奇偶性决定另一半的方向
        if ((blockPosition.getFloorX() & 1) == 1) {
            return List.of(blockPosition, blockPosition.east());
        }
        return List.of(blockPosition, blockPosition.west());
    }

    /**
     * 向玩家客户端发送假方块（{@link UpdateBlockPacket} + {@link BlockEntityDataPacket}）。
     * <p>
     * 直接构造数据包发送给玩家，不修改服务器世界。方块 {@code blockRuntimeId} 基于
     * 玩家 {@code GameVersion} 获取，确保网易版客户端兼容。
     *
     * @param player 玩家
     * @param title  容器标题（写入方块实体的 CustomName）
     * @return 主方块坐标（双联箱返回第一个）；若坐标越界返回 (0,0,0)
     */
    Vector3 create(Player player, String title) {
        List<Vector3> positions = getPositions(player);
        this.lastPlayer = player;
        this.lastPositions = positions;

        for (int i = 0; i < positions.size(); i++) {
            Vector3 pos = positions.get(i);
            // 双联箱时，另一半坐标用于方块实体配对（pairx/pairz）
            Vector3 pair = (doubled && positions.size() == 2) ? positions.get(1 - i) : null;
            sendFakeBlock(player, pos, title, pair);
        }

        return positions.isEmpty() ? new Vector3(0, 0, 0) : positions.get(0);
    }

    /**
     * 发送单个假方块的客户端数据包。
     *
     * @param player 玩家
     * @param pos    方块坐标
     * @param title  标题
     * @param pair   双联箱另一半坐标（null 表示单箱）
     */
    private void sendFakeBlock(Player player, Vector3 pos, String title, Vector3 pair) {
        int x = pos.getFloorX();
        int y = pos.getFloorY();
        int z = pos.getFloorZ();

        // 1. UpdateBlockPacket：方块本体（runtimeId 基于玩家 GameVersion，兼容网易版客户端）
        UpdateBlockPacket blockPk = new UpdateBlockPacket();
        blockPk.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(
                player.getGameVersion(), blockId, 0);
        blockPk.flags = UPDATE_FLAGS;
        blockPk.x = x;
        blockPk.y = y;
        blockPk.z = z;
        player.dataPacket(blockPk);

        // 2. BlockEntityDataPacket：方块实体数据（含标题、配对信息）
        if (tileId != null) {
            BlockEntityDataPacket entityPk = new BlockEntityDataPacket();
            entityPk.x = x;
            entityPk.y = y;
            entityPk.z = z;
            entityPk.namedTag = buildTileNbt(x, y, z, title, pair);
            player.dataPacket(entityPk);
        }
    }

    /**
     * 构造方块实体的 NBT 数据（基岩版 network 格式，little-endian）。
     *
     * @param x     坐标 X
     * @param y     坐标 Y
     * @param z     坐标 Z
     * @param title 标题
     * @param pair  双联箱另一半坐标（null 表示单箱）
     * @return 序列化的 NBT 字节数组
     */
    private byte[] buildTileNbt(int x, int y, int z, String title, Vector3 pair) {
        CompoundTag tag = new CompoundTag()
                .putString("id", tileId)
                .putInt("x", x)
                .putInt("y", y)
                .putInt("z", z);
        if (title != null) {
            tag.putString("CustomName", title);
        }
        if (pair != null) {
            tag.putInt("pairx", pair.getFloorX())
                    .putInt("pairz", pair.getFloorZ());
        }
        try {
            // network=true：基岩版协议要求 varint 编码的 NBT
            return NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN, true);
        } catch (IOException e) {
            throw new RuntimeException("Unable to serialize fake block NBT", e);
        }
    }

    /**
     * 恢复玩家客户端的假方块（发送原始方块的 {@link UpdateBlockPacket}）。
     * <p>
     * 利用服务器世界中的原始方块数据恢复客户端显示。玩家已下线时跳过
     * （客户端假方块随断开连接自动消失）。
     */
    void remove() {
        if (lastPlayer == null || !lastPlayer.isOnline() || lastPositions.isEmpty()) {
            return;
        }
        Level level = lastPlayer.getLevel();
        if (level == null) {
            return;
        }
        for (Vector3 pos : lastPositions) {
            int x = pos.getFloorX();
            int y = pos.getFloorY();
            int z = pos.getFloorZ();
            // 恢复为服务器世界中该坐标的原始方块（客户端假方块方案下服务器世界未变）
            UpdateBlockPacket pk = new UpdateBlockPacket();
            pk.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(
                    lastPlayer.getGameVersion(), level.getFullBlock(x, y, z));
            pk.flags = UPDATE_FLAGS;
            pk.x = x;
            pk.y = y;
            pk.z = z;
            lastPlayer.dataPacket(pk);
        }
    }

    /**
     * 计算玩家朝向偏移（假方块放在玩家身后/上方）。
     */
    private Vector3 getOffset(Player player) {
        Vector3 offset = player.getDirectionVector();
        offset.x *= -(1 + player.getWidth());
        offset.y *= -(1 + player.getHeight());
        offset.z *= -(1 + player.getWidth());
        if (doubled) {
            // 双联箱需要更大空间，放大偏移
            offset.x *= 1.5;
            offset.z *= 1.5;
        }
        return offset;
    }
}
