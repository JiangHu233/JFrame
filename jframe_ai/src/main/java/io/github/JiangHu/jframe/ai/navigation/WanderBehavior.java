package io.github.JiangHu.jframe.ai.navigation;

import io.github.JiangHu.jframe.core.JFrameLog;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.ai.pathfinding.PathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.PathfinderOptions;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 游荡行为：让实体在以自身为中心的圆形区域内<b>随机巡游</b>。
 * <p>
 * 典型用于被动生物的闲逛、怪物在领地内的巡逻、NPC 的环境氛围表现等。
 * 与 {@link AnytimePathFinder}（追逐特定目标）不同，游荡没有固定终点，
 * 而是周期性地在半径范围内随机选取一个可达点并前往，到达后原地停留片刻再选下一个，循环往复。
 *
 * <h3>行为循环（状态机）</h3>
 * <ol>
 *   <li><b>移动中</b>：实体正沿当前路径行进（{@link NavigatorManager#isNavigating} 为 true），等待走完</li>
 *   <li><b>停留</b>：上一段走完后，原地静止 {@code idleTicks} 个 tick（模拟"东张西望"）</li>
 *   <li><b>选取新点</b>：在半径圆内随机取一个点，寻路并开始下一段移动，回到步骤 1</li>
 * </ol>
 *
 * <h3>随机点选取</h3>
 * 以实体当前位置为圆心，随机角度 θ ∈ [0, 2π)、随机距离 d ∈ [radius×0.3, radius]，
 * 计算目标坐标。距离下限设为 30% 半径，避免选到过近的点导致"原地踏步"。
 * 寻路开启 {@link PathfinderOptions#setPartialOnFailure(boolean)}，即使随机点落在不可达区域，
 * 实体也会朝该方向移动一段（边走边搜），保证游荡的连续性。
 *
 * <h3>线程安全</h3>
 * 任务表使用 {@link ConcurrentHashMap}，{@code wander/stopWander} 可在任意线程调用；
 * 状态推进由 Nukkit 主线程调度执行。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 让村民在 10 格半径内游荡，默认速度与停留时间
 * wanderBehavior.wander(villager, 10);
 *
 * // 自定义：半径 16，速度 0.15，每次停留 100 tick（5 秒）
 * wanderBehavior.wander(villager, 16, 0.15, 100);
 * }</pre>
 *
 * @see PathFinder
 * @see NavigatorManager
 */
public class WanderBehavior {

    /** 默认游荡半径（方块） */
    public static final double DEFAULT_RADIUS = 10;
    /** 默认每次到达后的停留 tick 数（60 tick ≈ 3 秒） */
    public static final int DEFAULT_IDLE_TICKS = 60;
    /** 状态检查调度间隔（tick） */
    private static final int CHECK_INTERVAL = 10;

    private final PathFinder pathFinder;
    private final NavigatorManager navigatorManager;
    /** 实体 id → 游荡任务 */
    private final Map<Long, WanderTask> tasks = new ConcurrentHashMap<>();

    /**
     * @param pathFinder       寻路器
     * @param navigatorManager 导航管理器
     */
    public WanderBehavior(PathFinder pathFinder, NavigatorManager navigatorManager) {
        this.pathFinder = pathFinder;
        this.navigatorManager = navigatorManager;
    }

    /**
     * 以默认参数开始游荡。
     *
     * @param entity 实体
     * @param radius 游荡半径（方块）
     */
    public void wander(Entity entity, double radius) {
        wander(entity, radius, Navigator.DEFAULT_SPEED, DEFAULT_IDLE_TICKS);
    }

    /**
     * 开始游荡（完整参数）。
     *
     * @param entity    实体
     * @param radius    游荡半径（方块）
     * @param speed     行进速度（方块/tick）
     * @param idleTicks 每次到达后的停留 tick 数
     */
    public void wander(Entity entity, double radius, double speed, int idleTicks) {
        if (entity == null) {
            return;
        }
        Plugin plugin = navigatorManager.getPlugin();
        if (plugin == null) {
            JFrameLog.warning("WanderBehavior",
                    "插件尚未绑定，无法启动游荡任务。请先导入 AI 模块。");
            return;
        }
        stopWander(entity); // 同一实体同时只有一个游荡任务
        WanderTask task = new WanderTask(entity, Math.max(1, radius),
                Math.max(0.01, speed), Math.max(0, idleTicks));
        task.taskHandler = Server.getInstance().getScheduler()
                .scheduleRepeatingTask(plugin, task::tick, CHECK_INTERVAL);
        tasks.put(entity.getId(), task);
    }

    /**
     * 查询某实体是否正在游荡。
     *
     * @param entity 实体
     * @return true 表示存在未完成的游荡任务
     */
    public boolean isWandering(Entity entity) {
        return entity != null && tasks.containsKey(entity.getId());
    }

    /**
     * 停止指定实体的游荡（同时停止其导航）。
     *
     * @param entity 实体
     */
    public void stopWander(Entity entity) {
        if (entity == null) {
            return;
        }
        WanderTask removed = tasks.remove(entity.getId());
        if (removed != null) {
            removed.cancel();
            navigatorManager.stop(entity);
        }
    }

    /**
     * 停止所有游荡任务。
     */
    public void stopAll() {
        for (WanderTask task : tasks.values()) {
            task.cancel();
            navigatorManager.stop(task.entity);
        }
        tasks.clear();
    }

    /**
     * 当前活跃游荡任务数。
     *
     * @return 数量
     */
    public int activeCount() {
        return tasks.size();
    }

    /**
     * 单个实体的游荡任务：持有调度句柄与状态机。
     */
    private final class WanderTask {
        final Entity entity;
        final double radius;
        final double speed;
        final int idleTicks;
        /** 当前剩余停留 tick（折算为 CHECK_INTERVAL 的倍数） */
        int idleRemaining;
        TaskHandler taskHandler;

        WanderTask(Entity entity, double radius, double speed, int idleTicks) {
            this.entity = entity;
            this.radius = radius;
            this.speed = speed;
            this.idleTicks = idleTicks;
            this.idleRemaining = 0; // 立即开始第一段移动
        }

        /**
         * 由调度器周期调用：推进游荡状态机。
         */
        void tick() {
            // 终止条件：实体失效
            if (entity == null || entity.closed || !entity.isAlive()) {
                stopWander(entity);
                return;
            }
            // 状态 1：仍在走上一段 → 等待走完
            if (navigatorManager.isNavigating(entity)) {
                return;
            }
            // 状态 2：停留倒计时
            if (idleRemaining > 0) {
                idleRemaining--;
                return;
            }
            // 状态 3：选取新随机点并开始移动
            pickAndNavigate();
        }

        /**
         * 在半径圆内随机选取一个点，寻路并提交导航。
         */
        private void pickAndNavigate() {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double angle = rng.nextDouble(Math.PI * 2);
            // 距离下限 30% 半径，避免选到过近的点
            double dist = rng.nextDouble(radius * 0.3, radius);
            double tx = entity.x + Math.cos(angle) * dist;
            double tz = entity.z + Math.sin(angle) * dist;
            int ty = entity.getFloorY();
            Vector3 target = new Vector3(tx, ty, tz);

            // 游荡用较小的搜索预算，开启部分路径回退保证连续性
            PathfinderOptions opts = new PathfinderOptions()
                    .setMaxSearchNodes(400)
                    .setPartialOnFailure(true);
            PathResult result = pathFinder.findPath(entity.getLevel(), entity, target, opts);
            if (result.hasPath()) {
                navigatorManager.navigateOrPartial(entity, result, speed);
            }
            // 无论是否找到路径，都重置停留计时（找不到就等一会再试下一个点）
            idleRemaining = idleTicks / CHECK_INTERVAL + 1;
        }

        void cancel() {
            if (taskHandler != null) {
                taskHandler.cancel();
                taskHandler = null;
            }
        }
    }
}
