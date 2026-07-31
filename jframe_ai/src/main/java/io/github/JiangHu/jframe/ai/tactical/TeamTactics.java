package io.github.JiangHu.jframe.ai.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.util.BlockChecks;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队战术：协调<b>多个实体</b>的群体行为。
 * <p>
 * 与 {@link TacticalScanner}（单实体战术位置搜索）互补，本类面向"小队/群体"场景，
 * 为一组实体计算各自应前往的位置，使整体呈现出协同的战术队形。
 *
 * <h3>提供的团队战术</h3>
 * <ul>
 *   <li>{@link #flankTarget} —— <b>协同包抄</b>：多个实体从不同侧翼方向钳形接近同一目标</li>
 *   <li>{@link #surroundTarget} —— <b>包围</b>：实体均匀分布在目标周围 360°，形成环形包围</li>
 *   <li>{@link #rally} —— <b>集结</b>：实体汇聚到指定集结点，按 {@link FormationType} 阵型排列</li>
 *   <li>{@link #formationOffsets} —— <b>阵型偏移</b>：纯几何计算，给定阵型与人数返回各成员相对偏移</li>
 * </ul>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>所有方法均为<b>无状态纯计算</b>，返回的位置列表与输入 {@code members} 一一对应
 *      （{@code result.get(i)} 即 {@code members.get(i)} 的目标位置）。</li>
 *   <li>位置可站立性由 {@link BlockChecks#isStandable} 校验；不可站立的位置返回
 *       {@link TacticalPosition#empty()}，调用方可跳过或重试。</li>
 *   <li>本类只负责"算去哪"，实际移动交由 {@link io.github.JiangHu.jframe.ai.navigation.NavigatorManager}
 *       或 {@link io.github.JiangHu.jframe.ai.navigation.AnytimePathFinder} 驱动。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 三只僵尸协同包抄玩家
 * List<TacticalPosition> flankPositions = teamTactics.flankTarget(zombies, player, 6);
 * for (int i = 0; i < zombies.size(); i++) {
 *     TacticalPosition pos = flankPositions.get(i);
 *     if (pos.isPresent()) {
 *         navigatorManager.navigate(zombies.get(i),
 *                 pathFinder.findPath(level, zombies.get(i), pos.toLevelPosition(level)));
 *     }
 * }
 * }</pre>
 *
 * @see TacticalScanner
 * @see FormationType
 * @see TacticalPosition
 */
public class TeamTactics {

    /** 默认实体高度（占用的方块层数） */
    public static final int DEFAULT_ENTITY_HEIGHT = 2;
    /** 默认阵型间距（方块） */
    public static final double DEFAULT_SPACING = 2.0;

    /**
     * 默认包抄弧线跨度（度）：成员在目标<b>远侧</b>半圆（180°）上展开，
     * 弧线两端恰好落在目标的左右两翼，中间成员位于目标正后方。
     */
    public static final double DEFAULT_FLANK_ARC_DEGREES = 180.0;

    /**
     * 协同包抄：为一组实体分配不同的侧翼方向，从多个角度钳形接近 {@code target}。
     * <p>
     * 采用<b>统一参考 + 均匀分布</b>策略：先计算小队中心相对目标的方位（即小队来袭方向），
     * 再以"目标远侧"（小队的对面）为中心、{@link #DEFAULT_FLANK_ARC_DEGREES} 为跨度，
     * 将成员<b>均匀</b>铺在该弧线上。弧线两端恰好落在目标的左右两翼，中间成员位于目标后方，
     * 从而呈现两翼展开、合围目标的钳形 / 包围攻势。
     * <p>
     * 相比"按每个成员各自方位独立偏移"的旧策略，本方法保证成员<b>互不聚堆</b>、
     * 角度<b>均匀间隔</b>，视觉效果更协调。
     *
     * @param members 包抄成员列表
     * @param target   包抄目标
     * @param radius   包抄点距目标的距离（方块）
     * @return 各成员的包抄位置列表（与 members 一一对应）；不可站立处为 {@link TacticalPosition#empty()}
     * @see #flankTarget(List, Entity, double, double)
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target, double radius) {
        return flankTarget(members, target, radius, DEFAULT_FLANK_ARC_DEGREES);
    }

    /**
     * 协同包抄（指定弧线跨度）。
     * <p>
     * {@code arcSpanDegrees} 控制成员在目标远侧的展开角度：
     * <ul>
     *   <li>180°（默认 {@link #DEFAULT_FLANK_ARC_DEGREES}）：两端到两翼、中间在正后方，呈半圆合围</li>
     *   <li>越小：成员越集中在目标正后方</li>
     *   <li>越大（接近 360°）：近乎环形包围（接近 {@link #surroundTarget}）</li>
     * </ul>
     * <p>
     * <b>算法</b>：以小队平均位置为统一参考，计算其相对目标的方位 {@code rearAngle}（来袭方向），
     * 取 {@code rearAngle + π}（目标远侧）为弧线中心，按成员索引在 {@code [center - span/2, center + span/2]}
     * 上均匀采样（见 {@link #flankAngles}），保证角度间隔相等、无聚堆。
     *
     * @param members        包抄成员列表
     * @param target         包抄目标
     * @param radius         包抄点距目标的距离（方块）
     * @param arcSpanDegrees 包抄弧线跨度（度，钳制到 [10, 360]）
     * @return 各成员的包抄位置列表（与 members 一一对应）；不可站立处为 {@link TacticalPosition#empty()}
     */
    public List<TacticalPosition> flankTarget(List<Entity> members, Entity target,
                                              double radius, double arcSpanDegrees) {
        List<TacticalPosition> result = new ArrayList<>();
        if (members == null || members.isEmpty() || target == null) {
            return result;
        }
        Level level = target.getLevel();
        if (level == null) {
            for (int i = 0; i < members.size(); i++) {
                result.add(TacticalPosition.empty());
            }
            return result;
        }
        double r = Math.max(2, radius);
        int n = members.size();
        // 统一参考：小队中心相对目标的方位（小队来袭方向），全体成员共用，避免各自为政导致聚堆
        double rearAngle = squadRearAngle(members, target);
        double arcSpan = Math.toRadians(Math.max(10.0, Math.min(360.0, arcSpanDegrees)));
        double[] angles = flankAngles(n, rearAngle, arcSpan);
        for (int i = 0; i < n; i++) {
            Entity member = members.get(i);
            if (member == null) {
                result.add(TacticalPosition.empty());
                continue;
            }
            double angle = angles[i];
            double px = target.x + Math.cos(angle) * r;
            double pz = target.z + Math.sin(angle) * r;
            result.add(standableOrEmpty(level, px, pz, target.y, target.x, target.z, angle));
        }
        return result;
    }

    /**
     * 计算小队中心相对目标的方位角（弧度）：从目标指向成员平均位置（即小队来袭方向）。
     * 小队中心与目标重合时返回 0。包级可见以便单元测试。
     *
     * @param members 成员列表
     * @param target  目标
     * @return 来袭方位角（弧度）
     */
    static double squadRearAngle(List<Entity> members, Entity target) {
        double sumX = 0, sumZ = 0;
        int count = 0;
        for (Entity m : members) {
            if (m != null) {
                sumX += m.x;
                sumZ += m.z;
                count++;
            }
        }
        if (count == 0) {
            return 0.0;
        }
        double dx = sumX / count - target.x;
        double dz = sumZ / count - target.z;
        if (Math.abs(dx) < 1.0e-4 && Math.abs(dz) < 1.0e-4) {
            return 0.0;
        }
        return Math.atan2(dz, dx);
    }

    /**
     * 纯几何：在以"目标远侧"（{@code rearAngle + π}）为中心、跨度 {@code arcSpan} 的弧线上，
     * 将 {@code n} 个成员均匀分布，返回每个成员的绝对方位角（弧度）。
     * <p>
     * 相邻成员角度间隔恒为 {@code arcSpan / (n - 1)}（n ≥ 2），保证无聚堆。
     * 包级可见以便单元测试（不依赖世界方块）。
     *
     * @param n         成员数
     * @param rearAngle 小队来袭方位（目标→小队中心，弧度）
     * @param arcSpan   弧线跨度（弧度）
     * @return 长度为 n 的方位角数组（弧度）；n ≤ 0 时返回空数组
     */
    static double[] flankAngles(int n, double rearAngle, double arcSpan) {
        double[] angles = new double[Math.max(0, n)];
        if (n <= 0) {
            return angles;
        }
        // 弧线中心 = 目标远侧（小队的对面），使成员绕到目标侧后方合围
        double center = rearAngle + Math.PI;
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.5 : (double) i / (n - 1);
            angles[i] = center + (t - 0.5) * arcSpan;
        }
        return angles;
    }

    /**
     * 包围：将实体均匀分布在 {@code target} 周围 360°，形成环形包围。
     * <p>
     * 第 i 个实体的方位角为 {@code 2π·i/n}，距目标 {@code radius}。
     * 与 {@link #flankTarget}（集中在侧后方）不同，本方法全方位展开，适合围困、护卫。
     *
     * @param members 包围成员列表
     * @param target   被包围目标
     * @param radius   包围半径（方块）
     * @return 各成员的包围位置列表（与 members 一一对应）
     */
    public List<TacticalPosition> surroundTarget(List<Entity> members, Entity target, double radius) {
        List<TacticalPosition> result = new ArrayList<>();
        if (members == null || members.isEmpty() || target == null) {
            return result;
        }
        Level level = target.getLevel();
        if (level == null) {
            for (int i = 0; i < members.size(); i++) {
                result.add(TacticalPosition.empty());
            }
            return result;
        }
        double r = Math.max(2, radius);
        int n = members.size();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double px = target.x + Math.cos(angle) * r;
            double pz = target.z + Math.sin(angle) * r;
            result.add(standableOrEmpty(level, px, pz, target.y, target.x, target.z, angle));
        }
        return result;
    }

    /**
     * 集结：让实体汇聚到 {@code rallyPoint} 附近，按指定 {@link FormationType} 阵型排列。
     * <p>
     * 世界从第一个有效成员获取（假设全体同世界）。
     *
     * @param members   成员列表
     * @param rallyPoint 集结中心点
     * @param formation  阵型类型
     * @param spacing    成员间距（方块）
     * @return 各成员的集结位置列表（与 members 一一对应）
     * @see #formationOffsets
     */
    public List<TacticalPosition> rally(List<Entity> members, Vector3 rallyPoint,
                                        FormationType formation, double spacing) {
        List<TacticalPosition> result = new ArrayList<>();
        if (members == null || members.isEmpty() || rallyPoint == null || formation == null) {
            return result;
        }
        Level level = firstLevelOf(members);
        if (level == null) {
            for (int i = 0; i < members.size(); i++) {
                result.add(TacticalPosition.empty());
            }
            return result;
        }
        double space = Math.max(1, spacing);
        List<Vector3> offsets = formationOffsets(formation, members.size(), space);
        for (int i = 0; i < members.size(); i++) {
            Vector3 off = offsets.get(i);
            double px = rallyPoint.x + off.x;
            double pz = rallyPoint.z + off.z;
            result.add(standableOrEmpty(level, px, pz, rallyPoint.y, rallyPoint.x, rallyPoint.z, 0));
        }
        return result;
    }

    /**
     * 阵型偏移计算（纯几何）：给定阵型、人数、间距，返回每个成员相对中心的水平偏移。
     * <p>
     * 偏移以"中心在前（−Z 方向为朝向）、+X 为右"的局部坐标系给出，{@code y} 恒为 0。
     * 调用方可将其叠加到任意世界坐标（领队位置/集结点）上。
     *
     * @param formation   阵型类型
     * @param memberCount 成员数量
     * @param spacing     成员间距（方块）
     * @return 偏移列表（长度等于 memberCount）
     * @see FormationType
     */
    public List<Vector3> formationOffsets(FormationType formation, int memberCount, double spacing) {
        List<Vector3> offsets = new ArrayList<>();
        int n = Math.max(0, memberCount);
        double space = Math.max(1, spacing);
        if (n == 0 || formation == null) {
            return offsets;
        }
        switch (formation) {
            case LINE -> {
                // 横排：沿 X 轴对称分布，Z=0
                for (int i = 0; i < n; i++) {
                    double x = (i - (n - 1) / 2.0) * space;
                    offsets.add(new Vector3(x, 0, 0));
                }
            }
            case COLUMN -> {
                // 纵队：沿 Z 轴排列（成员在领队后方，Z 为正）
                for (int i = 0; i < n; i++) {
                    double z = (i - (n - 1) / 2.0) * space;
                    offsets.add(new Vector3(0, 0, z));
                }
            }
            case WEDGE -> {
                // 楔形：尖端在前（Z 负），两翼向后展开（|X| 与 Z 同步增大）
                for (int i = 0; i < n; i++) {
                    double fromCenter = i - (n - 1) / 2.0;
                    double x = fromCenter * space;
                    double z = Math.abs(fromCenter) * space * 0.7;
                    offsets.add(new Vector3(x, 0, z));
                }
            }
            case CIRCLE -> {
                // 圆形：均匀分布在半径 space 的圆周上
                double radius = space;
                for (int i = 0; i < n; i++) {
                    double angle = 2 * Math.PI * i / n;
                    offsets.add(new Vector3(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                }
            }
            case SQUARE -> {
                // 方阵：接近正方形的网格
                int cols = (int) Math.ceil(Math.sqrt(n));
                int rows = (int) Math.ceil((double) n / cols);
                for (int i = 0; i < n; i++) {
                    int row = i / cols;
                    int col = i % cols;
                    double x = (col - (cols - 1) / 2.0) * space;
                    double z = (row - (rows - 1) / 2.0) * space;
                    offsets.add(new Vector3(x, 0, z));
                }
            }
            default -> {
                for (int i = 0; i < n; i++) {
                    offsets.add(new Vector3(0, 0, 0));
                }
            }
        }
        return offsets;
    }

    // ==================== 内部工具 ====================

    /**
     * 在 (px,pz) 列上寻找可站立的 Y，构造 TacticalPosition；不可站立则返回 empty。
     */
    private TacticalPosition standableOrEmpty(Level level, double px, double pz,
                                              double refY, double refX, double refZ, double score) {
        int bx = (int) Math.floor(px);
        int bz = (int) Math.floor(pz);
        int by = findStandableY(level, bx, (int) Math.floor(refY), bz);
        if (by == Integer.MIN_VALUE) {
            return TacticalPosition.empty();
        }
        Vector3 pos = new Vector3(bx + 0.5, by, bz + 0.5);
        // distanceToThreat 记录该位置到参考点（目标/集结点）的水平距离
        double distToRef = Math.hypot(pos.x - refX, pos.z - refZ);
        return new TacticalPosition(pos, score, distToRef);
    }

    /**
     * 在指定列 (bx, bz) 上，以 centerY 为基准上下搜索可站立的 Y（同 TacticalScanner 逻辑）。
     */
    private int findStandableY(Level level, int bx, int centerY, int bz) {
        if (BlockChecks.isStandable(level, bx, centerY, bz, DEFAULT_ENTITY_HEIGHT)) {
            return centerY;
        }
        for (int dy = 1; dy <= 4; dy++) {
            if (BlockChecks.isStandable(level, bx, centerY + dy, bz, DEFAULT_ENTITY_HEIGHT)) {
                return centerY + dy;
            }
        }
        for (int dy = 1; dy <= 4; dy++) {
            if (BlockChecks.isStandable(level, bx, centerY - dy, bz, DEFAULT_ENTITY_HEIGHT)) {
                return centerY - dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 从成员列表中取第一个有效实体的世界。
     */
    private Level firstLevelOf(List<Entity> members) {
        for (Entity e : members) {
            if (e != null && e.getLevel() != null) {
                return e.getLevel();
            }
        }
        return null;
    }
}
