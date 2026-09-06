package io.github.JiangHu.jframe.ai.pathfinding;

import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarOptions;
import io.github.JiangHu.jframe.ai.pathfinding.greedy.GreedyOptions;
import lombok.Getter;

/**
 * 寻路配置基类：所有寻路策略共享的通用配置参数。
 * <p>
 * 本类是策略模式中配置体系的根节点。具体的寻路策略有自己的配置子类：
 * <ul>
 *   <li>{@link AStarOptions}：A* 寻路配置（继承本类，追加 A* 专属参数）</li>
 *   <li>{@link GreedyOptions}：贪心步进寻路配置（继承本类，追加贪心专属参数）</li>
 * </ul>
 *
 * <h3>通用参数</h3>
 * <ul>
 *   <li>{@link #entityHeight}：实体高度（占用方块层数），用于判断头部空间是否充足</li>
 *   <li>{@link #entityWidth}：实体宽度（占用方块列数），用于判断水平空间是否充足</li>
 *   <li>{@link #partialOnFailure}：寻路失败时是否回退到"部分路径"（边走边搜），默认 false</li>
 * </ul>
 *
 * <h3>链式 setter</h3>
 * 所有 setter 返回 {@code this}，支持链式调用：
 * <pre>{@code
 * AStarOptions opts = new AStarOptions()
 *         .setEntityHeight(3)
 *         .setMaxSearchNodes(2000);
 * }</pre>
 *
 * @see PathfindingStrategy
 * @see AStarOptions
 * @see GreedyOptions
 */
@Getter
public abstract class PathfindingConfig {

    /**
     * 实体高度（占用的方块层数，含脚部格）。
     * <p>
     * 用于判断头部上方是否有足够空间。普通生物为 2（脚 + 头），
     * 高大生物（如末影人）可能为 3。
     * 默认 2。
     */
    private int entityHeight = 2;

    /**
     * 实体宽度（占用的方块列数）。
     * <p>
     * 用于判断水平方向是否有足够空间。普通生物为 1（占 1 列），
     * 大型生物（如铁傀儡）可能为 2。
     * <p>
     * A* 寻路当前仅检查单列通行性（忽略此参数），贪心寻路会严格检查多列空间。
     * 默认 1。
     */
    private int entityWidth = 1;

    /**
     * 寻路失败时是否回退到"部分路径"（边走边搜模式）。
     * <p>
     * 开启后，当搜索因预算熔断或 open 表耗尽（NO_PATH）而未能到达终点时，
     * 不会直接返回失败，而是返回到"离目标启发距离最近的已探索节点"的部分路径
     * （状态为 {@link PathResult.Status#PARTIAL}）。
     * <p>
     * 这样即使目标不可达或预算不足，实体也能朝目标方向移动一段距离，
     * 配合周期性重搜即可实现"追逐移动目标"的边走边搜效果。
     * <p>
     * A* 策略据此在熔断/无解时返回 PARTIAL；贪心策略天然返回 PARTIAL（不受此开关影响）。
     * 默认 false。
     *
     * @see PathResult#isPartial()
     * @see PathResult#hasPath()
     */
    private boolean partialOnFailure = false;

    /**
     * 设置实体高度（链式）。
     *
     * @param entityHeight 实体高度（≥1）
     * @return this
     */
    public PathfindingConfig setEntityHeight(int entityHeight) {
        this.entityHeight = Math.max(1, entityHeight);
        return this;
    }

    /**
     * 设置实体宽度（链式）。
     *
     * @param entityWidth 实体宽度（≥1）
     * @return this
     */
    public PathfindingConfig setEntityWidth(int entityWidth) {
        this.entityWidth = Math.max(1, entityWidth);
        return this;
    }

    /**
     * 设置寻路失败时是否回退到部分路径（链式）。
     *
     * @param partialOnFailure true 表示启用边走边搜回退
     * @return this
     * @see #partialOnFailure
     */
    public PathfindingConfig setPartialOnFailure(boolean partialOnFailure) {
        this.partialOnFailure = partialOnFailure;
        return this;
    }
}
