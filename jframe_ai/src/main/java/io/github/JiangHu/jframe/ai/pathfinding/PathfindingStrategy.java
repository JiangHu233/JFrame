package io.github.JiangHu.jframe.ai.pathfinding;

import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;

/**
 * 寻路策略接口（<b>插槽</b>）：定义统一的寻路算法入口。
 * <p>
 * 本接口是策略模式的抽象，允许不同的寻路算法作为<b>可插拔插件</b>接入框架。
 * 调用方（如 {@link io.github.JiangHu.jframe.ai.navigation.AnytimePathFinder}、
 * {@link io.github.JiangHu.jframe.ai.AiAPI}）依赖本接口而非具体实现，
 * 从而可在运行时切换寻路算法，无需修改调用方代码。
 *
 * <h3>内置策略</h3>
 * <ul>
 *   <li>{@link PathFinder}：<b>A* 全局最优寻路</b>——保证最短路径，单次开销较大，
 *       适合精英怪、Boss 等要求必达的场景</li>
 *   <li>{@link GreedyPathFinder}：<b>贪心局部步进寻路</b>——不保证最优/必达，
 *       单次开销极小（常态仅 8 邻居），适合杂兵群、游荡等低开销场景</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 使用 A* 寻路（精确，保证最优）
 * PathfindingStrategy astar = new PathFinder();
 * PathResult r1 = astar.findPath(level, start, target, new PathfinderOptions());
 *
 * // 使用贪心寻路（快速，开销极低）
 * PathfindingStrategy greedy = new GreedyPathFinder();
 * PathResult r2 = greedy.findPath(level, start, target, new GreedyOptions());
 *
 * // 运行时切换策略：AnytimePathFinder 持有 PathfindingStrategy 引用
 * anytimePathFinder.setStrategy(greedy);  // 切换为贪心追逐
 * }</pre>
 *
 * <h3>实现约定</h3>
 * 实现方应保证：
 * <ul>
 *   <li>{@link #findPath} 永不返回 null，失败时返回 {@link PathResult.Status} 标识的失败结果</li>
 *   <li>{@code config} 类型不匹配时降级为 {@link #getDefaultConfig()} 而非抛异常</li>
 *   <li>{@code level/start/target} 为 null 时返回 {@link PathResult.Status#START_INVALID}</li>
 * </ul>
 *
 * @see PathfindingConfig
 * @see PathResult
 * @see PathFinder
 * @see GreedyPathFinder
 */
public interface PathfindingStrategy {

    /**
     * 策略名称（如 {@code "astar"}、{@code "greedy"}），用于标识、日志与命令行展示。
     *
     * @return 策略名称
     */
    String getName();

    /**
     * 执行寻路：计算从起点到终点的路径。
     * <p>
     * 这是策略接口的核心方法。不同策略返回的 {@link PathResult} 语义可能不同：
     * <ul>
     *   <li>A*：返回 {@link PathResult.Status#SUCCESS}（完整最优路径）或
     *       {@link PathResult.Status#PARTIAL}（边走边搜部分路径）</li>
     *   <li>贪心：返回 {@link PathResult.Status#SUCCESS}（短视路径到达目标）或
     *       {@link PathResult.Status#PARTIAL}（步数上限/目标远离熔断）或
     *       {@link PathResult.Status#CANCELLED}（外部取消）</li>
     * </ul>
     *
     * @param level  所在世界
     * @param start  起点（实体的脚部位置）
     * @param target 终点
     * @param config 寻路配置（具体类型由策略决定，类型不匹配时降级为默认配置）
     * @return 寻路结果（永不为 null）
     */
    PathResult findPath(Level level, Vector3 start, Vector3 target, PathfindingConfig config);

    /**
     * 获取该策略的默认配置实例。
     * <p>
     * 调用方可通过此方法获取适合该策略的默认参数，再按需微调：
     * <pre>{@code
     * PathfinderOptions opts = (PathfinderOptions) astar.getDefaultConfig();
     * opts.setMaxSearchNodes(3000);
     * }</pre>
     *
     * @return 默认配置（新实例）
     */
    PathfindingConfig getDefaultConfig();
}
