package io.github.JiangHu.jframe.ai.util;

import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

/**
 * 视线（Line of Sight）检测工具：判断两点之间是否存在阻挡视线的固体方块。
 * <p>
 * 用于战术（找掩体时判断某位置是否被遮挡）与战斗（判断能否看到/射击目标）。
 *
 * <h3>算法</h3>
 * 采用<b>等距采样射线投射</b>：沿 from→to 方向以固定步长（默认 0.5 方块）采样，
 * 逐点检查所在方块是否为固体。一旦遇到固体即认为视线被阻挡。
 * <p>
 * 这比完整的 3D DDA 略粗略，但对游戏 AI 的"能否看到"判断已足够，且实现简单、性能稳定。
 *
 * @see BlockChecks
 */
public final class LineOfSight {

    /** 默认采样步长（方块） */
    private static final double DEFAULT_STEP = 0.5;

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
        return hasLineOfSight(level, from, to, DEFAULT_STEP);
    }

    /**
     * 判断视线是否畅通（指定采样步长）。
     *
     * @param level 世界
     * @param from  观察点
     * @param to    目标点
     * @param step  采样步长（方块，越小越精确、越慢）
     * @return true 表示视线畅通
     */
    public static boolean hasLineOfSight(Level level, Vector3 from, Vector3 to, double step) {
        if (level == null || from == null || to == null) {
            return false;
        }
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0e-4) {
            return true;
        }
        int steps = (int) Math.ceil(distance / step);
        if (steps <= 0) {
            return true;
        }
        double inv = 1.0 / steps;
        for (int i = 1; i < steps; i++) {
            double t = i * inv;
            int bx = floor(from.x + dx * t);
            int by = floor(from.y + dy * t);
            int bz = floor(from.z + dz * t);
            if (BlockChecks.isSolid(level, bx, by, bz)) {
                return false;
            }
        }
        return true;
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

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
