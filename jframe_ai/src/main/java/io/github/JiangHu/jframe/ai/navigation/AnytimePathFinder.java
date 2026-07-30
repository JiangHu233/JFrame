package io.github.JiangHu.jframe.ai.navigation;

import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.ai.pathfinding.GreedyOptions;
import io.github.JiangHu.jframe.ai.pathfinding.PathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingConfig;
import io.github.JiangHu.jframe.ai.pathfinding.PathfindingStrategy;
import io.github.JiangHu.jframe.ai.pathfinding.PathfinderOptions;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 边走边搜寻路器（Anytime A*）：支持追逐<b>移动目标</b>的实时寻路。
 * <p>
 * 传统 {@link PathFinder#findPath} 是一次性计算：目标一旦移动，旧路径立即失效。
 * 本类通过<b>周期性重搜</b>解决该问题——每隔若干 tick 重新以实体当前位置为起点、目标当前位置为终点
 * 进行一次 A* 搜索，并用新路径替换实体正在跟随的旧路径，从而实现"边走边搜"。
 *
 * <h3>核心特性：即使无解也能移动</h3>
 * 每次重搜都会强制开启 {@link PathfinderOptions#setPartialOnFailure(boolean)}：
 * <ul>
 *   <li>若目标可达 → 返回完整路径（{@link PathResult.Status#SUCCESS}）</li>
 *   <li>若因预算熔断或地形阻隔无法到达 → 返回到"离目标最近的已探索节点"的
 *       <b>部分路径</b>（{@link PathResult.Status#PARTIAL}），实体仍朝目标方向移动一段距离</li>
 *   <li>配合下一轮重搜，实体可持续逼近目标，表现自然</li>
 * </ul>
 * 这正是"即使没有到达目标地点的解也能表现出移动过程，同时兼顾模糊解"的实现。
 *
 * <h3>终止条件</h3>
 * 以下任一情况发生时自动停止追逐：
 * <ul>
 *   <li>实体死亡、关闭或切换世界</li>
 *   <li>目标供应器返回 null（目标消失）</li>
 *   <li>实体与目标的水平距离小于 {@link #GIVE_UP_RADIUS}（已追上）</li>
 *   <li>调用 {@link #stopChase(Entity)} 主动停止</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 任务表使用 {@link ConcurrentHashMap}，{@code chase/stopChase} 可在任意线程调用；
 * 重搜逻辑由 Nukkit 主线程调度执行。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 让僵尸持续追逐某玩家（玩家移动时自动重新规划）
 * anytimePathFinder.chase(zombie, () -> player);
 *
 * // 自定义参数：每 10 tick 重搜一次，速度 0.3
 * anytimePathFinder.chase(zombie, () -> player,
 *         new PathfinderOptions().setMaxSearchNodes(500),
 *         0.3, 10);
 * }</pre>
 *
 * @see PathFinder
 * @see NavigatorManager#navigateOrPartial
 * @see PathResult#isPartial()
 */
public class AnytimePathFinder {

    /** 默认重搜间隔（tick），10 tick ≈ 0.5 秒。较短间隔让追逐移动目标时路径更新更及时 */
    public static final int DEFAULT_REPLAN_INTERVAL = 10;
    /** 视为"已追上"的水平距离（方块），小于此值则停止追逐 */
    public static final double GIVE_UP_RADIUS = 1.5;

    private final PathfindingStrategy strategy;
    private final NavigatorManager navigatorManager;
    /** 实体 id → 追逐任务 */
    private final Map<Long, ChaseTask> tasks = new ConcurrentHashMap<>();

    /**
     * @param strategy         寻路策略（A* 或 Greedy 等，通常为 Spring 单例）
     * @param navigatorManager 导航管理器（提供调度插件与导航提交入口）
     */
    public AnytimePathFinder(PathfindingStrategy strategy, NavigatorManager navigatorManager) {
        this.strategy = strategy;
        this.navigatorManager = navigatorManager;
    }

    /**
     * 以默认参数开始追逐一个（可能移动的）目标。
     *
     * @param entity          追逐者
     * @param targetSupplier  目标位置供应器（每次重搜时调用，返回 null 则停止追逐）
     */
    public void chase(Entity entity, Supplier<Vector3> targetSupplier) {
        chase(entity, targetSupplier, new PathfinderOptions().setMaxSearchNodes(4000),
                Navigator.DEFAULT_SPEED, DEFAULT_REPLAN_INTERVAL);
    }

    /**
     * 开始追逐目标（完整参数）。
     * <p>
     * 注意：传入的 {@code options} 会被强制 {@link PathfinderOptions#setPartialOnFailure(boolean) 开启}，
     * 以保证边走边搜语义。
     *
     * @param entity              追逐者
     * @param targetSupplier      目标位置供应器
     * @param options             寻路参数（将强制开启部分路径回退）
     * @param speed               行进速度（方块/tick）
     * @param replanIntervalTicks 重搜间隔（tick）
     */
    public void chase(Entity entity, Supplier<Vector3> targetSupplier,
                      PathfindingConfig config, double speed, int replanIntervalTicks) {
        if (entity == null || targetSupplier == null) {
            return;
        }
        Plugin plugin = navigatorManager.getPlugin();
        if (plugin == null) {
            Server.getInstance().getLogger().warning(
                    "AnytimePathFinder: 插件尚未绑定，无法启动追逐任务。请先导入 AI 模块。");
            return;
        }
        // 停止该实体已有的追逐任务（同一实体同时只有一个追逐任务）
        stopChase(entity);
        if (config == null) {
            config = strategy.getDefaultConfig();
        }
        // A* 策略需要强制开启边走边搜；贪心策略天然返回 PARTIAL，无需设置
        if (config instanceof PathfinderOptions opts) {
            opts.setPartialOnFailure(true);
        }
        int interval = Math.max(1, replanIntervalTicks);

        ChaseTask task = new ChaseTask(entity, targetSupplier, config, speed);
        task.taskHandler = Server.getInstance().getScheduler()
                .scheduleRepeatingTask(plugin, task::tick, interval);
        tasks.put(entity.getId(), task);
    }

    /**
     * 以<b>贪心策略</b>开始追逐目标。
     * <p>
     * 与 {@link #chase} 不同，贪心追逐使用 {@link io.github.JiangHu.jframe.ai.pathfinding.GreedyPathFinder}
     * 进行局部步进寻路，每次重搜只走有限步数（返回 PARTIAL），由周期性重搜组合完成长距离追逐。
     * 适合大量实体的低开销追逐场景。
     *
     * @param entity         追逐者
     * @param targetSupplier 目标位置供应器
     * @param speed          行进速度
     * @param replanInterval 重搜间隔（tick）
     */
    public void chaseGreedy(Entity entity, Supplier<Vector3> targetSupplier,
                            double speed, int replanInterval) {
        chase(entity, targetSupplier, new GreedyOptions(), speed, replanInterval);
    }

    /**
     * 查询某实体是否正在追逐。
     *
     * @param entity 实体
     * @return true 表示存在未完成的追逐任务
     */
    public boolean isChasing(Entity entity) {
        return entity != null && tasks.containsKey(entity.getId());
    }

    /**
     * 停止指定实体的追逐（同时停止其导航）。
     *
     * @param entity 实体
     */
    public void stopChase(Entity entity) {
        if (entity == null) {
            return;
        }
        ChaseTask removed = tasks.remove(entity.getId());
        if (removed != null) {
            removed.cancel();
            navigatorManager.stop(entity);
        }
    }

    /**
     * 停止所有追逐任务。
     */
    public void stopAll() {
        for (ChaseTask task : tasks.values()) {
            task.cancel();
            navigatorManager.stop(task.entity);
        }
        tasks.clear();
    }

    /**
     * 当前活跃追逐任务数。
     *
     * @return 数量
     */
    public int activeCount() {
        return tasks.size();
    }

    /**
     * 单个实体的追逐任务：持有调度句柄与重搜逻辑。
     */
    private final class ChaseTask {
        final Entity entity;
        final Supplier<Vector3> targetSupplier;
        final PathfindingConfig config;
        final double speed;
        TaskHandler taskHandler;

        ChaseTask(Entity entity, Supplier<Vector3> targetSupplier,
                  PathfindingConfig config, double speed) {
            this.entity = entity;
            this.targetSupplier = targetSupplier;
            this.config = config;
            this.speed = speed;
        }

        /**
         * 由调度器周期调用：执行一次重搜并更新导航。
         */
        void tick() {
            // 终止条件：实体失效
            if (entity == null || entity.closed || !entity.isAlive()) {
                stopChase(entity);
                return;
            }
            Vector3 target = targetSupplier.get();
            // 终止条件：目标消失
            if (target == null) {
                stopChase(entity);
                return;
            }
            // 终止条件：已追上（水平距离）
            double dx = target.x - entity.x;
            double dz = target.z - entity.z;
            if (dx * dx + dz * dz <= GIVE_UP_RADIUS * GIVE_UP_RADIUS) {
                stopChase(entity);
                return;
            }
            // 边走边搜：以实体当前位置为起点重新规划
            PathResult result = strategy.findPath(entity.getLevel(), entity, target, config);
            if (result.hasPath()) {
                // 用新路径替换旧导航（navigateOrPartial 接受 SUCCESS 与 PARTIAL）
                navigatorManager.navigateOrPartial(entity, result, speed);
            }
            // 若完全无路径（NO_PATH 且无 partial），本次跳过，等待下一轮重搜
        }

        void cancel() {
            if (taskHandler != null) {
                taskHandler.cancel();
                taskHandler = null;
            }
        }
    }
}
