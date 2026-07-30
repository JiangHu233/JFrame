package io.github.JiangHu.jframe.ai.navigation;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.pathfinding.GreedyOptions;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连续导航器：走完一段路径后自动从新位置续算下一段，直到到达目标。
 * <p>
 * 与 {@link AnytimePathFinder}（定时重搜追逐<b>移动</b>目标）不同，本类面向<b>静态目标</b>，
 * 通过 {@link Navigator#onComplete} 回调驱动续算——实体走完当前段后，
 * 立即从最新位置重新调用 {@link PathfindingStrategy#findPath} 计算下一段，
 * 无需等待定时器，段间衔接无缝。
 *
 * <h3>适用场景</h3>
 * 贪心寻路每次只走有限步数（返回 PARTIAL），本类通过走完续算将这些短段
 * 串联成长距离导航，使实体能到达任意远的目标。
 *
 * <h3>工作流程</h3>
 * <pre>{@code
 * findPath → 段A（12步） → Navigator走A → 走完 → onComplete回调
 *   └→ 距离检查：未到达 → findPath → 段B → Navigator走B → 走完 → onComplete回调
 *       └→ 距离检查：已到达 → 停止
 * }</pre>
 *
 * <h3>终止条件</h3>
 * <ul>
 *   <li>实体走完一段后，与目标的水平距离 ≤ {@link GreedyOptions#getReachRadius()}（已到达）</li>
 *   <li>寻路返回 {@link PathResult.Status#NO_PATH} / {@link PathResult.Status#START_INVALID}（无法到达）</li>
 *   <li>实体死亡、关闭或切换世界</li>
 *   <li>续算段数超过 {@link #DEFAULT_MAX_SEGMENTS}（防止无限循环）</li>
 *   <li>调用 {@link #stop(Entity)} 主动停止</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 任务表使用 {@link ConcurrentHashMap}，{@code navigateTo/stop} 可在任意线程调用；
 * 续算回调由 {@link NavigatorManager} 主线程调度执行（在 {@link Navigator#tick} 返回 false 时触发）。
 *
 * @see Navigator#onComplete
 * @see AnytimePathFinder
 * @see GreedyOptions
 */
public class ContinuousNavigator {

    /** 默认最大续算段数（防止无限循环）。每段约 12 步，100 段 ≈ 1200 步 */
    public static final int DEFAULT_MAX_SEGMENTS = 100;

    private final PathfindingStrategy strategy;
    private final NavigatorManager navigatorManager;
    /** 实体 id → 连续导航任务 */
    private final Map<Long, ContinuousTask> tasks = new ConcurrentHashMap<>();

    /**
     * @param strategy         寻路策略（通常为 GreedyPathFinder）
     * @param navigatorManager 导航管理器（提供导航提交入口）
     */
    public ContinuousNavigator(PathfindingStrategy strategy, NavigatorManager navigatorManager) {
        this.strategy = strategy;
        this.navigatorManager = navigatorManager;
    }

    /**
     * 开始连续导航到静态目标（默认参数）。
     *
     * @param entity 被导航的实体
     * @param target 目标点
     */
    public void navigateTo(Entity entity, Vector3 target) {
        navigateTo(entity, target, new GreedyOptions(), Navigator.DEFAULT_SPEED, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * 开始连续导航到静态目标（完整参数）。
     *
     * @param entity      被导航的实体
     * @param target      目标点
     * @param options     贪心寻路参数
     * @param speed       行进速度（方块/tick）
     * @param maxSegments 最大续算段数（防止无限循环）
     */
    public void navigateTo(Entity entity, Vector3 target, GreedyOptions options,
                           double speed, int maxSegments) {
        if (entity == null || target == null) {
            return;
        }
        // 停止该实体已有的连续导航（同一实体同时只有一个连续导航任务）
        stop(entity);
        if (options == null) {
            options = new GreedyOptions();
        }
        ContinuousTask task = new ContinuousTask(entity, target, options, speed, maxSegments);
        tasks.put(entity.getId(), task);
        task.navigateNextSegment();
    }

    /**
     * 停止指定实体的连续导航。
     *
     * @param entity 实体
     */
    public void stop(Entity entity) {
        if (entity == null) {
            return;
        }
        ContinuousTask removed = tasks.remove(entity.getId());
        if (removed != null) {
            removed.cancel();
            navigatorManager.stop(entity);
        }
    }

    /**
     * 停止所有连续导航。
     */
    public void stopAll() {
        for (ContinuousTask task : tasks.values()) {
            task.cancel();
            navigatorManager.stop(task.entity);
        }
        tasks.clear();
    }

    /**
     * 查询某实体是否正在连续导航。
     *
     * @param entity 实体
     * @return true 表示存在未完成的连续导航任务
     */
    public boolean isNavigating(Entity entity) {
        return entity != null && tasks.containsKey(entity.getId());
    }

    /**
     * 当前活跃连续导航任务数。
     *
     * @return 数量
     */
    public int activeCount() {
        return tasks.size();
    }

    // =========================================================================
    // 单个实体的连续导航任务
    // =========================================================================

    /**
     * 单个实体的连续导航任务：持有目标、参数与续算逻辑。
     */
    private final class ContinuousTask {
        final Entity entity;
        final Vector3 target;
        final GreedyOptions options;
        final double speed;
        final int maxSegments;
        /** 取消标志（volatile 支持跨线程取消） */
        volatile boolean cancelled;
        /** 已完成的续算段数 */
        int segmentCount;

        ContinuousTask(Entity entity, Vector3 target, GreedyOptions options,
                       double speed, int maxSegments) {
            this.entity = entity;
            this.target = target;
            this.options = options;
            this.speed = speed;
            this.maxSegments = Math.max(1, maxSegments);
        }

        /**
         * 计算并提交下一段导航。
         * <p>
         * 首次调用由 {@link #navigateTo} 触发，后续由 {@link Navigator#onComplete} 回调触发。
         */
        void navigateNextSegment() {
            if (cancelled) {
                return;
            }
            // 实体失效
            if (entity == null || entity.closed || !entity.isAlive()) {
                cleanup();
                return;
            }
            // 段数上限
            if (segmentCount >= maxSegments) {
                cleanup();
                return;
            }
            segmentCount++;

            // 从实体当前位置计算下一段路径
            PathResult result = strategy.findPath(entity.getLevel(), entity, target, options);

            // 无路径：停止
            if (result == null || !result.hasPath()) {
                cleanup();
                return;
            }

            // 提交导航（navigateOrPartial 接受 SUCCESS 与 PARTIAL）
            Navigator nav = navigatorManager.navigateOrPartial(entity, result, speed);
            if (nav == null) {
                cleanup();
                return;
            }

            // 设置走完回调：检查是否到达目标，未到达则续算下一段
            nav.onComplete(n -> {
                if (cancelled) {
                    return;
                }
                // 实体失效检查
                if (entity == null || entity.closed || !entity.isAlive()) {
                    cleanup();
                    return;
                }
                // 距离检查：是否已到达目标（水平距离）
                double dx = target.x - entity.x;
                double dz = target.z - entity.z;
                double reachSq = options.getReachRadius() * options.getReachRadius();
                if (dx * dx + dz * dz <= reachSq) {
                    // 已到达，停止续算
                    cleanup();
                    return;
                }
                // 未到达，续算下一段
                navigateNextSegment();
            });
        }

        /** 从任务表中移除自身 */
        void cleanup() {
            tasks.remove(entity.getId());
        }

        void cancel() {
            cancelled = true;
        }
    }
}
