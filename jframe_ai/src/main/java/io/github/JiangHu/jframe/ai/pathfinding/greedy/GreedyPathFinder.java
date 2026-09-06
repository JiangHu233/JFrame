package io.github.JiangHu.jframe.ai.pathfinding.greedy;

import cn.nukkit.block.Block;
import cn.nukkit.block.BlockCactus;
import cn.nukkit.block.BlockFire;
import cn.nukkit.block.BlockLava;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarOptions;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 贪心局部步进寻路器（{@link PathfindingStrategy} 插槽的实现之一）。
 * <p>
 * 采用<b>自适应三阶段</b>架构，不全局搜索，而是递推选择下一步：
 * <ul>
 *   <li><b>阶段①快速贪心</b>：枚举 8 邻居，部分因子评分（距离+转向），O(8) 极快</li>
 *   <li><b>阶段②范围感知</b>：扩大窗口，舍伍德随机采样，全四因子评分+随机扰动</li>
 *   <li><b>阶段③恢复</b>：找到出路后回到阶段①</li>
 * </ul>
 *
 * <h3>设计哲学：短视 + 多次调用组合</h3>
 * 贪心是<b>短视</b>的，不适合一次性完成大圈绕行。每次 {@link #findPath} 调用最多走
 * {@link GreedyOptions#getMaxSteps()} 步，返回 PARTIAL 路径交给 Navigator 走完后，
 * 由上层（{@link io.github.JiangHu.jframe.ai.core.behavior.LoopBehavior}/调用方）从新位置重新调用，组合完成长距离寻路。
 *
 * <h3>熔断条件（不退化为 A*）</h3>
 * <ul>
 *   <li><b>步数上限</b>：step ≥ maxSteps → {@link PathResult.Status#PARTIAL}</li>
 *   <li><b>目标远离</b>：currentDist - startDist > maxDrift → {@link PathResult.Status#PARTIAL}</li>
 *   <li><b>区域穷尽</b>：窗口内全部采样完仍无出路 → {@link PathResult.Status#NO_PATH}</li>
 *   <li><b>外部取消</b>：{@link #cancel()} 被调用 → {@link PathResult.Status#CANCELLED}</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 外层无状态（可作单例），{@code cancelled} 标志为 volatile 支持跨线程取消。
 * 单次 {@link #findPath} 的可变状态封装在 {@link GreedySearch} 中。
 *
 * @see GreedyOptions
 * @see PathfindingStrategy
 * @see AStarPathFinder
 */
public class GreedyPathFinder implements PathfindingStrategy {

    /** 八向水平偏移（含对角线） */
    private static final int[][] DIRS_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    /** 外部取消标志（volatile 保证跨线程可见性） */
    private volatile boolean cancelled = false;

    /** 用于迷你 A* 可达性验证（无状态，可安全持有） */
    private final AStarPathFinder miniAStar = new AStarPathFinder();
    private final Random random = new Random();

    // ========== PathfindingStrategy 接口实现 ==========

    @Override
    public String getName() {
        return "greedy";
    }

    @Override
    public GreedyOptions getDefaultConfig() {
        return new GreedyOptions();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 接口方法：接受通用 {@link PathfindingConfig}，内部 cast 为 {@link GreedyOptions}。
     * 若传入的配置类型不匹配，降级为默认贪心配置。
     */
    @Override
    public PathResult findPath(Level level, Vector3 start, Vector3 target, PathfindingConfig config) {
        if (level == null || start == null || target == null) {
            return PathResult.failed(PathResult.Status.START_INVALID, 0, level);
        }
        GreedyOptions opts = (config instanceof GreedyOptions)
                ? (GreedyOptions) config
                : new GreedyOptions();
        // 每次寻路重置取消标志
        cancelled = false;
        return new GreedySearch(level, opts, this).run(
                AStarNode.fromVector(start), AStarNode.fromVector(target));
    }

    /**
     * 外部手动终止当前寻路。
     * <p>
     * 设置取消标志后，正在执行的 {@link #findPath} 会在下一步检查时返回
     * {@link PathResult.Status#CANCELLED}（附带已走的部分路径）。
     */
    public void cancel() {
        cancelled = true;
    }

    /** 供内部类检查取消状态 */
    boolean isCancelled() {
        return cancelled;
    }

    // =========================================================================
    // 单次贪心寻路的状态封装
    // =========================================================================

    /**
     * 单次贪心寻路的可变状态，保证 {@link GreedyPathFinder} 外层无状态。
     */
    private final class GreedySearch {

        private final Level level;
        private final GreedyOptions opts;
        private final GreedyPathFinder outer;

        // 寻路状态
        private final List<AStarNode> path = new ArrayList<>();
        private final VisitHistory visitHistory;
        private SubGoal subGoal = null;
        private double subGoalWeight = 0;
        private int stallCount1 = 0;
        private SamplingState samplingState = null;
        private int currentDirX = 0;
        private int currentDirZ = 0;

        GreedySearch(Level level, GreedyOptions opts, GreedyPathFinder outer) {
            this.level = level;
            this.opts = opts;
            this.outer = outer;
            this.visitHistory = new VisitHistory(opts.getVisitHistorySize());
        }

        PathResult run(AStarNode start, AStarNode target) {
            // 起点修正：若悬空则向下找落点
            start = clampStartToGround(start);
            path.add(start);
            visitHistory.record(start.x, start.y, start.z, 0);

            AStarNode current = start;
            double startDist = horizontalDistance(current, target);
            double lastRecordedDist = startDist;
            int step = 0;

            while (step < opts.getMaxSteps()) {
                step++;

                // 外部取消检查
                if (outer.isCancelled()) {
                    return PathResult.cancelled(path, step, level);
                }

                double currentDist = horizontalDistance(current, target);

                // 到达检查
                if (currentDist <= opts.getReachRadius()) {
                    return PathResult.success(path, path.size(), step, level);
                }

                // 目标远离熔断
                if (currentDist - startDist > opts.getMaxDrift()) {
                    return PathResult.partial(path, path.size(), step, level);
                }

                // 步数刷新：取得显著进展
                if (currentDist < lastRecordedDist - opts.getRefreshThreshold()) {
                    step = Math.max(0, step - opts.getRefreshAmount());
                }
                lastRecordedDist = currentDist;

                // 短期目标衰减
                if (subGoal != null) {
                    subGoalWeight *= opts.getSubGoalDecay();
                    if (subGoalWeight < opts.getSubGoalThreshold()) {
                        subGoal = null; // 自然淘汰
                    } else if (horizontalDistance(current, subGoal.position) <= opts.getReachRadius()) {
                        subGoal = null; // 到达清除
                    }
                }

                // 阶段调度
                AStarNode next;
                if (stallCount1 < opts.getStallThreshold1()) {
                    // 阶段①：快速贪心
                    next = fastGreedyStep(current, target);
                    if (next != null && horizontalDistance(next, target) < currentDist) {
                        stallCount1 = 0;
                    } else if (next != null) {
                        // 走了但没更近，不增加 stall（允许侧向移动）
                    } else {
                        stallCount1++;
                    }
                } else {
                    // 阶段②：范围感知
                    next = rangeAwareStep(current, target, step);
                    if (next != null) {
                        // 找到出路，重置回阶段①
                        stallCount1 = 0;
                        samplingState = null;
                    }
                }

                if (next == null) {
                    // 无法前进：如果阶段②也失败了，说明区域穷尽
                    if (stallCount1 >= opts.getStallThreshold1() && samplingState != null
                            && samplingState.isExhausted()) {
                        return PathResult.failed(PathResult.Status.NO_PATH, step, level);
                    }
                    stallCount1++;
                    continue;
                }

                // 记录行进方向
                currentDirX = next.x - current.x;
                currentDirZ = next.z - current.z;
                if (currentDirX == 0 && currentDirZ == 0) {
                    currentDirX = 1; // 避免零向量
                }

                current = next;
                path.add(current);
                visitHistory.record(current.x, current.y, current.z, step);
            }

            // 步数上限
            return PathResult.partial(path, path.size(), step, level);
        }

        // ===== 阶段①：快速贪心 =====

        /**
         * 枚举 8 邻居，用部分因子（距离+转向）评分，返回最优邻居。
         */
        private AStarNode fastGreedyStep(AStarNode current, AStarNode target) {
            List<AStarNode> neighbors = new ArrayList<>();
            for (int[] d : DIRS_8) {
                AStarNode landing = findLanding(current, d[0], d[1]);
                if (landing != null) {
                    neighbors.add(landing);
                }
            }
            if (neighbors.isEmpty()) {
                return null;
            }

            // 有效目标（短期目标权重混合）
            AStarNode effectiveTarget = getEffectiveTarget(target);

            AStarNode best = null;
            double bestScore = Double.MAX_VALUE;
            for (AStarNode n : neighbors) {
                double score = opts.getWDistance() * horizontalDistance(n, effectiveTarget)
                        + opts.getWTurn() * turnPenalty(n.x - current.x, n.z - current.z);
                if (score < bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
            return best;
        }

        // ===== 阶段②：范围感知 =====

        /**
         * 扩大窗口，舍伍德随机采样，全因子评分。
         * 采样跨步累积，完成后决策。
         */
        private AStarNode rangeAwareStep(AStarNode current, AStarNode target, int step) {
            // 首次进入：初始化采样
            if (samplingState == null) {
                samplingState = new SamplingState(current, opts.getWindowRadius());
            }

            // 本步采样 k 个可站立候选
            samplingState.sampleK(level, current, opts, this);

            // 区域穷尽检查
            if (samplingState.sampled.isEmpty() && samplingState.candidates.isEmpty()) {
                return null;
            }

            // 采样未完成：继续走已知次优邻居（不停下）
            if (!samplingState.isComplete(opts)) {
                return fastGreedyStep(current, target);
            }

            // 采样完成：全因子评分决策
            AStarNode effectiveTarget = getEffectiveTarget(target);

            List<ScoredCandidate> scored = new ArrayList<>();
            for (AStarNode candidate : samplingState.sampled) {
                double rawScore = scoreCandidate(candidate, current, effectiveTarget, step);
                // 随机扰动（舍伍德）
                double jitter = (random.nextDouble() * 2 - 1) * opts.getScoreJitter();
                double finalScore = rawScore * (1 + jitter);
                scored.add(new ScoredCandidate(candidate, finalScore));
            }
            scored.sort((a, b) -> Double.compare(a.score, b.score));

            // 尝试最优候选：迷你A*可达性验证
            for (ScoredCandidate sc : scored) {
                if (miniAStarReachable(current, sc.node)) {
                    // 检查是否需要设置短期目标（如果最优不可达但次优可达）
                    if (sc != scored.get(0) && subGoal == null) {
                        subGoal = new SubGoal(scored.get(0).node);
                        subGoalWeight = 1.0;
                    }
                    return sc.node;
                }
            }

            // 全部不可达：标记区域穷尽
            samplingState.markExhausted();
            return null;
        }

        /**
         * 全因子评分：距离 + 转向 + 访问惩罚 - 边界接近度（边界是奖励，减分）。
         */
        private double scoreCandidate(AStarNode candidate, AStarNode current,
                                       AStarNode target, int step) {
            double distScore = horizontalDistance(candidate, target);
            double turnScore = turnPenalty(candidate.x - current.x, candidate.z - current.z);
            double visitScore = visitHistory.penalty(candidate.x, candidate.y, candidate.z,
                    step, opts.getVisitDecayLambda());
            // 边界接近度：靠近边缘 → 值大 → 减分（奖励探索）
            double boundaryScore = samplingState.boundaryProximity(candidate);

            return opts.getWDistance() * distScore
                    + opts.getWTurn() * turnScore
                    + opts.getWVisit() * visitScore
                    - opts.getWBoundary() * boundaryScore;
        }

        // ===== 辅助方法 =====

        /**
         * 获取有效目标：短期目标权重混合（lerp 插值）。
         */
        private AStarNode getEffectiveTarget(AStarNode finalTarget) {
            if (subGoal == null || subGoalWeight <= 0) {
                return finalTarget;
            }
            // lerp(finalTarget, subGoal, weight)
            double w = subGoalWeight;
            int x = (int) (finalTarget.x * (1 - w) + subGoal.position.x * w);
            int z = (int) (finalTarget.z * (1 - w) + subGoal.position.z * w);
            int y = (int) (finalTarget.y * (1 - w) + subGoal.position.y * w);
            return new AStarNode(x, y, z);
        }

        /**
         * 转向惩罚：候选方向与当前行进方向的夹角（0=直行，最大=掉头）。
         */
        private double turnPenalty(int dx, int dz) {
            if (currentDirX == 0 && currentDirZ == 0) {
                return 0;
            }
            // 点积归一化 → cos(θ)，范围 [-1, 1]
            double len1 = Math.sqrt(currentDirX * currentDirX + currentDirZ * currentDirZ);
            double len2 = Math.sqrt(dx * dx + dz * dz);
            double cosAngle = (currentDirX * dx + currentDirZ * dz) / (len1 * len2);
            // cos=1 同向（惩罚0），cos=-1 反向（惩罚2）
            return 1 - cosAngle;
        }

        /**
         * 迷你 A* 可达性验证：限制节点数，检查 current 到 candidate 是否可达。
         */
        private boolean miniAStarReachable(AStarNode from, AStarNode to) {
            if (from.equals(to)) {
                return true;
            }
            AStarOptions miniOpts = new AStarOptions()
                    .setMaxSearchNodes(opts.getMiniANodeLimit())
                    .setAllowDiagonal(true)
                    .setAllowJump(true)
                    .setGoalReachRadius(1.5)
                    .setEntityHeight(opts.getEntityHeight());
            PathResult result = miniAStar.findPath(level,
                    new Vector3(from.x, from.y, from.z),
                    new Vector3(to.x, to.y, to.z),
                    miniOpts);
            return result.isSuccess();
        }

        /**
         * 计算从 current 沿 (dx,dz) 方向移动的落脚点（考虑跳跃和下落）。
         */
        private AStarNode findLanding(AStarNode cur, int dx, int dz) {
            int nx = cur.x + dx;
            int nz = cur.z + dz;

            // 1. 同高度平移
            int y = findStandableY(nx, cur.y, nz);
            if (y == cur.y && isWalkable(nx, cur.y, nz)) {
                return new AStarNode(nx, cur.y, nz);
            }

            // 2. 向上跳跃 1 格
            if (isPassable(cur.x, cur.y + 1, cur.z)) {
                int jy = cur.y + 1;
                if (isWalkable(nx, jy, nz)) {
                    return new AStarNode(nx, jy, nz);
                }
            }

            // 3. 安全下落
            for (int drop = 1; drop <= 3; drop++) {
                int ly = cur.y - drop;
                if (ly < MIN_Y || !isPassable(nx, ly, nz)) {
                    break;
                }
                if (isWalkable(nx, ly, nz)) {
                    return new AStarNode(nx, ly, nz);
                }
            }

            // 4. 高度差落脚（上坡/下坡 1 格）
            if (y != -1 && y != cur.y) {
                if (isWalkable(nx, y, nz)) {
                    return new AStarNode(nx, y, nz);
                }
            }

            return null;
        }

        /**
         * 在 (x, baseY, z) 列寻找可站立的 Y 坐标。
         *
         * @return 可站立的 Y，或 -1 表示该列不可站立
         */
        private int findStandableY(int x, int baseY, int z) {
            for (int dy = -2; dy <= 2; dy++) {
                int y = baseY + dy;
                if (y < MIN_Y || y > MAX_Y) {
                    continue;
                }
                if (isWalkable(x, y, z)) {
                    return y;
                }
            }
            return -1;
        }

        /**
         * 判断 (x,y,z) 是否为有效落脚点（容纳实体碰撞箱）。
         */
        private boolean isWalkable(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            // 脚部和头部空间
            for (int h = 0; h < opts.getEntityHeight(); h++) {
                if (!isPassable(x, y + h, z) || isDangerous(x, y + h, z)) {
                    return false;
                }
            }
            // 宽度 > 1 时检查周围列
            for (int w = 1; w < opts.getEntityWidth(); w++) {
                // 检查 x+w 和 z+w 方向的列（简化：只检查正方向）
                if (!isPassable(x + w, y, z) || !isPassable(x, y, z + w)) {
                    return false;
                }
            }
            // 脚下支撑
            return isSolid(x, y - 1, z);
        }

        private boolean isDangerous(int x, int y, int z) {
            Block b = getBlock(x, y, z);
            return b instanceof BlockLava || b instanceof BlockFire || b instanceof BlockCactus;
        }

        private boolean isPassable(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            return getBlock(x, y, z).canPassThrough();
        }

        private boolean isSolid(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            return !getBlock(x, y, z).canPassThrough();
        }

        private Block getBlock(int x, int y, int z) {
            return level.getBlock(x, y, z);
        }

        /**
         * 起点修正：若起点不可站立，向下逐层寻找落点。
         */
        private AStarNode clampStartToGround(AStarNode start) {
            if (isWalkable(start.x, start.y, start.z)) {
                return start;
            }
            for (int drop = 1; drop <= 7; drop++) {
                int y = start.y - drop;
                if (y < MIN_Y) {
                    break;
                }
                if (isWalkable(start.x, y, start.z)) {
                    return new AStarNode(start.x, y, start.z);
                }
            }
            return start;
        }

        private double horizontalDistance(AStarNode a, AStarNode b) {
            double dx = a.x - b.x;
            double dz = a.z - b.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    // =========================================================================
    // 辅助内部类
    // =========================================================================

    /** 带分数的候选（用于排序） */
    private static final class ScoredCandidate {
        final AStarNode node;
        final double score;

        ScoredCandidate(AStarNode node, double score) {
            this.node = node;
            this.score = score;
        }
    }

    /**
     * 访问历史：滑动窗口 + 时间衰减。
     * <p>
     * 记录最近 K 步的位置，用于计算时间衰减访问惩罚。
     * 近期位置惩罚大（防振荡），远期遗忘（允许回退）。
     */
    private static final class VisitHistory {
        private final int[] xs;
        private final int[] ys;
        private final int[] zs;
        private final int[] steps;
        private int head = 0;
        private int size = 0;

        VisitHistory(int capacity) {
            this.xs = new int[capacity];
            this.ys = new int[capacity];
            this.zs = new int[capacity];
            this.steps = new int[capacity];
        }

        void record(int x, int y, int z, int step) {
            xs[head] = x;
            ys[head] = y;
            zs[head] = z;
            steps[head] = step;
            head = (head + 1) % xs.length;
            if (size < xs.length) {
                size++;
            }
        }

        /**
         * 计算位置 (x,y,z) 的时间衰减访问惩罚。
         */
        double penalty(int x, int y, int z, int currentStep, double lambda) {
            double penalty = 0;
            for (int i = 0; i < size; i++) {
                int age = currentStep - steps[i];
                if (age < 0) {
                    age = 0;
                }
                double decay = Math.exp(-lambda * age);
                int dx = x - xs[i];
                int dz = z - zs[i];
                double dist = Math.sqrt(dx * dx + dz * dz);
                double weight = 1.0 / (1.0 + dist);
                penalty += weight * decay;
            }
            return penalty;
        }
    }

    /**
     * 采样状态：跨步累积的舍伍德随机采样。
     */
    private static final class SamplingState {
        /** 待采样的候选列坐标（x,z 对） */
        private final List<int[]> candidates;
        /** 已采样的可站立候选 */
        private final List<AStarNode> sampled = new ArrayList<>();
        /** 已采样列的集合（防重复），存储 "x,z" 字符串 */
        private final java.util.Set<String> sampledSet = new java.util.HashSet<>();
        /** 窗口中心 */
        private final int centerX;
        private final int centerZ;
        private final int radius;
        /** 当前最优分数 */
        private double bestScore = Double.MAX_VALUE;
        /** 连续无更优采样次数 */
        private int noImproveCount = 0;
        /** 最近采样的分数（算方差用） */
        private final double[] recentScores;
        private int recentCount = 0;
        private int recentHead = 0;
        private boolean exhausted = false;

        SamplingState(AStarNode center, int radius) {
            this.centerX = center.x;
            this.centerZ = center.z;
            this.radius = radius;
            this.candidates = new ArrayList<>();
            this.recentScores = new double[10]; // 固定大小循环缓冲

            // 枚举窗口内所有列
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue; // 跳过中心
                    }
                    candidates.add(new int[]{center.x + dx, center.z + dz});
                }
            }
            // 打乱顺序（舍伍德随机化）
            java.util.Collections.shuffle(candidates);
        }

        /**
         * 采样 k 个可站立候选。
         */
        void sampleK(Level level, AStarNode current, GreedyOptions opts, GreedySearch search) {
            int count = 0;
            int limit = opts.getSamplesPerTick();
            while (count < limit && !candidates.isEmpty()) {
                int[] col = candidates.remove(candidates.size() - 1);
                int cx = col[0];
                int cz = col[1];
                String key = cx + "," + cz;
                if (sampledSet.contains(key)) {
                    continue;
                }

                // 寻找可站立的 Y
                int y = findStandableYForColumn(level, cx, current.y, cz, opts, search);
                if (y < 0) {
                    continue; // 不可站立，不计入 k
                }

                AStarNode node = new AStarNode(cx, y, cz);
                sampled.add(node);
                sampledSet.add(key);
                count++;

                // 更新终止条件统计
                double dist = horizontalDist(node, current);
                addRecentScore(dist);
                if (dist < bestScore) {
                    bestScore = dist;
                    noImproveCount = 0;
                } else {
                    noImproveCount++;
                }
            }
        }

        /**
         * 三重终止条件。
         */
        boolean isComplete(GreedyOptions opts) {
            if (noImproveCount >= opts.getNoImproveLimit()) {
                return true; // 最优值稳定
            }
            if (recentCount >= opts.getVarianceWindow()) {
                double var = variance(opts.getVarianceWindow());
                if (var < opts.getVarianceFloor()) {
                    return true; // 方差收敛
                }
            }
            return candidates.isEmpty(); // 预算上限
        }

        boolean isExhausted() {
            return exhausted;
        }

        void markExhausted() {
            exhausted = true;
        }

        /**
         * 边界接近度：候选到窗口中心的切比雪夫距离 / 半径。
         */
        double boundaryProximity(AStarNode candidate) {
            int chebyshev = Math.max(Math.abs(candidate.x - centerX), Math.abs(candidate.z - centerZ));
            return (double) chebyshev / radius;
        }

        private int findStandableYForColumn(Level level, int x, int baseY, int z,
                                             GreedyOptions opts, GreedySearch search) {
            // 复用 GreedySearch 的 isWalkable 逻辑
            for (int dy = -opts.getWindowHeight(); dy <= opts.getWindowHeight(); dy++) {
                int y = baseY + dy;
                if (y < MIN_Y || y > MAX_Y) {
                    continue;
                }
                if (search.isWalkable(x, y, z)) {
                    return y;
                }
            }
            return -1;
        }

        private void addRecentScore(double score) {
            recentScores[recentHead] = score;
            recentHead = (recentHead + 1) % recentScores.length;
            if (recentCount < recentScores.length) {
                recentCount++;
            }
        }

        private double variance(int window) {
            if (recentCount < 2) {
                return Double.MAX_VALUE;
            }
            int n = Math.min(window, recentCount);
            double sum = 0;
            int start = (recentHead - n + recentScores.length) % recentScores.length;
            for (int i = 0; i < n; i++) {
                sum += recentScores[(start + i) % recentScores.length];
            }
            double mean = sum / n;
            double sqSum = 0;
            for (int i = 0; i < n; i++) {
                double diff = recentScores[(start + i) % recentScores.length] - mean;
                sqSum += diff * diff;
            }
            return sqSum / n;
        }

        private double horizontalDist(AStarNode a, AStarNode b) {
            double dx = a.x - b.x;
            double dz = a.z - b.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /**
     * 短期目标：评分最高但 A* 不可达的候选，作为跳板引导绕路。
     */
    private static final class SubGoal {
        final AStarNode position;

        SubGoal(AStarNode position) {
            this.position = position;
        }
    }
}
