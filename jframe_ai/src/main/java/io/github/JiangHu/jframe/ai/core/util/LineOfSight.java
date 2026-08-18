package io.github.JiangHu.jframe.ai.core.util;

import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

/**
 * 视线（Line of Sight）检测工具：判断两点之间是否存在阻挡视线的固体方块。
 * <p>
 * 用于战术（找掩体时判断某位置是否被遮挡）与战斗（判断能否看到/射击目标）。
 *
 * <h3>算法</h3>
 * 采用 <b>Amanatides & Woo 逐格 DDA</b>（三维网格遍历）：沿 from→to 射线
 * 按穿过的方块边界逐格前进，每个途经方块恰好检查一次——
 * <ul>
 *   <li>无遗漏：不会像等距采样那样漏掉斜穿角落的薄方块</li>
 *   <li>无重复：同一方块不会被查询两次</li>
 *   <li>查询次数 = 途经方块数（约等于射线曼哈顿长度），与步长参数无关</li>
 * </ul>
 * 起点格与终点格均参与检查（眼在墙里 / 目标被方块包裹均视为不可见）。
 *
 * <h3>快照缓存</h3>
 * 批量检测场景（如 {@link io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner}
 * 对数百个候选位置逐一做视线检测）应使用
 * {@link #hasLineOfSight(BlockSnapshotCache, Vector3, Vector3)} 共享同一份方块快照，
 * 避免同一坐标反复回源世界。
 *
 * @see BlockChecks
 * @see BlockSnapshotCache
 */
public final class LineOfSight {

    private LineOfSight() {
    }

    /**
     * 判断 from 是否能"看到" to（两点间无固体方块阻挡）。
     *
     * @param level 世界
     * @param from  观察点（如实体眼部位置）
     * @param to    目标点
     * @return true 表示视线畅通
     */
    public static boolean hasLineOfSight(Level level, Vector3 from, Vector3 to) {
        if (level == null || from == null || to == null) {
            return false;
        }
        return traverse(level, null, from, to);
    }

    /**
     * 判断视线是否畅通（共享方块快照缓存版）。
     * <p>
     * 批量检测（一次扫描对多个候选做视线判断）应传入同一 {@link BlockSnapshotCache}
     * 实例，使重复坐标的方块查询降为本地缓存命中。
     *
     * @param cache 单次计算任务生命周期内的方块快照
     * @param from  观察点
     * @param to    目标点
     * @return true 表示视线畅通
     */
    public static boolean hasLineOfSight(BlockSnapshotCache cache, Vector3 from, Vector3 to) {
        if (cache == null || from == null || to == null) {
            return false;
        }
        return traverse(null, cache, from, to);
    }

    /**
     * 判断 from 是否被遮挡（即看不到 to）。
     *
     * @param level 世界
     * @param from  观察点
     * @param to    目标点
     * @return true 表示视线被阻挡
     */
    public static boolean isBlocked(Level level, Vector3 from, Vector3 to) {
        return !hasLineOfSight(level, from, to);
    }

    /**
     * DDA 核心：level 与 cache 二选一（cache 非 null 时优先走缓存）。
     */
    private static boolean traverse(Level level, BlockSnapshotCache cache, Vector3 from, Vector3 to) {
        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);
        int ex = floor(to.x);
        int ey = floor(to.y);
        int ez = floor(to.z);

        // 起点格即固体（观察点在墙里）
        if (isSolid(level, cache, x, y, z)) {
            return false;
        }
        if (x == ex && y == ey && z == ez) {
            return true;
        }

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        int stepX = stepOf(dx);
        int stepY = stepOf(dy);
        int stepZ = stepOf(dz);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX != 0 ? boundaryT(stepX, x, from.x) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY != 0 ? boundaryT(stepY, y, from.y) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ != 0 ? boundaryT(stepZ, z, from.z) * tDeltaZ : Double.POSITIVE_INFINITY;

        // 浮点防御熔断：理论步数 = 各轴格距之和，上限放宽 3 倍 + 常数
        int maxSteps = 3 * (Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z)) + 8;

        for (int i = 0; i < maxSteps; i++) {
            // 沿 t 值最小的轴前进一格
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
            if (isSolid(level, cache, x, y, z)) {
                return false;
            }
            if (x == ex && y == ey && z == ez) {
                return true;
            }
        }
        // 浮点误差下未精确到达终点格：视为可达（防御分支，正常不会触发）
        return true;
    }

    /** 方向符号：正/负/零 */
    private static int stepOf(double d) {
        return d > 0 ? 1 : (d < 0 ? -1 : 0);
    }

    /** 沿 step 方向到当前格边界的距离（格单位，非 t 单位） */
    private static double boundaryT(int step, int cell, double pos) {
        return step > 0 ? cell + 1 - pos : pos - cell;
    }

    /** 固体判断：cache 非 null 走快照，否则回源 level */
    private static boolean isSolid(Level level, BlockSnapshotCache cache, int x, int y, int z) {
        if (cache != null) {
            return BlockChecks.isSolid(cache, x, y, z);
        }
        return BlockChecks.isSolid(level, x, y, z);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
