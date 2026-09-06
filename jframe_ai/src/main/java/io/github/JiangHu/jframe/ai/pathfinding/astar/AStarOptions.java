package io.github.JiangHu.jframe.ai.pathfinding.astar;

import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import lombok.Getter;

/**
 * A* 寻路参数配置：控制 A* 搜索行为的各项阈值与开关。
 * <p>
 * 继承 {@link PathfindingConfig} 通用基类（entityHeight/entityWidth），
 * 追加 A* 专属参数（搜索上限、对角线、跳跃、启发函数等）。
 * <p>
 * 采用<b>可变 + 链式 setter</b>风格，便于在调用处快速定制：
 * <pre>{@code
 * AStarOptions opts = new AStarOptions()
 *         .setMaxSearchNodes(2000)
 *         .setAllowJump(true)
 *         .setHeuristic(HeuristicType.EUCLIDEAN);
 * PathResult result = pathFinder.findPath(level, start, target, opts);
 * }</pre>
 *
 * <h3>关键参数说明</h3>
 * <ul>
 *   <li>{@link #maxSearchNodes}：防止在巨大或无解地图上无限搜索的"熔断"上限</li>
 *   <li>{@link #allowDiagonal}/{@link #allowJump}：决定邻居扩展方式，影响路径形态与计算量</li>
 *   <li>{@link #maxDropHeight}：实体可安全下落的最大高度（超过则视为不可通行）</li>
 *   <li>{@link PathfindingConfig#getEntityHeight()}：用于判断头部空间，默认按普通生物（2 格高）</li>
 * </ul>
 *
 * @see AStarPathFinder
 * @see PathfindingConfig
 * @see PathfindingStrategy
 */
@Getter
public class AStarOptions extends PathfindingConfig {

    /**
     * 最大可扩展的节点数（熔断阈值）。
     * <p>
     * 一旦 open 表累计弹出节点达到此数仍未到达终点，寻路终止并返回失败。
     * 默认 1500，足以覆盖大多数战术寻路场景。
     */
    private int maxSearchNodes = 1500;

    /**
     * 是否允许对角线移动（八连通）。
     * <p>
     * 开启后路径更自然、更短，但需额外做"角切割"检测（避免穿过两个固体方块的夹角）。
     * 默认开启。
     */
    private boolean allowDiagonal = true;

    /**
     * 是否允许向上跳跃 1 格（攀爬）。
     * <p>
     * 开启后实体可翻越 1 格高的台阶/墙壁。默认开启。
     */
    private boolean allowJump = true;

    /**
     * 实体可安全下落的最大高度（方块数）。
     * <p>
     * 高度差为负且绝对值超过此值时，认为该方向不可通行（避免跳崖）。
     * 默认 3。
     */
    private int maxDropHeight = 3;

    /**
     * 启发函数类型。默认 {@link HeuristicType#OCTILE}（对角代价 √2 的八连通精确启发，
     * 与默认移动模型匹配；四向模式下仍为可采纳下界）。
     */
    private HeuristicType heuristic = HeuristicType.OCTILE;

    /**
     * 对角线移动代价（相对于直线 1.0）。
     * <p>
     * 理论上对角线代价为 √2 ≈ 1.414。略调高（如 1.5）可减少对角线路径、提升可读性。
     * 默认 1.414。
     */
    private double diagonalCost = 1.41421356;

    /**
     * 到达终点的判定半径（方块）。
     * <p>
     * 当弹出节点与终点的水平距离平方小于此值的平方时即视为到达，
     * 避免因终点本身不可站立而完全失败。默认 1（允许终点在相邻格）。
     */
    private double goalReachRadius = 1.0;

    /**
     * 外接评分器：自定义步进代价函数。
     * <p>
     * 设置后，A* 在计算邻居代价时会在基础移动代价之上叠加 {@link StepCostFunction#extraCost} 的返回值，
     * 可用于规避危险方块（岩浆/悬崖）、偏好特定地形（道路/草地）等。默认 null（不启用，使用纯几何代价）。
     *
     * @see StepCostFunction
     */
    private StepCostFunction stepCostFunction;

    public AStarOptions setMaxSearchNodes(int maxSearchNodes) {
        this.maxSearchNodes = Math.max(16, maxSearchNodes);
        return this;
    }

    public AStarOptions setAllowDiagonal(boolean allowDiagonal) {
        this.allowDiagonal = allowDiagonal;
        return this;
    }

    public AStarOptions setAllowJump(boolean allowJump) {
        this.allowJump = allowJump;
        return this;
    }

    public AStarOptions setMaxDropHeight(int maxDropHeight) {
        this.maxDropHeight = Math.max(1, maxDropHeight);
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 协变覆盖：返回 {@link AStarOptions} 以支持链式调用。
     */
    @Override
    public AStarOptions setEntityHeight(int entityHeight) {
        super.setEntityHeight(entityHeight);
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 协变覆盖：返回 {@link AStarOptions} 以支持链式调用。
     */
    @Override
    public AStarOptions setEntityWidth(int entityWidth) {
        super.setEntityWidth(entityWidth);
        return this;
    }

    public AStarOptions setHeuristic(HeuristicType heuristic) {
        this.heuristic = heuristic == null ? HeuristicType.OCTILE : heuristic;
        return this;
    }

    public AStarOptions setDiagonalCost(double diagonalCost) {
        this.diagonalCost = diagonalCost;
        return this;
    }

    public AStarOptions setGoalReachRadius(double goalReachRadius) {
        this.goalReachRadius = Math.max(0, goalReachRadius);
        return this;
    }

    /**
     * 设置外接评分器（链式）。
     *
     * @param stepCostFunction 自定义代价函数；null 表示禁用（使用默认几何代价）
     * @return this
     * @see StepCostFunction
     */
    public AStarOptions setStepCostFunction(StepCostFunction stepCostFunction) {
        this.stepCostFunction = stepCostFunction;
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 协变覆盖：返回 {@link AStarOptions} 以支持链式调用。
     */
    @Override
    public AStarOptions setPartialOnFailure(boolean partialOnFailure) {
        super.setPartialOnFailure(partialOnFailure);
        return this;
    }
}
