package io.github.JiangHu.jframe.ai.core.util;

import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.particle.FlameParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 寻路路径可视化器：用<b>粒子效果</b>沿路径节点显示实体的当前寻路路径。
 * <p>
 * 适用于调试与演示场景——让开发者/玩家直观看到 AI 实体"打算走哪条路"。
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #showPathOnce} —— 一次性显示完整路径（粒子约持续 1-2 秒后消失）</li>
 *   <li>{@link #showPath(Entity, int)} —— 持续显示路径 N 秒（定时刷新粒子，保持可见）</li>
 *   <li>{@link #stopShowPath} —— 提前停止持续显示</li>
 * </ul>
 *
 * <h3>依赖</h3>
 * 本类依赖 {@link NavigatorManager}（获取路径与 Plugin）。
 * Plugin 通过 {@link NavigatorManager#getPlugin()} 获取，因此<b>必须在 Plugin 绑定后</b>
 * （{@code AiAPI.bindPlugin}）才能使用持续显示功能。{@link #showPathOnce} 不依赖 Plugin。
 *
 * <h3>线程安全</h3>
 * Nukkit 调度器在主线程执行任务，{@link #activeTasks} 使用 {@link ConcurrentHashMap}
 * 以防跨线程调用 {@link #stopShowPath}。
 */
public class PathVisualizer {

    /** 持续显示时粒子刷新间隔（tick，10 tick = 0.5 秒） */
    private static final int REFRESH_TICKS = 10;
    /** 默认持续显示时长（秒） */
    public static final int DEFAULT_DURATION_SECONDS = 10;

    private final NavigatorManager navigatorManager;
    /** 每个实体的持续显示任务（key = entity id） */
    private final Map<Long, TaskHandler> activeTasks = new ConcurrentHashMap<>();

    /**
     * @param navigatorManager 导航管理器（用于获取路径与 Plugin）
     */
    public PathVisualizer(NavigatorManager navigatorManager) {
        this.navigatorManager = navigatorManager;
    }

    /**
     * 一次性显示实体当前路径的所有节点（火焰粒子）。
     * <p>
     * 粒子约持续 1-2 秒后自然消失。若需持续可见，请用 {@link #showPath(Entity, int)}。
     *
     * @param entity 实体
     * @return 显示的节点数；若实体未导航或无路径返回 0
     */
    public int showPathOnce(Entity entity) {
        if (entity == null) {
            return 0;
        }
        Navigator nav = navigatorManager.getNavigator(entity);
        if (nav == null) {
            return 0;
        }
        Level level = entity.getLevel();
        if (level == null) {
            return 0;
        }
        List<AStarNode> path = nav.getPath();
        if (path.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (AStarNode node : path) {
            // 方块中心、脚部上方 1 格（站立高度）
            Vector3 pos = new Vector3(node.x + 0.5, node.y + 1.0, node.z + 0.5);
            level.addParticle(new FlameParticle(pos));
            count++;
        }
        return count;
    }

    /**
     * 持续显示实体路径（默认 10 秒），定时刷新粒子保持可见。
     * <p>
     * 导航结束后（实体到达终点或被 {@code stop}）自动停止。
     *
     * @param entity 实体
     * @return true 表示成功启动；false 表示实体未导航、无路径或 Plugin 未绑定
     */
    public boolean showPath(Entity entity) {
        return showPath(entity, DEFAULT_DURATION_SECONDS);
    }

    /**
     * 持续显示实体路径指定秒数，定时刷新粒子保持可见。
     *
     * @param entity          实体
     * @param durationSeconds 持续秒数（≤0 时取默认值 10）
     * @return true 表示成功启动；false 表示实体未导航、无路径或 Plugin 未绑定
     */
    public boolean showPath(Entity entity, int durationSeconds) {
        Plugin plugin = navigatorManager.getPlugin();
        if (plugin == null) {
            return false;
        }
        // 先停止该实体已有的显示任务
        stopShowPath(entity);

        int count = showPathOnce(entity);
        if (count == 0) {
            return false;
        }

        int duration = durationSeconds > 0 ? durationSeconds : DEFAULT_DURATION_SECONDS;
        // 定时刷新粒子
        TaskHandler task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
            if (!navigatorManager.isNavigating(entity)) {
                stopShowPath(entity);
                return;
            }
            showPathOnce(entity);
        }, REFRESH_TICKS);
        activeTasks.put(entity.getId(), task);

        // duration 秒后自动停止
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin,
                () -> stopShowPath(entity), duration * 20);
        return true;
    }

    /**
     * 停止显示指定实体的路径（取消定时刷新任务）。
     *
     * @param entity 实体
     */
    public void stopShowPath(Entity entity) {
        if (entity == null) {
            return;
        }
        TaskHandler task = activeTasks.remove(entity.getId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 停止所有正在显示的路径。
     */
    public void stopAll() {
        for (TaskHandler task : activeTasks.values()) {
            task.cancel();
        }
        activeTasks.clear();
    }

    /**
     * 查询某实体是否正在持续显示路径。
     *
     * @param entity 实体
     * @return true 表示有活跃的显示任务
     */
    public boolean isShowing(Entity entity) {
        return entity != null && activeTasks.containsKey(entity.getId());
    }
}
