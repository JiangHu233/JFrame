package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.level.Level;
import cn.nukkit.level.Position;

import java.util.Collections;
import java.util.List;

/**
 * 寻路结果：封装一次 A* 搜索的产出。
 * <p>
 * 无论成功与否都返回本对象（而非抛异常），调用方通过 {@link #isSuccess()} 判断，
 * 并可读取失败原因（{@link #status}）用于调试或回退决策。
 *
 * <h3>路径方向</h3>
 * {@link #nodes} 中<b>索引 0 为起点、末尾为终点</b>（已按行进顺序排列），
 * 可直接交给 {@code Navigator} 逐点行进。
 *
 * @see PathFinder
 */
public final class PathResult {

    /**
     * 寻路状态。
     */
    public enum Status {
        /** 成功找到完整路径 */
        SUCCESS,
        /** 起点与终点过近，无需寻路 */
        ALREADY_AT_GOAL,
        /** 起点本身不可站立（如悬空），无法开始 */
        START_INVALID,
        /** 达到最大搜索节点数仍未到达终点（熔断） */
        NODE_LIMIT_EXCEEDED,
        /** open 表耗尽且未到达终点（目标不可达） */
        NO_PATH,
        /** 预算耗尽或目标不可达时，回退到"离目标最近的已探索节点"的部分路径（边走边搜） */
        PARTIAL,
        /** 外部主动取消（如贪心寻路器的 {@code cancel()} 被调用），返回已走的部分路径 */
        CANCELLED
    }

    private final Status status;
    private final List<BlockNode> nodes;
    private final double totalCost;
    private final int expandedNodes;
    private final Level level;

    private PathResult(Status status, List<BlockNode> nodes, double totalCost, int expandedNodes, Level level) {
        this.status = status;
        this.nodes = nodes;
        this.totalCost = totalCost;
        this.expandedNodes = expandedNodes;
        this.level = level;
    }

    /**
     * 构造失败结果。
     */
    static PathResult failed(Status status, int expandedNodes, Level level) {
        return new PathResult(status, Collections.emptyList(), Double.POSITIVE_INFINITY, expandedNodes, level);
    }

    /**
     * 构造成功结果。
     */
    static PathResult success(List<BlockNode> nodes, double totalCost, int expandedNodes, Level level) {
        return new PathResult(Status.SUCCESS, nodes, totalCost, expandedNodes, level);
    }

    /**
     * 构造部分路径结果（边走边搜：预算耗尽或无解时，返回到离目标最近的已探索节点的路径）。
     */
    static PathResult partial(List<BlockNode> nodes, double totalCost, int expandedNodes, Level level) {
        return new PathResult(Status.PARTIAL, nodes, totalCost, expandedNodes, level);
    }

    /**
     * 构造取消结果（外部主动终止时，返回已走的部分路径）。
     */
    static PathResult cancelled(List<BlockNode> nodes, int expandedNodes, Level level) {
        double cost = nodes.isEmpty() ? Double.POSITIVE_INFINITY : nodes.size();
        return new PathResult(Status.CANCELLED, nodes, cost, expandedNodes, level);
    }

    /**
     * 是否成功找到路径。
     *
     * @return true 表示路径可用
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.ALREADY_AT_GOAL;
    }

    /**
     * 是否为部分路径（未到达目标，但提供了朝目标方向的可用路径）。
     *
     * @return true 表示状态为 PARTIAL
     */
    public boolean isPartial() {
        return status == Status.PARTIAL;
    }

    /**
     * 是否被外部主动取消（返回了已走的部分路径）。
     *
     * @return true 表示状态为 CANCELLED
     */
    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    /**
     * 是否存在可用路径（成功或部分路径）。
     * <p>
     * 边走边搜场景下，即使 {@link #isSuccess()} 为 false，只要本方法返回 true，
     * 就可以从 {@link #getNodes()} 取出路径让实体开始移动。
     *
     * @return true 表示有可行走的节点序列
     */
    public boolean hasPath() {
        return isSuccess() || isPartial() || isCancelled();
    }

    public Status getStatus() {
        return status;
    }

    /**
     * 获取路径节点列表（起点在前，终点在后）。失败时返回空列表。
     *
     * @return 不可变节点列表
     */
    public List<BlockNode> getNodes() {
        return nodes;
    }

    /**
     * 获取路径节点总数（含起终点）。
     *
     * @return 节点数，失败为 0
     */
    public int length() {
        return nodes.size();
    }

    public double getTotalCost() {
        return totalCost;
    }

    public int getExpandedNodes() {
        return expandedNodes;
    }

    public Level getLevel() {
        return level;
    }

    /**
     * 获取路径终点（最后一个节点对应的世界位置）。
     *
     * @return 终点位置，失败为 null
     */
    public Position getDestination() {
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(nodes.size() - 1).toPosition(level);
    }

    @Override
    public String toString() {
        return "PathResult{status=" + status + ", length=" + nodes.size()
                + ", cost=" + (totalCost == Double.POSITIVE_INFINITY ? "∞" : String.format("%.2f", totalCost))
                + ", expanded=" + expandedNodes + "}";
    }
}
