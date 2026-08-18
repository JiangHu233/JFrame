package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.util.BlockChecks;
import io.github.JiangHu.jframe.ai.core.util.BlockSnapshotCache;
import io.github.JiangHu.jframe.ai.core.util.LineOfSight;

/**
 * 战术位置扫描器：在实体周围搜索满足特定战术意图的候选位置。
 * <p>
 * 本类是 AI 战术层的核心，提供四种经典游戏 AI 战术：
 * <ul>
 *   <li>{@link #findCover} —— 找掩体：寻找能遮挡威胁视线的位置</li>
 *   <li>{@link #findFleePosition} —— 远离：寻找离威胁最远的可达位置</li>
 *   <li>{@link #findFlankPosition} —— 包抄：寻找位于目标侧方的位置，便于侧翼接近</li>
 *   <li>{@link #findHighGround} —— 寻找高地：寻找比当前位置更高的可站立位置</li>
 *   <li>{@link #findSightPosition} —— 占据视野点：寻找能看到目标的可站立位置（找掩体的反向操作）</li>
 *   <li>{@link #findApproximatePosition} —— 模糊位置选取：目标位置不精确时，在不确定区域内选取搜索点</li>
 * </ul>
 *
 * <h3>坐标版重载</h3>
 * {@link #findCover}、{@link #findFleePosition}、{@link #findFlankPosition}、{@link #findSightPosition}
 * 均提供以 {@link Vector3} 坐标为输入的重载版本。当目标不是某个实体（如仅知坐标、最后已知位置、
 * 投射落点）时，可直接传入坐标而无需构造虚拟实体。Entity 版本内部委托给坐标版本。
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>所有方法均为<b>无状态纯计算</b>，仅依赖输入实体与世界方块状态，线程安全。</li>
 *   <li>扫描采用"实体周围立方体邻域遍历"，复杂度约为 O(radius²)。建议在战术决策
 *       节点（而非每 tick）调用，避免性能压力。</li>
 *   <li>返回的 {@link TacticalPosition} 仅描述"去哪里"，实际移动由
 *       {@link io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager} 驱动。</li>
 *   <li>"可站立"判定统一委托 {@link BlockChecks#isStandable}，与寻路保持一致。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 实体版
 * TacticalPosition cover = scanner.findCover(self, threat, 8);
 * // 坐标版（仅需坐标）
 * TacticalPosition cover2 = scanner.findCover(self, new Vector3(100, 64, 200), 8);
 * if (cover.isPresent()) {
 *     navigatorManager.navigate(self, cover.toLevelPosition(self.getLevel()));
 * }
 * }</pre>
 */
public class TacticalScanner {

    /** 默认扫描半径（方块） */
    public static final double DEFAULT_RADIUS = 8.0;
    /** 默认实体高度（占用的方块层数） */
    public static final int DEFAULT_ENTITY_HEIGHT = 2;
    /** 默认理想观察距离（方块，用于 {@link #findSightPosition} 评分） */
    public static final double DEFAULT_IDEAL_SIGHT_DISTANCE = 10.0;
    /** 默认不确定半径（方块，用于 {@link #findApproximatePosition} 模糊位置选取） */
    public static final double DEFAULT_UNCERTAINTY_RADIUS = 5.0;
    /** 近似眼部高度（相对脚部，用于视线检测） */
    private static final double EYE_HEIGHT = 1.5;

    /**
     * 找掩体：在实体周围寻找能遮挡 {@code threat} 视线的可站立位置。
     *
     * @param self     需要掩体的实体
     * @param threat   威胁来源（如敌对实体）
     * @param radius   搜索半径（方块）
     * @return 最佳掩体位置；若无满足条件的位置返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Entity threat, double radius) {
        if (threat == null) {
            return TacticalPosition.empty();
        }
        return findCover(self, (Vector3) threat, radius);
    }

    /**
     * 找掩体（坐标版）：在实体周围寻找能遮挡指定坐标视线的可站立位置。
     *
     * @param self      需要掩体的实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径（方块）
     * @return 最佳掩体位置；若无满足条件的位置返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findCover(Entity self, Vector3 threatPos, double radius) {
        if (self == null || threatPos == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, radius);
        Vector3 threatEye = eyeOf(threatPos);
        // 单次扫描共享一份方块快照：findStandableY 与视线检测的重复查询本地命中
        BlockSnapshotCache cache = new BlockSnapshotCache(level);

        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(self.x) + dx;
                int bz = floor(self.z) + dz;
                int by = findStandableY(cache, bx, floor(self.y), bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                Vector3 candidateEye = new Vector3(candidate.x, candidate.y + EYE_HEIGHT, candidate.z);
                // 关键条件：威胁看不到候选位置
                if (LineOfSight.hasLineOfSight(cache, threatEye, candidateEye)) {
                    continue;
                }
                double distToThreat = horizontalDistance(candidate, threatPos);
                double distToSelf = horizontalDistance(candidate, self);
                // 评分：距威胁远 + 距自身近（权重可调）
                double score = distToThreat - distToSelf * 0.5;
                if (!best.isPresent() || score > best.score()) {
                    best = new TacticalPosition(candidate, score, distToThreat);
                }
            }
        }
        return best;
    }

    /**
     * 远离：在实体周围寻找离 {@code threat} 最远的可站立位置。
     *
     * @param self   逃跑实体
     * @param threat 威胁来源
     * @param radius 搜索半径（方块）
     * @return 离威胁最远的可达位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Entity threat, double radius) {
        if (threat == null) {
            return TacticalPosition.empty();
        }
        return findFleePosition(self, (Vector3) threat, radius);
    }

    /**
     * 远离（坐标版）：在实体周围寻找离指定坐标最远的可站立位置。
     *
     * @param self      逃跑实体
     * @param threatPos 威胁位置坐标
     * @param radius    搜索半径（方块）
     * @return 离威胁最远的可达位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFleePosition(Entity self, Vector3 threatPos, double radius) {
        if (self == null || threatPos == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, radius);
        BlockSnapshotCache cache = new BlockSnapshotCache(level);
        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(self.x) + dx;
                int bz = floor(self.z) + dz;
                int by = findStandableY(cache, bx, floor(self.y), bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                double distToThreat = horizontalDistance(candidate, threatPos);
                if (!best.isPresent() || distToThreat > best.score()) {
                    best = new TacticalPosition(candidate, distToThreat, distToThreat);
                }
            }
        }
        return best;
    }

    /**
     * 包抄：寻找位于 {@code target} 侧方的可站立位置，便于从侧翼接近目标。
     *
     * @param self   包抄发起实体
     * @param target 包抄目标
     * @param radius 搜索半径（方块）
     * @return 最佳侧翼位置；若无返回 {@link TacticalPosition#empty()}
     * @implNote 本方法面向<b>单实体</b>侧翼位置选择。若需为<b>一组实体</b>（团队/小队）协同包抄——
     *           即多个成员交替分配左/右翼、避免聚堆——请改用
     *           {@link io.github.JiangHu.jframe.ai.core.tactical.TeamTactics#flankTarget(java.util.List, Entity, double)}，
     *           它会以本方法的侧翼评分逻辑作为候选构建块，并叠加协同偏移。
     */
    public TacticalPosition findFlankPosition(Entity self, Entity target, double radius) {
        if (target == null) {
            return TacticalPosition.empty();
        }
        return findFlankPosition(self, (Vector3) target, radius);
    }

    /**
     * 包抄（坐标版）：寻找位于指定坐标侧方的可站立位置，便于从侧翼接近。
     *
     * @param self      包抄发起实体
     * @param targetPos 包抄目标位置坐标
     * @param radius    搜索半径（方块）
     * @return 最佳侧翼位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findFlankPosition(Entity self, Vector3 targetPos, double radius) {
        if (self == null || targetPos == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, radius);
        // 前向单位向量（自身→目标）
        double fx = targetPos.x - self.x;
        double fz = targetPos.z - self.z;
        double flen = Math.sqrt(fx * fx + fz * fz);
        if (flen < 1.0e-4) {
            return TacticalPosition.empty();
        }
        fx /= flen;
        fz /= flen;
        // 理想包抄距离：约等于当前距离的一半，便于逐步侧移接近
        double idealDist = Math.max(2.0, flen * 0.5);

        BlockSnapshotCache cache = new BlockSnapshotCache(level);
        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(self.x) + dx;
                int bz = floor(self.z) + dz;
                int by = findStandableY(cache, bx, floor(self.y), bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                // 候选相对自身的方向
                double cx = candidate.x - self.x;
                double cz = candidate.z - self.z;
                double clen = Math.sqrt(cx * cx + cz * cz);
                if (clen < 1.0e-4) {
                    continue;
                }
                // 与前向的点积：1=正前，0=正侧，-1=正后
                double dot = (cx * fx + cz * fz) / clen;
                double perpendicularity = 1.0 - Math.abs(dot);
                double distToTarget = horizontalDistance(candidate, targetPos);
                // 距离理想包抄距离的接近度（0=最佳）
                double distFit = 1.0 - Math.min(1.0, Math.abs(distToTarget - idealDist) / idealDist);
                // 评分：侧方程度为主，距离适配为辅
                double score = perpendicularity * 0.7 + distFit * 0.3;
                if (!best.isPresent() || score > best.score()) {
                    best = new TacticalPosition(candidate, score, distToTarget);
                }
            }
        }
        return best;
    }

    /**
     * 寻找高地：在实体周围寻找比当前位置更高的可站立位置。
     *
     * @param self         实体
     * @param radius       搜索半径（方块）
     * @param minAdvantage 最小高度优势阈值（方块），低于此值的位置不予考虑
     * @return 最佳高地位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findHighGround(Entity self, double radius, int minAdvantage) {
        if (self == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, radius);
        int selfY = floor(self.y);
        int threshold = Math.max(1, minAdvantage);
        BlockSnapshotCache cache = new BlockSnapshotCache(level);
        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(self.x) + dx;
                int bz = floor(self.z) + dz;
                int by = findStandableY(cache, bx, selfY, bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                int advantage = by - selfY;
                if (advantage < threshold) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                double distToSelf = horizontalDistance(candidate, self);
                // 评分：高度优势为主，距自身近为辅（便于快速占据）
                double score = advantage - distToSelf * 0.2;
                if (!best.isPresent() || score > best.score()) {
                    best = new TacticalPosition(candidate, score, distToSelf);
                }
            }
        }
        return best;
    }

    /**
     * 占据视野点：在实体周围寻找一个<b>能看到 {@code target}</b>的可站立位置。
     * <p>
     * 这是 {@link #findCover}（找掩体）的<b>反向操作</b>：找掩体寻找"看不到威胁"的位置，
     * 本方法寻找"能看到目标"的位置。适用于弓箭手/哨兵需要保持视线锁定目标的场景。
     *
     * @param self   需要视野的实体
     * @param target 观察目标
     * @param radius 搜索半径（方块）
     * @return 最佳视野位置；若无满足条件的位置返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius) {
        if (target == null) {
            return TacticalPosition.empty();
        }
        return findSightPosition(self, (Vector3) target, radius);
    }

    /**
     * 占据视野点（坐标版，默认理想观察距离）。
     *
     * @param self      需要视野的实体
     * @param targetPos 观察目标位置坐标
     * @param radius    搜索半径（方块）
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius) {
        return findSightPosition(self, targetPos, radius, DEFAULT_IDEAL_SIGHT_DISTANCE);
    }

    /**
     * 占据视野点（指定理想观察距离，实体版）。
     *
     * @param self           需要视野的实体
     * @param target         观察目标
     * @param radius         搜索半径（方块）
     * @param idealDistance  理想观察距离（方块，评分时距目标越接近此值越好）
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Entity target, double radius, double idealDistance) {
        if (target == null) {
            return TacticalPosition.empty();
        }
        return findSightPosition(self, (Vector3) target, radius, idealDistance);
    }

    /**
     * 占据视野点（坐标版，指定理想观察距离）。
     * <p>
     * <b>筛选条件</b>：候选位置眼部到目标眼部视线畅通（{@link LineOfSight#hasLineOfSight}）。
     * <p>
     * <b>评分</b>（越高越好）：距目标的水平距离越接近 {@code idealDistance} 越好（适配度，权重最高），
     * 距自身越近越好（便于快速到达，次要权重）。
     *
     * @param self           需要视野的实体
     * @param targetPos      观察目标位置坐标
     * @param radius         搜索半径（方块）
     * @param idealDistance  理想观察距离（方块）
     * @return 最佳视野位置；若无返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findSightPosition(Entity self, Vector3 targetPos, double radius, double idealDistance) {
        if (self == null || targetPos == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, radius);
        Vector3 targetEye = eyeOf(targetPos);
        double ideal = Math.max(1.0, idealDistance);
        // 单次扫描共享一份方块快照：findStandableY 与视线检测的重复查询本地命中
        BlockSnapshotCache cache = new BlockSnapshotCache(level);

        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(self.x) + dx;
                int bz = floor(self.z) + dz;
                int by = findStandableY(cache, bx, floor(self.y), bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                Vector3 candidateEye = new Vector3(candidate.x, candidate.y + EYE_HEIGHT, candidate.z);
                // 关键条件：从候选位置能看到目标
                if (!LineOfSight.hasLineOfSight(cache, candidateEye, targetEye)) {
                    continue;
                }
                double distToTarget = horizontalDistance(candidate, targetPos);
                double distToSelf = horizontalDistance(candidate, self);
                // 评分：距目标接近理想距离（适配度）为主，距自身近为辅
                double distFit = 1.0 - Math.min(1.0, Math.abs(distToTarget - ideal) / ideal);
                double score = distFit * 10.0 - distToSelf * 0.3;
                if (!best.isPresent() || score > best.score()) {
                    best = new TacticalPosition(candidate, score, distToTarget);
                }
            }
        }
        return best;
    }

    /**
     * <b>模糊位置选取</b>：当目标位置不精确（只知道大致区域）时，
     * 在不确定区域内选取一个可站立且距自身最近的搜索点。
     * <p>
     * <b>适用场景</b>：玩家未完全暴露位置——例如只听到脚步声、看到残影、
     * 或仅有"最后已知位置"。AI 无需精确到达某个方块，只需移动到目标大致所在区域
     * 即可开始搜查。
     * <p>
     * <b>算法</b>：以 {@code center} 为圆心、{@code uncertaintyRadius} 为半径做圆形邻域遍历，
     * 筛选可站立位置，选取距自身最近者（快速到达搜索区域）。
     *
     * @param self              搜索实体
     * @param center            目标大致位置（不确定区域圆心）
     * @param uncertaintyRadius 不确定半径（方块），目标可能在此圆内任意位置
     * @return 距自身最近的可站立搜索点；若不确定圆内无可站立位置返回 {@link TacticalPosition#empty()}
     */
    public TacticalPosition findApproximatePosition(Entity self, Vector3 center, double uncertaintyRadius) {
        if (self == null || center == null) {
            return TacticalPosition.empty();
        }
        Level level = self.getLevel();
        if (level == null) {
            return TacticalPosition.empty();
        }
        int r = (int) Math.max(1, uncertaintyRadius);
        double radius = Math.max(1.0, uncertaintyRadius);
        double radiusSq = radius * radius;
        int centerY = floor(center.y);

        BlockSnapshotCache cache = new BlockSnapshotCache(level);
        TacticalPosition best = TacticalPosition.empty();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int bx = floor(center.x) + dx;
                int bz = floor(center.z) + dz;
                // 圆形约束：候选必须在不确定圆内
                double offX = bx + 0.5 - center.x;
                double offZ = bz + 0.5 - center.z;
                if (offX * offX + offZ * offZ > radiusSq) {
                    continue;
                }
                int by = findStandableY(cache, bx, centerY, bz, DEFAULT_ENTITY_HEIGHT);
                if (by == Integer.MIN_VALUE) {
                    continue;
                }
                Vector3 candidate = new Vector3(bx + 0.5, by, bz + 0.5);
                double distToSelf = horizontalDistance(candidate, self);
                // 评分：距自身越近越好（快速到达搜索区域）
                double score = -distToSelf;
                if (!best.isPresent() || score > best.score()) {
                    double distToCenter = horizontalDistance(candidate, center);
                    best = new TacticalPosition(candidate, score, distToCenter);
                }
            }
        }
        return best;
    }

    /**
     * 判断实体是否已进入模糊目标区域（到达大概位置）。
     *
     * @param self              实体
     * @param center            目标大致位置（不确定区域圆心）
     * @param uncertaintyRadius 不确定半径（方块）
     * @return true 表示已进入不确定区域
     */
    public boolean hasReachedApproximate(Entity self, Vector3 center, double uncertaintyRadius) {
        if (self == null || center == null) {
            return false;
        }
        return hasReachedApproximate(self.x, self.z, center.x, center.z, uncertaintyRadius);
    }

    /**
     * 纯坐标版"是否到达模糊区域"（包级可见，便于单元测试，不依赖实体）。
     *
     * @param selfX             实体 X
     * @param selfZ             实体 Z
     * @param centerX           圆心 X
     * @param centerZ           圆心 Z
     * @param uncertaintyRadius 不确定半径（方块）
     * @return true 表示已进入不确定区域
     */
    static boolean hasReachedApproximate(double selfX, double selfZ,
                                         double centerX, double centerZ,
                                         double uncertaintyRadius) {
        double dx = selfX - centerX;
        double dz = selfZ - centerZ;
        double radius = Math.max(0.0, uncertaintyRadius);
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * 在指定列 (bx, bz) 上，以 {@code centerY} 为基准上下搜索一个可站立的 Y。
     * 优先同高，其次向上，再次向下（受限于合理范围）。
     * <p>
     * 方块查询经共享快照 {@code cache}（由调用方在一次扫描生命周期内创建），
     * 相邻列的高度探测大量重叠，缓存命中率高。
     *
     * @param cache        方块快照（调用方一次扫描的共享实例）
     * @param bx           方块 X
     * @param centerY      基准 Y（实体当前脚部）
     * @param bz           方块 Z
     * @param entityHeight 实体高度
     * @return 可站立的 Y；若该列无可站立位置返回 {@link Integer#MIN_VALUE}
     */
    private int findStandableY(BlockSnapshotCache cache, int bx, int centerY, int bz, int entityHeight) {
        // 同高
        if (BlockChecks.isStandable(cache, bx, centerY, bz, entityHeight)) {
            return centerY;
        }
        // 向上搜索 4 格
        for (int dy = 1; dy <= 4; dy++) {
            if (BlockChecks.isStandable(cache, bx, centerY + dy, bz, entityHeight)) {
                return centerY + dy;
            }
        }
        // 向下搜索 4 格
        for (int dy = 1; dy <= 4; dy++) {
            if (BlockChecks.isStandable(cache, bx, centerY - dy, bz, entityHeight)) {
                return centerY - dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 计算实体眼部位置 */
    private Vector3 eyeOf(Entity e) {
        return new Vector3(e.x + 0.5, e.y + EYE_HEIGHT, e.z + 0.5);
    }

    /** 计算坐标对应的眼部位置（坐标版战术方法使用） */
    private Vector3 eyeOf(Vector3 pos) {
        return new Vector3(pos.x + 0.5, pos.y + EYE_HEIGHT, pos.z + 0.5);
    }

    /** 水平距离（忽略 Y） */
    private double horizontalDistance(Vector3 a, Vector3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
