package io.github.JiangHu.jframe.ai.core.util;

import cn.nukkit.block.Block;
import cn.nukkit.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 方块查询快照缓存：在<b>单次计算任务</b>的生命周期内，缓存 {@link Level#getBlock(int, int, int)} 的结果，
 * 消除同一坐标的重复世界查询。
 * <p>
 * A* 寻路对每个候选落脚点要做"脚部 + 头部 + 脚下支撑"多次方块查询，且相邻节点的探测列高度重叠，
 * 同一坐标在一次搜索中往往被查询 3~8 次。Nukkit 的 {@code getBlock} 涉及 chunk 定位与方块状态解析，
 * 是寻路热点中的主要开销。本缓存把重复查询降为一次 {@link HashMap} 命中。
 *
 * <h3>生命周期约定（重要）</h3>
 * 本类<b>不做任何失效处理</b>——缓存的方块状态是创建时刻的快照。调用方负责生命周期：
 * <ul>
 *   <li>一次寻路计算 = 一个实例（在 {@code compute()} 内创建，计算结束即丢弃）</li>
 *   <li>一次战术扫描 = 一个实例（同一帧内多个实体共享，见 TacticalScanner）</li>
 *   <li>禁止跨 tick / 跨任务复用（世界可能在任务间隙被修改）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 非线程安全。每个计算任务持有独立实例（任务本身运行在单线程上），
 * 不需要并发结构带来的开销。
 *
 * @see BlockChecks
 */
public final class BlockSnapshotCache {

    /** Y 偏移：合法 Y 范围 [-64, 320] 映射到 [0, 384]，占 9 bit */
    private static final int Y_OFFSET = 64;
    /** 坐标位宽掩码：X/Z 各 27 bit（±67M，覆盖 Minecraft 世界范围） */
    private static final int XZ_MASK = 0x07FF_FFFF;
    /** Y 位宽掩码：9 bit */
    private static final int Y_MASK = 0x1FF;

    private final Level level;
    private final Map<Long, Block> cache = new HashMap<>();

    /**
     * 创建快照缓存。
     *
     * @param level 被缓存的世界（快照来源）
     */
    public BlockSnapshotCache(Level level) {
        this.level = level;
    }

    /**
     * 查询方块（带缓存）：首次查询回源 {@link Level}，之后命中本地缓存。
     *
     * @param x 方块 X
     * @param y 方块 Y（须在 [-64, 320] 内，调用方负责边界检查）
     * @param z 方块 Z
     * @return 方块快照（与 {@link Level#getBlock} 语义一致）
     */
    public Block getBlock(int x, int y, int z) {
        return cache.computeIfAbsent(encode(x, y, z), k -> level.getBlock(x, y, z));
    }

    /**
     * 当前缓存的方块数量（诊断/测试用）。
     *
     * @return 已缓存的坐标数
     */
    public int size() {
        return cache.size();
    }

    /**
     * 三维方块坐标编码为单个 long，用作缓存 key 与闭合表 key。
     * <p>
     * 位布局：{@code x[26..62] | (y+64)[9..17] | z[0..26]}（X/Z 各 27 bit，Y 9 bit）。
     * X/Z 合法范围 ±67,108,863，Y 合法范围 [-64, 447]；超出范围的编码可能碰撞，
     * 调用方应先完成 Y 边界检查再编码。
     *
     * @param x 方块 X
     * @param y 方块 Y
     * @param z 方块 Z
     * @return 坐标编码
     */
    public static long encode(int x, int y, int z) {
        return ((long) (x & XZ_MASK) << 36)
                | ((long) ((y + Y_OFFSET) & Y_MASK) << 27)
                | (z & XZ_MASK);
    }
}
