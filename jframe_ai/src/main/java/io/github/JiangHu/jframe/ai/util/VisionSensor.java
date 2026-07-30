package io.github.JiangHu.jframe.ai.util;

import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

/**
 * 视野感知（Vision Sense）工具：判断一个实体能否"看到"另一个实体。
 * <p>
 * 本类是 AI 感知层的核心，综合<b>三要素</b>判断视野：
 * <ol>
 *   <li><b>距离</b>：目标须在最大视野半径内（水平距离）</li>
 *   <li><b>视野角度（FOV）</b>：目标须落在观察者前方视野锥角范围内。
 *       通过实体朝向向量与"观察者→目标"方向的夹角判断</li>
 *   <li><b>视线遮挡</b>：观察者眼部到目标眼部之间无固体方块阻挡（委托 {@link LineOfSight}）</li>
 * </ol>
 * 三者全部满足时才返回 {@code true}。
 *
 * <h3>FOV 计算原理</h3>
 * Nukkit 基岩版 yaw 约定：<b>0° 朝 +Z（南），顺时针为正</b>。
 * 由此推导实体水平朝向单位向量为：
 * <pre>
 *   forward = ( -sin(yaw), cos(yaw) )    // (x, z) 分量
 * </pre>
 * 验证：yaw=0 → forward=(0,1) 朝 +Z ✓；yaw=90° → forward=(-1,0) 朝 -X ✓。
 * <p>
 * 目标方向单位向量 dir = (target - observer) / |target - observer|（水平）。
 * 两向量点积即为夹角余弦：{@code cos(θ) = forward · dir}。
 * 目标在视野锥内 ⟺ {@code cos(θ) ≥ cos(halfFov)}，即 θ ≤ halfFov。
 *
 * <h3>与 {@link LineOfSight} 的关系</h3>
 * {@link LineOfSight} 仅判断<b>两点间几何视线</b>是否被方块阻挡（无方向、无距离上限）。
 * 本类在其基础上叠加了<b>距离</b>与<b>朝向角度</b>约束，构成完整的"生物视野"模型。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 僵尸能否看到 16 格内、前方 90° 锥角范围的玩家
 * if (VisionSensor.canSee(zombie, player, 16, 90)) {
 *     ai.chase(zombie, () -> player);
 * }
 *
 * // 守卫塔 360° 感知（仅距离 + 视线，不限朝向）
 * if (VisionSensor.canSee360(guard, intruder, 32)) {
 *     alarm.trigger();
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 全部方法为<b>无状态纯计算</b>，仅读取实体与世界方块状态，线程安全。
 *
 * @see LineOfSight
 * @see BlockChecks
 */
public final class VisionSensor {

    /** 默认最大视野距离（方块） */
    public static final double DEFAULT_MAX_DISTANCE = 16.0;
    /** 默认全视野角度（度，如 90 表示前方 ±45° 锥角） */
    public static final double DEFAULT_FIELD_OF_VIEW = 90.0;
    /** 近似眼部高度（相对脚部，与 {@link io.github.JiangHu.jframe.ai.tactical.TacticalScanner} 一致） */
    private static final double EYE_HEIGHT = 1.5;

    private VisionSensor() {
    }

    /**
     * 判断 {@code observer} 能否看到 {@code target}（默认参数：16 格 / 90° FOV）。
     *
     * @param observer 观察者
     * @param target   被观察目标
     * @return true 表示目标在视野内（距离 + 角度 + 视线均满足）
     */
    public static boolean canSee(Entity observer, Entity target) {
        return canSee(observer, target, DEFAULT_MAX_DISTANCE, DEFAULT_FIELD_OF_VIEW);
    }

    /**
     * 判断 {@code observer} 能否看到 {@code target}（完整参数）。
     * <p>
     * 依次检查三要素，任一不满足即返回 false：
     * <ol>
     *   <li>水平距离 ≤ {@code maxDistance}</li>
     *   <li>目标方向与观察者朝向的夹角 ≤ {@code fieldOfViewDegrees / 2}</li>
     *   <li>眼部到眼部视线无固体方块阻挡</li>
     * </ol>
     *
     * @param observer           观察者
     * @param target             被观察目标
     * @param maxDistance        最大视野距离（方块，水平）
     * @param fieldOfViewDegrees 全视野角度（度）。≥360 表示全向视野（跳过角度判断）
     * @return true 表示目标在视野内
     */
    public static boolean canSee(Entity observer, Entity target,
                                 double maxDistance, double fieldOfViewDegrees) {
        if (observer == null || target == null) {
            return false;
        }
        Level level = observer.getLevel();
        if (level == null || target.getLevel() != level) {
            return false;
        }
        // 要素 1：距离
        double dist = horizontalDistance(observer, target);
        if (dist > maxDistance) {
            return false;
        }
        // 要素 2：视野角度（FOV ≥ 360 视为全向，跳过）
        if (fieldOfViewDegrees < 360.0) {
            double angle = angleTo(observer, target);
            if (angle > fieldOfViewDegrees / 2.0) {
                return false;
            }
        }
        // 要素 3：视线遮挡
        Vector3 fromEye = eyeOf(observer);
        Vector3 toEye = eyeOf(target);
        return LineOfSight.hasLineOfSight(level, fromEye, toEye);
    }

    /**
     * <b>全向视野</b>判断：仅检查距离与视线，不考虑朝向（360° 感知）。
     * <p>
     * 适用于哨塔、感知型实体等"无盲区"场景。
     *
     * @param observer    观察者
     * @param target      被观察目标
     * @param maxDistance 最大感知距离（方块，水平）
     * @return true 表示目标在感知范围内且视线畅通
     */
    public static boolean canSee360(Entity observer, Entity target, double maxDistance) {
        return canSee(observer, target, maxDistance, 360.0);
    }

    /**
     * 计算 {@code target} 相对 {@code observer} 朝向的水平夹角（度）。
     * <p>
     * 返回值范围 [0, 180]：0 表示目标正前方，90 表示正侧方，180 表示正后方。
     *
     * @param observer 观察者
     * @param target   目标
     * @return 夹角度数；若两实体重叠返回 0
     */
    public static double angleTo(Entity observer, Entity target) {
        if (observer == null || target == null) {
            return 180.0;
        }
        return angleBetween(observer.yaw, observer.x, observer.z, target.x, target.z);
    }

    /**
     * 获取实体水平朝向单位向量（仅 X、Z 分量）。
     * <p>
     * 基于 Nukkit yaw 约定（0° 朝 +Z，顺时针为正）：
     * <pre>forward = ( -sin(yaw), cos(yaw) )</pre>
     *
     * @param entity 实体
     * @return 长度 2 的数组 {@code [forwardX, forwardZ]}；实体为 null 返回 {@code (0, 1)}（朝 +Z）
     */
    public static double[] forwardVector(Entity entity) {
        if (entity == null) {
            return new double[]{0.0, 1.0};
        }
        return forwardVector(entity.yaw);
    }

    /**
     * 根据 yaw 计算水平朝向单位向量（纯数学，包级可见便于测试）。
     *
     * @param yawDegrees yaw 角度（Nukkit 约定：0° 朝 +Z，顺时针为正）
     * @return {@code [forwardX, forwardZ]}，始终为单位向量
     */
    static double[] forwardVector(double yawDegrees) {
        double yawRad = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(yawRad), Math.cos(yawRad)};
    }

    /**
     * 计算目标方向与观察者朝向的水平夹角（纯数学，包级可见便于测试）。
     *
     * @param yawDegrees 观察者 yaw
     * @param fromX      观察者 X
     * @param fromZ      观察者 Z
     * @param toX        目标 X
     * @param toZ        目标 Z
     * @return 夹角度数 [0, 180]
     */
    static double angleBetween(double yawDegrees, double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0e-4) {
            return 0.0;
        }
        // 目标方向单位向量
        double dirX = dx / dist;
        double dirZ = dz / dist;
        // 观察者朝向单位向量
        double[] forward = forwardVector(yawDegrees);
        // 点积 = cos(夹角)
        double dot = forward[0] * dirX + forward[1] * dirZ;
        // 钳制到 [-1, 1] 防止浮点误差导致 acos 返回 NaN
        if (dot > 1.0) {
            dot = 1.0;
        } else if (dot < -1.0) {
            dot = -1.0;
        }
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * 计算实体眼部位置（脚部 + {@link #EYE_HEIGHT}）。
     *
     * @param entity 实体
     * @return 眼部坐标
     */
    private static Vector3 eyeOf(Entity entity) {
        return new Vector3(entity.x, entity.y + EYE_HEIGHT, entity.z);
    }

    /**
     * 计算两实体间的水平距离（忽略 Y）。
     *
     * @param a 实体 A
     * @param b 实体 B
     * @return 水平距离
     */
    private static double horizontalDistance(Entity a, Entity b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
