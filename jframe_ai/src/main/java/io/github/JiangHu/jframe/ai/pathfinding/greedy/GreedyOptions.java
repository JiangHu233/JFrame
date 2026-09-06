package io.github.JiangHu.jframe.ai.pathfinding.greedy;

import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import lombok.Getter;

/**
 * 贪心步进寻路配置：控制 {@link GreedyPathFinder} 的三阶段自适应行为。
 * <p>
 * 继承 {@link PathfindingConfig} 通用基类（entityHeight/entityWidth），
 * 追加贪心专属参数。参数按功能分组：
 *
 * <h3>参数分组</h3>
 * <ul>
 *   <li><b>阶段①快速贪心</b>：{@link #stallThreshold1}（无进展阈值）</li>
 *   <li><b>阶段②范围感知</b>：窗口半径、采样频率、终止条件、随机扰动</li>
 *   <li><b>短期目标</b>：迷你A*验证、吸引力衰减</li>
 *   <li><b>熔断</b>：{@link #maxDrift}（目标远离上限）</li>
 *   <li><b>步数刷新</b>：进展刷新机制</li>
 *   <li><b>评分权重</b>：四因子权重、访问衰减</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * GreedyOptions opts = new GreedyOptions()
 *         .setWindowRadius(6)
 *         .setSamplesPerTick(12)
 *         .setMaxSteps(20);
 * GreedyPathFinder greedy = new GreedyPathFinder();
 * PathResult result = greedy.findPath(level, start, target, opts);
 * }</pre>
 *
 * @see GreedyPathFinder
 * @see PathfindingConfig
 * @see PathfindingStrategy
 */
@Getter
public class GreedyOptions extends PathfindingConfig {

    // ========== 阶段①：快速贪心 ==========

    /**
     * 阶段①连续无进展的步数阈值。
     * <p>
     * 当快速贪心连续此步数未能更接近目标时，切换到阶段②（范围感知）。
     * 默认 3。
     */
    private int stallThreshold1 = 3;

    // ========== 阶段②：范围感知 ==========

    /**
     * 观察窗口水平半径（方块）。
     * <p>
     * 阶段②在此半径范围内采样候选位置。半径越大感知越远，但采样开销也越大。
     * 受 {@link #maxWindowRadius} 硬上限约束。默认 4。
     */
    private int windowRadius = 4;

    /**
     * 观察窗口水平半径的<b>硬上限</b>（方块）。
     * <p>
     * 防止窗口无限扩大导致采样开销失控。默认 8。
     */
    private int maxWindowRadius = 8;

    /**
     * 观察窗口垂直半径（方块）。
     * <p>
     * 在此高度范围内搜索可站立的 Y 坐标。默认 2。
     */
    private int windowHeight = 2;

    /**
     * 每步必须采满的可站立候选数 k。
     * <p>
     * 阶段②每步从窗口中随机采样，直到收集到此数量的<b>可站立</b>候选。
     * 不可站立的位置跳过，不计入 k。默认 8。
     */
    private int samplesPerTick = 8;

    /**
     * 最优值稳定阈值：连续此次数未发现更优候选即停止采样。
     * <p>
     * 三重终止条件之一。默认 3。
     */
    private int noImproveLimit = 3;

    /**
     * 方差收敛窗口：计算最近此次数采样的分数方差。
     * <p>
     * 三重终止条件之一。默认 5。
     */
    private int varianceWindow = 5;

    /**
     * 方差收敛下限：方差低于此值即停止采样。
     * <p>
     * 三重终止条件之一。默认 0.1。
     */
    private double varianceFloor = 0.1;

    /**
     * 评分随机扰动幅度 δ（舍伍德随机化）。
     * <p>
     * 最终分数 = 原始分数 × (1 + ε)，ε ∈ [-δ, +δ]。
     * 用于打破平局和确定性，避免被特定地形卡住。默认 0.05（5%）。
     */
    private double scoreJitter = 0.05;

    // ========== 短期目标 ==========

    /**
     * 迷你A*可达性验证的节点上限。
     * <p>
     * 对评分最高的候选，用限制节点数的迷你A*验证是否可达。
     * 默认 100。
     */
    private int miniANodeLimit = 100;

    /**
     * 短期目标吸引力每步衰减系数。
     * <p>
     * 短期目标的吸引力权重每步乘以此系数，实现时间衰减淘汰。
     * 默认 0.9。
     */
    private double subGoalDecay = 0.9;

    /**
     * 短期目标自然淘汰的权重阈值。
     * <p>
     * 当吸引力权重衰减到此值以下时，清除短期目标，回归最终目标。
     * 默认 0.1。
     */
    private double subGoalThreshold = 0.1;

    // ========== 熔断 ==========

    /**
     * 目标远离熔断阈值（方块）。
     * <p>
     * 当当前位置到目标的距离超过起始距离此值时，返回 PARTIAL。
     * 用于检测贪心已偏离目标太远，交给上层重新调用。
     * 默认 12。
     */
    private double maxDrift = 12;

    // ========== 步数刷新 ==========

    /**
     * 单次贪心最大步数。
     * <p>
     * 达到此步数后返回 PARTIAL（短视路径），由上层重新调用。
     * 可被 {@link #refreshThreshold}/{@link #refreshAmount} 刷新。
     * 默认 12。
     */
    private int maxSteps = 12;

    /**
     * 步数刷新触发阈值（方块）。
     * <p>
     * 当离目标的距离比上次记录近此值时，刷新步数预算。
     * 默认 2。
     */
    private double refreshThreshold = 2;

    /**
     * 步数刷新回退量。
     * <p>
     * 触发刷新时，步数计数回退此值，延长寻路预算。
     * 默认 3。
     */
    private int refreshAmount = 3;

    // ========== 评分权重 ==========

    /**
     * 主因子权重：启发距离（到目标的估值）。
     * <p>
     * 权重最大，保证方向性。默认 1.0。
     */
    private double wDistance = 1.0;

    /**
     * 转向惩罚权重：候选方向与当前行进方向的夹角。
     * <p>
     * 减少锯齿走位。默认 0.3。
     */
    private double wTurn = 0.3;

    /**
     * 时间衰减访问惩罚权重。
     * <p>
     * 近期走过的位置惩罚大（防振荡），远期遗忘（允许回退）。
     * 默认 2.0。
     */
    private double wVisit = 2.0;

    /**
     * 边界接近度权重。
     * <p>
     * 靠近窗口边缘的候选加分（鼓励探索未知区域）。
     * 默认 2.0。
     */
    private double wBoundary = 2.0;

    /**
     * 访问惩罚指数衰减速率 λ。
     * <p>
     * 时间衰减函数 e^(-λ×年龄) 的参数。λ 越大遗忘越快。
     * 默认 0.2。
     */
    private double visitDecayLambda = 0.2;

    /**
     * 访问历史滑动窗口大小。
     * <p>
     * 只保留最近此步数的位置记录。默认 20。
     */
    private int visitHistorySize = 20;

    // ========== 通用 ==========

    /**
     * 到达判定半径（方块）。
     * <p>
     * 当前位置到目标的水平距离 ≤ 此值时视为到达。默认 1.5。
     */
    private double reachRadius = 1.5;

    // ========== 协变 setter（链式调用） ==========

    @Override
    public GreedyOptions setEntityHeight(int entityHeight) {
        super.setEntityHeight(entityHeight);
        return this;
    }

    @Override
    public GreedyOptions setEntityWidth(int entityWidth) {
        super.setEntityWidth(entityWidth);
        return this;
    }

    public GreedyOptions setStallThreshold1(int stallThreshold1) {
        this.stallThreshold1 = Math.max(1, stallThreshold1);
        return this;
    }

    public GreedyOptions setWindowRadius(int windowRadius) {
        this.windowRadius = Math.max(1, Math.min(windowRadius, maxWindowRadius));
        return this;
    }

    public GreedyOptions setMaxWindowRadius(int maxWindowRadius) {
        this.maxWindowRadius = Math.max(1, maxWindowRadius);
        return this;
    }

    public GreedyOptions setWindowHeight(int windowHeight) {
        this.windowHeight = Math.max(1, windowHeight);
        return this;
    }

    public GreedyOptions setSamplesPerTick(int samplesPerTick) {
        this.samplesPerTick = Math.max(1, samplesPerTick);
        return this;
    }

    public GreedyOptions setNoImproveLimit(int noImproveLimit) {
        this.noImproveLimit = Math.max(1, noImproveLimit);
        return this;
    }

    public GreedyOptions setVarianceWindow(int varianceWindow) {
        this.varianceWindow = Math.max(1, varianceWindow);
        return this;
    }

    public GreedyOptions setVarianceFloor(double varianceFloor) {
        this.varianceFloor = Math.max(0, varianceFloor);
        return this;
    }

    public GreedyOptions setScoreJitter(double scoreJitter) {
        this.scoreJitter = Math.max(0, scoreJitter);
        return this;
    }

    public GreedyOptions setMiniANodeLimit(int miniANodeLimit) {
        this.miniANodeLimit = Math.max(10, miniANodeLimit);
        return this;
    }

    public GreedyOptions setSubGoalDecay(double subGoalDecay) {
        this.subGoalDecay = Math.max(0, Math.min(1, subGoalDecay));
        return this;
    }

    public GreedyOptions setSubGoalThreshold(double subGoalThreshold) {
        this.subGoalThreshold = Math.max(0, Math.min(1, subGoalThreshold));
        return this;
    }

    public GreedyOptions setMaxDrift(double maxDrift) {
        this.maxDrift = Math.max(1, maxDrift);
        return this;
    }

    public GreedyOptions setMaxSteps(int maxSteps) {
        this.maxSteps = Math.max(1, maxSteps);
        return this;
    }

    public GreedyOptions setRefreshThreshold(double refreshThreshold) {
        this.refreshThreshold = Math.max(0, refreshThreshold);
        return this;
    }

    public GreedyOptions setRefreshAmount(int refreshAmount) {
        this.refreshAmount = Math.max(0, refreshAmount);
        return this;
    }

    public GreedyOptions setWDistance(double wDistance) {
        this.wDistance = Math.max(0, wDistance);
        return this;
    }

    public GreedyOptions setWTurn(double wTurn) {
        this.wTurn = Math.max(0, wTurn);
        return this;
    }

    public GreedyOptions setWVisit(double wVisit) {
        this.wVisit = Math.max(0, wVisit);
        return this;
    }

    public GreedyOptions setWBoundary(double wBoundary) {
        this.wBoundary = Math.max(0, wBoundary);
        return this;
    }

    public GreedyOptions setVisitDecayLambda(double visitDecayLambda) {
        this.visitDecayLambda = Math.max(0, visitDecayLambda);
        return this;
    }

    public GreedyOptions setVisitHistorySize(int visitHistorySize) {
        this.visitHistorySize = Math.max(1, visitHistorySize);
        return this;
    }

    public GreedyOptions setReachRadius(double reachRadius) {
        this.reachRadius = Math.max(0, reachRadius);
        return this;
    }
}
