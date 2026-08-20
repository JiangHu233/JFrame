package io.github.JiangHu.jframe.ai.core.gaze;

import cn.nukkit.entity.Entity;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;

/**
 * 视角修正上下文:一次 {@link Gaze#apply} 调用的输入快照。
 * <p>
 * 不可变;由导航器每 tick 构造并传入。除实体与移动信息外,
 * 还提供按 Nukkit 角度约定({@code getDirectionVector} 推导)的角度纯函数,
 * 供各修正器与用户自定义实现复用。
 */
public final class GazeContext {

    /** 水平速度小于该值视为"未在移动",朝向保持不变 */
    private static final double MOVE_EPSILON = 1.0e-4;

    private final Entity entity;
    private final double motionX;
    private final double motionZ;
    /** 当前目标路径节点(段间空窗等场景可为 null) */
    private final AStarNode currentNode;

    /**
     * 构造上下文(由导航器调用)。
     *
     * @param entity      被导航实体
     * @param motionX     本 tick 水平速度 X 分量
     * @param motionZ     本 tick 水平速度 Z 分量
     * @param currentNode 当前目标路径节点,可 null
     */
    public GazeContext(Entity entity, double motionX, double motionZ, AStarNode currentNode) {
        this.entity = entity;
        this.motionX = motionX;
        this.motionZ = motionZ;
        this.currentNode = currentNode;
    }

    /**
     * 被导航的实体(写 yaw/headYaw/pitch 的目标)。
     *
     * @return 实体
     */
    public Entity entity() {
        return entity;
    }

    /**
     * 本 tick 水平速度 X 分量(导航器写入 motion 后的值)。
     *
     * @return 速度分量
     */
    public double motionX() {
        return motionX;
    }

    /**
     * 本 tick 水平速度 Z 分量。
     *
     * @return 速度分量
     */
    public double motionZ() {
        return motionZ;
    }

    /**
     * 当前目标路径节点。
     *
     * @return 节点;段间空窗(如异步续段计算中)为 null
     */
    public AStarNode currentNode() {
        return currentNode;
    }

    /**
     * 是否正在移动(水平速度非微小)。
     *
     * @return true 表示在移动
     */
    public boolean isMoving() {
        return Math.sqrt(motionX * motionX + motionZ * motionZ) >= MOVE_EPSILON;
    }

    /**
     * 移动方向的 yaw;未在移动时返回实体当前 yaw(保持朝向)。
     *
     * @return 移动方向 yaw(度)
     */
    public double movementYaw() {
        if (!isMoving()) {
            return entity.yaw;
        }
        return yawTowards(motionX, motionZ);
    }

    // ==================== 角度纯函数(Nukkit 约定) ====================

    /**
     * 朝水平向量 {@code (dx, dz)} 方向的 yaw。
     * <p>
     * Nukkit 约定:yaw=0 朝 +Z,顺时针为正(由 {@code getDirectionVector}
     * 的 {@code x=-sin(yaw), z=cos(yaw)} 反解)。
     *
     * @param dx 指向目标的 X 分量
     * @param dz 指向目标的 Z 分量
     * @return yaw(度)
     */
    public static double yawTowards(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * 朝三维向量 {@code (dx, dy, dz)} 方向的 pitch。
     * <p>
     * Nukkit 约定:上仰为负。
     *
     * @param dx 指向目标的 X 分量
     * @param dy 指向目标的 Y 分量(目标高度 - 视点高度)
     * @param dz 指向目标的 Z 分量
     * @return pitch(度)
     */
    public static double pitchTowards(double dx, double dy, double dz) {
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return Math.toDegrees(-Math.atan2(dy, horiz));
    }

    /**
     * 有向角度差,归一至 {@code [-180, 180)}:从 {@code from} 转到 {@code to} 最短路径的度数。
     *
     * @param from 起始角(度)
     * @param to   目标角(度)
     * @return 差值,正表示顺时针(Nukkit 正方向)
     */
    public static double angleDiff(double from, double to) {
        double d = (to - from) % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
