package io.github.JiangHu.jframe.ai.core.util;

import cn.nukkit.block.Block;
import cn.nukkit.level.Level;

/**
 * 方块可通行性检查工具：为战术与战斗模块提供统一的"实体能否站立/穿过某方块"判断。
 * <p>
 * 本类集中封装对 Nukkit {@link Block#canPassThrough()} 的语义解释，确保
 * {@link io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder}、战术类、战斗类
 * 对"可站立"的定义保持一致。
 *
 * <h3>语义约定</h3>
 * <ul>
 *   <li>{@code passable}（可穿过）：方块不阻挡实体，如空气、水、草、花</li>
 *   <li>{@code solid}（固体/阻挡）：{@code !passable}，如石头、木头、泥土</li>
 *   <li>{@code standable}（可站立）：脚部及头部空间可穿过，且脚下为固体支撑</li>
 * </ul>
 *
 * @see LineOfSight
 */
public final class BlockChecks {

    /** 安全的最小 Y 边界 */
    private static final int MIN_Y = -64;
    /** 安全的最大 Y 边界 */
    private static final int MAX_Y = 320;

    private BlockChecks() {
    }

    /**
     * 该方块是否可被实体穿过。
     *
     * @param level 世界
     * @param x     方块 X
     * @param y     方块 Y
     * @param z     方块 Z
     * @return true 表示可穿过
     */
    public static boolean isPassable(Level level, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        return level.getBlock(x, y, z).canPassThrough();
    }

    /**
     * 该方块是否为不可穿过的固体。
     *
     * @param level 世界
     * @param x     方块 X
     * @param y     方块 Y
     * @param z     方块 Z
     * @return true 表示固体
     */
    public static boolean isSolid(Level level, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        return !level.getBlock(x, y, z).canPassThrough();
    }

    /**
     * 判断 (x,y,z) 是否为实体可站立的脚部位置。
     * <p>
     * 条件：脚部及头部（共 {@code entityHeight} 格）可穿过，且脚下为固体支撑。
     *
     * @param level        世界
     * @param x            方块 X
     * @param y            脚部 Y
     * @param z            方块 Z
     * @param entityHeight 实体高度（占用的方块层数，含脚部）
     * @return true 表示可站立
     */
    public static boolean isStandable(Level level, int x, int y, int z, int entityHeight) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        if (!isPassable(level, x, y, z)) {
            return false;
        }
        int height = Math.max(1, entityHeight);
        for (int h = 1; h < height; h++) {
            if (!isPassable(level, x, y + h, z)) {
                return false;
            }
        }
        return isSolid(level, x, y - 1, z);
    }

    /**
     * 以默认实体高度（2 格）判断可站立性。
     *
     * @param level 世界
     * @param x     方块 X
     * @param y     脚部 Y
     * @param z     方块 Z
     * @return true 表示可站立
     */
    public static boolean isStandable(Level level, int x, int y, int z) {
        return isStandable(level, x, y, z, 2);
    }

    // ========== 快照缓存版重载（批量计算场景共享同一份方块快照） ==========

    /**
     * 该方块是否可被实体穿过（快照缓存版）。
     *
     * @param cache 方块快照
     * @param x     方块 X
     * @param y     方块 Y
     * @param z     方块 Z
     * @return true 表示可穿过
     */
    public static boolean isPassable(BlockSnapshotCache cache, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        return cache.getBlock(x, y, z).canPassThrough();
    }

    /**
     * 该方块是否为不可穿过的固体（快照缓存版）。
     *
     * @param cache 方块快照
     * @param x     方块 X
     * @param y     方块 Y
     * @param z     方块 Z
     * @return true 表示固体
     */
    public static boolean isSolid(BlockSnapshotCache cache, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        return !cache.getBlock(x, y, z).canPassThrough();
    }

    /**
     * 判断 (x,y,z) 是否为实体可站立的脚部位置（快照缓存版）。
     *
     * @param cache        方块快照
     * @param x            方块 X
     * @param y            脚部 Y
     * @param z            方块 Z
     * @param entityHeight 实体高度（占用的方块层数，含脚部）
     * @return true 表示可站立
     */
    public static boolean isStandable(BlockSnapshotCache cache, int x, int y, int z, int entityHeight) {
        if (y < MIN_Y || y > MAX_Y) {
            return false;
        }
        if (!isPassable(cache, x, y, z)) {
            return false;
        }
        int height = Math.max(1, entityHeight);
        for (int h = 1; h < height; h++) {
            if (!isPassable(cache, x, y + h, z)) {
                return false;
            }
        }
        return isSolid(cache, x, y - 1, z);
    }
}
