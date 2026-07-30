package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.level.Level;

/**
 * 自定义步进代价函数（<b>外接评分器</b>）。
 * <p>
 * 允许调用方在 A* 寻路时覆盖默认的邻居代价计算，实现诸如：
 * <ul>
 *   <li><b>规避危险方块</b>：岩浆、仙人掌、悬崖边等赋予高代价</li>
 *   <li><b>偏好特定地形</b>：草地、道路赋予低代价（更愿意走）</li>
 *   <li><b>动态权重</b>：基于光照、生物群系、高度、距威胁距离等动态调整</li>
 *   <li><b>禁止通行</b>：返回 {@link Double#POSITIVE_INFINITY} 表示该步不可走</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * A* 计算从 {@code from} 到 {@code to} 的步进代价时，在<b>基础移动代价</b>
 * （直线 1.0 / 对角线 {@code diagonalCost} / 攀爬额外代价）之上，叠加本函数返回的 {@code extraCost}。
 * 最终代价会被下限保护（不低于 0.01），避免负代价破坏 A* 的最优性。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 规避岩浆：经过岩浆旁的方块代价 +10
 * StepCostFunction avoidLava = (level, from, to, diagonal, opts) -> {
 *     if (level.getBlock(to.x, to.y - 1, to.z) instanceof BlockLava) {
 *         return 10.0;  // 大幅增加代价，A* 会尽量绕开
 *     }
 *     return 0.0;
 * };
 *
 * PathfinderOptions options = new PathfinderOptions().setStepCostFunction(avoidLava);
 * PathResult result = pathFinder.findPath(level, start, target, options);
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 实现方应保证本函数无状态或线程安全，因为 {@link PathFinder} 可被多线程并发调用。
 *
 * @see PathfinderOptions#stepCostFunction(StepCostFunction)
 * @see PathFinder
 */
@FunctionalInterface
public interface StepCostFunction {

    /**
     * 计算从 {@code from} 移动到 {@code to} 的<b>额外</b>代价（叠加在基础移动代价之上）。
     *
     * @param level    所在世界
     * @param from     当前节点
     * @param to       邻居节点（已通过可通行性判定）
     * @param diagonal 是否为对角线移动
     * @param options  寻路参数（可读取实体高度等上下文）
     * @return 额外代价：0 表示无影响；正值增加代价（规避）；{@link Double#POSITIVE_INFINITY} 表示禁止该步
     */
    double extraCost(Level level, BlockNode from, BlockNode to, boolean diagonal, PathfinderOptions options);
}
