package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.block.Block;
import cn.nukkit.block.BlockCactus;
import cn.nukkit.block.BlockFire;
import cn.nukkit.block.BlockLava;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 启发式方块寻路器（A* 算法）。
 * <p>
 * 在 Minecraft 基岩版（Nukkit）的方块世界中，为实体计算从起点到终点的可行走路径。
 * 算法以"方块格"为单位进行搜索，综合考虑：
 * <ul>
 *   <li><b>可通行性</b>：脚部/头部空间是否畅通、脚下是否有支撑</li>
 *   <li><b>移动方式</b>：四向/八向平移、向上跳跃 1 格、安全下落</li>
 *   <li><b>启发估值</b>：可切换 {@link HeuristicType}，平衡精度与速度</li>
 *   <li><b>熔断保护</b>：{@link PathfinderOptions#getMaxSearchNodes()} 防止在无解地图上耗尽资源</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 本类是<b>无状态</b>的（所有可变状态都封装在单次 {@link #findPath} 调用的局部 {@link Search} 对象中），
 * 因此可作为 Spring 单例被多线程并发调用。
 *
 * <h3>算法要点</h3>
 * <ul>
 *   <li>使用 {@link PriorityQueue}（按 f = g + h 排序）+ {@link HashSet} 闭合表</li>
 *   <li>采用"惰性删除"策略：同一坐标可能多次入队，弹出时由闭合表过滤，首次弹出即最优（启发可采纳时）</li>
 *   <li>对角线移动做"切角检测"，避免实体穿过两个固体方块的夹角</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * PathFinder finder = new PathFinder();
 * PathResult result = finder.findPath(level, entity, target, new PathfinderOptions());
 * if (result.isSuccess()) {
 *     navigator.follow(entity, result);
 * }
 * }</pre>
 *
 * @see BlockNode
 * @see PathfinderOptions
 * @see PathResult
 */
public class PathFinder implements PathfindingStrategy {

    /** 四向水平偏移（不含对角线） */
    private static final int[][] DIRS_4 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    /** 八向水平偏移（含对角线） */
    private static final int[][] DIRS_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** 安全的最小 Y 边界，防止查询负高度导致异常 */
    private static final int MIN_Y = -64;
    /** 安全的最大 Y 边界 */
    private static final int MAX_Y = 320;

    // ========== PathfindingStrategy 接口实现 ==========

    @Override
    public String getName() {
        return "astar";
    }

    /**
     * {@inheritDoc}
     * <p>
     * 接口方法：接受通用 {@link PathfindingConfig}，内部 cast 为 {@link PathfinderOptions}。
     * 若传入的配置类型不匹配（如误传 {@link GreedyOptions}），降级为默认 A* 配置。
     */
    @Override
    public PathResult findPath(Level level, Vector3 start, Vector3 target, PathfindingConfig config) {
        PathfinderOptions opts = (config instanceof PathfinderOptions)
                ? (PathfinderOptions) config
                : new PathfinderOptions();
        return findPath(level, start, target, opts);
    }

    @Override
    public PathfinderOptions getDefaultConfig() {
        return new PathfinderOptions();
    }

    // ========== A* 寻路方法 ==========

    /**
     * 使用默认参数寻路。
     *
     * @param level  所在世界
     * @param start  起点（实体的脚部位置）
     * @param target 终点
     * @return 寻路结果
     */
    public PathResult findPath(Level level, Vector3 start, Vector3 target) {
        return findPath(level, start, target, new PathfinderOptions());
    }

    /**
     * 寻路主入口。
     *
     * @param level   所在世界
     * @param start   起点（实体的脚部位置）
     * @param target  终点
     * @param options 寻路参数
     * @return 寻路结果（永不为 null）
     */
    public PathResult findPath(Level level, Vector3 start, Vector3 target, PathfinderOptions options) {
        if (level == null || start == null || target == null) {
            return PathResult.failed(PathResult.Status.START_INVALID, 0, level);
        }
        if (options == null) {
            options = new PathfinderOptions();
        }
        return new Search(level, options).run(BlockNode.fromVector(start), BlockNode.fromVector(target));
    }

    /**
     * 单次寻路的可变状态封装，保证 {@link PathFinder} 外层无状态。
     */
    private static final class Search {

        private final Level level;
        private final PathfinderOptions opts;
        private final PriorityQueue<BlockNode> open;
        private final HashSet<BlockNode> closed;
        private final int[][] dirs;
        private int expanded;
        /** 边走边搜：记录离目标启发距离最近的已闭合节点 */
        private BlockNode bestNode;
        private double bestH = Double.POSITIVE_INFINITY;
        /** 起点的启发距离，用于判断部分路径是否比原地不动更有意义 */
        private double originH = Double.POSITIVE_INFINITY;

        Search(Level level, PathfinderOptions opts) {
            this.level = level;
            this.opts = opts;
            this.open = new PriorityQueue<>((a, b) -> Double.compare(a.fCost(), b.fCost()));
            this.closed = new HashSet<>();
            this.dirs = opts.isAllowDiagonal() ? DIRS_8 : DIRS_4;
        }

        PathResult run(BlockNode start, BlockNode goal) {
            // 起点修正：实体常生成在悬空位置（如玩家前方无地面），若起点不可站立，
            // 向下逐层寻找最近的可行走落点作为实际起点，避免整次搜索因悬空而失败。
            start = clampStartToGround(start);
            start.gCost = 0;
            start.hCost = heuristic(start, goal);
            this.originH = start.hCost;
            open.add(start);

            double reachSq = opts.getGoalReachRadius() * opts.getGoalReachRadius();

            while (!open.isEmpty()) {
                BlockNode current = open.poll();
                // 惰性删除：已闭合的过时条目直接跳过
                if (!closed.add(current)) {
                    continue;
                }

                // 边走边搜：追踪离目标启发距离最近的已闭合节点，用于失败时回退到部分路径
                if (current.hCost < bestH) {
                    bestH = current.hCost;
                    bestNode = current;
                }

                // 到达判定（水平距离）
                if (current.equals(goal) || current.horizontalDistanceSquared(goal) <= reachSq) {
                    return PathResult.success(reconstruct(current), current.gCost, expanded, level);
                }

                // 熔断
                if (++expanded > opts.getMaxSearchNodes()) {
                    return partialOrFailed(PathResult.Status.NODE_LIMIT_EXCEEDED);
                }

                // 扩展邻居
                for (int[] d : dirs) {
                    BlockNode neighbor = findLanding(current, d[0], d[1]);
                    if (neighbor == null || closed.contains(neighbor)) {
                        continue;
                    }
                    boolean diagonal = d[0] != 0 && d[1] != 0;
                    // 切角检测：对角线移动时，若两侧正交格均为固体，则禁止穿过夹角
                    if (diagonal && isCornerBlocked(current, d[0], d[1])) {
                        continue;
                    }
                    double stepCost = diagonal ? opts.getDiagonalCost() : 1.0;
                    int dy = neighbor.y - current.y;
                    if (dy > 0) {
                        stepCost += dy * 0.5; // 攀爬额外代价
                    }
                    // 外接评分器：叠加自定义代价（规避危险方块、偏好地形等）
                    if (opts.getStepCostFunction() != null) {
                        stepCost += opts.getStepCostFunction().extraCost(level, current, neighbor, diagonal, opts);
                        if (stepCost == Double.POSITIVE_INFINITY) {
                            continue; // 评分器判定该步禁止通行
                        }
                    }
                    // 下限保护：避免负代价破坏 A* 最优性
                    if (stepCost < 0.01) {
                        stepCost = 0.01;
                    }
                    neighbor.gCost = current.gCost + stepCost;
                    neighbor.hCost = heuristic(neighbor, goal);
                    neighbor.cameFrom = current;
                    open.add(neighbor);
                }
            }
            return partialOrFailed(PathResult.Status.NO_PATH);
        }

        /**
         * 失败时根据 {@link PathfinderOptions#isPartialOnFailure()} 决定返回失败还是部分路径。
         * <p>
         * 边走边搜模式下，若存在比起点更接近目标（启发距离更小）的已探索节点，
         * 则回溯其路径作为部分解（状态 {@link PathResult.Status#PARTIAL}），
         * 使实体仍能朝目标方向移动一段距离；否则返回真正的失败。
         */
        private PathResult partialOrFailed(PathResult.Status failStatus) {
            if (opts.isPartialOnFailure() && bestNode != null && bestH < originH) {
                return PathResult.partial(reconstruct(bestNode), bestNode.gCost, expanded, level);
            }
            return PathResult.failed(failStatus, expanded, level);
        }

        /**
         * 计算从 current 沿 (dx,dz) 方向移动的落脚点。
         * 依次尝试：同高度平移 → 向上跳跃 1 格 → 安全下落。
         */
        private BlockNode findLanding(BlockNode cur, int dx, int dz) {
            int nx = cur.x + dx;
            int nz = cur.z + dz;

            // 1. 同高度平移
            if (isWalkable(nx, cur.y, nz)) {
                return new BlockNode(nx, cur.y, nz);
            }

            // 2. 向上跳跃（前方脚部被固体阻挡，且跳上去可站立）
            if (opts.isAllowJump() && isSolid(nx, cur.y, nz)) {
                int jy = cur.y + 1;
                // 起跳点头部需有空间，落点需可站立
                if (isPassable(cur.x, jy, cur.z) && isWalkable(nx, jy, nz)) {
                    return new BlockNode(nx, jy, nz);
                }
            }

            // 3. 安全下落（脚下无支撑时逐层下探）
            for (int drop = 1; drop <= opts.getMaxDropHeight(); drop++) {
                int ly = cur.y - drop;
                if (ly < MIN_Y) {
                    break;
                }
                // 该层被固体阻挡，无法继续下落
                if (!isPassable(nx, ly, nz)) {
                    break;
                }
                // 该层可穿过，检查其下方是否有支撑 → 形成落脚点
                if (isWalkable(nx, ly, nz)) {
                    return new BlockNode(nx, ly, nz);
                }
            }
            return null;
        }

        /**
         * 切角检测：对角线移动 (dx,dz) 时，若两个正交相邻格均为固体，
         * 则实体无法穿过它们的夹角。
         */
        private boolean isCornerBlocked(BlockNode cur, int dx, int dz) {
            return isSolid(cur.x + dx, cur.y, cur.z) && isSolid(cur.x, cur.y, cur.z + dz);
        }

        /**
         * 判断 (x,y,z) 是否为有效落脚点（脚部位置）。
         * 条件：脚部及头部空间可穿过、非危险方块，且脚下为固体支撑。
         */
        private boolean isWalkable(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            if (!isPassable(x, y, z) || isDangerous(x, y, z)) {
                return false;
            }
            // 头部空间（entityHeight 包含脚部格，故额外检查 height-1 格）
            for (int h = 1; h < opts.getEntityHeight(); h++) {
                if (!isPassable(x, y + h, z) || isDangerous(x, y + h, z)) {
                    return false;
                }
            }
            // 脚下支撑
            return isSolid(x, y - 1, z);
        }

        /**
         * 起点修正：若起点不可站立（实体悬空），向下逐层寻找最近的可行走落点。
         * <p>
         * 实体生成时常位于悬空格（如玩家前方 3 格无地面），直接以其脚部坐标作为
         * A* 起点会导致 {@link #findLanding} 因周围无可站立格而搜索失败。
         * 本方法向下探测最多 {@code maxDropHeight + 4} 格，定位到第一个可站立点；
         * 找不到则原样返回（保持宽容策略，仍尝试搜索）。
         */
        private BlockNode clampStartToGround(BlockNode start) {
            if (isWalkable(start.x, start.y, start.z)) {
                return start;
            }
            int maxDrop = opts.getMaxDropHeight() + 4;
            for (int drop = 1; drop <= maxDrop; drop++) {
                int y = start.y - drop;
                if (y < MIN_Y) {
                    break;
                }
                if (isWalkable(start.x, y, start.z)) {
                    return new BlockNode(start.x, y, start.z);
                }
            }
            return start;
        }

        /**
         * 判断方块是否为危险方块（岩浆、火、仙人掌等），实体不应进入或站立其中。
         * <p>
         * 这些方块虽 {@link Block#canPassThrough()} 返回 true（液体/非固体可穿过），
         * 但进入会造成持续伤害，寻路时应视为不可通行，避免 AI 走进岩浆自焚。
         */
        private boolean isDangerous(int x, int y, int z) {
            Block b = getBlock(x, y, z);
            return b instanceof BlockLava || b instanceof BlockFire || b instanceof BlockCactus;
        }

        /** 该方块是否可被实体穿过（空气、水、草等） */
        private boolean isPassable(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            return getBlock(x, y, z).canPassThrough();
        }

        /** 该方块是否为实体不可穿过的固体（即可作为地面/障碍） */
        private boolean isSolid(int x, int y, int z) {
            if (y < MIN_Y || y > MAX_Y) {
                return false;
            }
            return !getBlock(x, y, z).canPassThrough();
        }

        private Block getBlock(int x, int y, int z) {
            return level.getBlock(x, y, z);
        }

        private double heuristic(BlockNode a, BlockNode b) {
            return opts.getHeuristic().estimate(a.x - b.x, a.y - b.y, a.z - b.z);
        }

        /** 从终点沿 cameFrom 回溯到起点，并反转为行进顺序 */
        private List<BlockNode> reconstruct(BlockNode end) {
            List<BlockNode> path = new ArrayList<>();
            for (BlockNode n = end; n != null; n = n.cameFrom) {
                path.add(n);
            }
            Collections.reverse(path);
            return path;
        }
    }
}
