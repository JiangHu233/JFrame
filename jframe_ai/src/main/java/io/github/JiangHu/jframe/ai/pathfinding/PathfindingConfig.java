package io.github.JiangHu.jframe.ai.pathfinding;

import lombok.Getter;

/**
 * 寻路配置基类：所有寻路策略共享的通用配置参数。
 * <p>
 * 本类是策略模式中配置体系的根节点。具体的寻路策略有自己的配置子类：
 * <ul>
 *   <li>{@link PathfinderOptions}：A* 寻路配置（继承本类，追加 A* 专属参数）</li>
 *   <li>{@link GreedyOptions}：贪心步进寻路配置（继承本类，追加贪心专属参数）</li>
 * </ul>
 *
 * <h3>通用参数</h3>
 * <ul>
 *   <li>{@link #entityHeight}：实体高度（占用方块层数），用于判断头部空间是否充足</li>
 *   <li>{@link #entityWidth}：实体宽度（占用方块列数），用于判断水平空间是否充足</li>
 * </ul>
 *
 * <h3>链式 setter</h3>
 * 所有 setter 返回 {@code this}，支持链式调用：
 * <pre>{@code
 * PathfinderOptions opts = new PathfinderOptions()
 *         .setEntityHeight(3)
 *         .setMaxSearchNodes(2000);
 * }</pre>
 *
 * @see PathfindingStrategy
 * @see PathfinderOptions
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
}
