package io.github.JiangHu.jframe.ai.core.navigation;

import io.github.JiangHu.jframe.core.JFrameLog;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.core.module.PluginAware;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导航器管理器：集中调度所有活跃的 {@link Navigator}。
 * <p>
 * 本类是 AI 模块的<b>调度中枢</b>，负责：
 * <ul>
 *   <li>维护"实体 → 导航器"的注册表（同一实体同时只保留一个导航器，新导航会替换旧的）</li>
 *   <li>通过 Nukkit {@link cn.nukkit.scheduler.ServerScheduler} 注册每 tick（50ms）的重复任务，
 *       统一驱动所有导航器前进</li>
 *   <li>在导航完成或实体失效时自动清理</li>
 * </ul>
 *
 * <h3>插件绑定（PluginAware）</h3>
 * 调度任务需要 {@link Plugin} 实例。本类实现 {@link PluginAware}，
 * 由 {@code JFrameMain} 在模块导入/插件启用时自动注入 plugin 并启动调度。
 * 在 plugin 绑定前提交的导航请求会被正常注册，待绑定后随首个 tick 一起推进。
 *
 * <h3>线程安全</h3>
 * 使用 {@link ConcurrentHashMap} 持有导航器，{@code navigate/stop} 可在任意线程调用；
 * {@link #tickAll()} 由 Nukkit 主线程调度执行。
 *
 * @see Navigator
 * @see PluginAware
 */
public class NavigatorManager implements PluginAware {

    /** 实体 id → 导航器 */
    private final Map<Long, Navigator> navigators = new ConcurrentHashMap<>();

    /** 关联插件（用于注册调度任务） */
    private Plugin plugin;
    /** 调度任务句柄，用于去重注册 */
    private TaskHandler taskHandler;
    /** 是否已启动调度，防止重复注册 */
    private volatile boolean started;

    /**
     * 获取关联的插件实例。
     *
     * @return 插件实例，或 null（尚未绑定）
     */
    public Plugin getPlugin() {
        return plugin;
    }

    /**
     * 提交一条导航任务。
     * <p>
     * 若该实体已有进行中的导航，会先停止旧的（同一实体同时只有一个导航器）。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果（失败则不创建导航器，返回 null）
     * @param speed  行进速度（方块/tick）
     * @return 导航器，或 null（路径不可用）
     */
    public Navigator navigate(Entity entity, PathResult result, double speed) {
        if (entity == null || result == null || !result.isSuccess()) {
            return null;
        }
        stop(entity);
        Navigator navigator = new Navigator(entity, result, speed);
        navigators.put(entity.getId(), navigator);
        ensureStarted();
        return navigator;
    }

    /**
     * 以默认速度提交导航任务。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果
     * @return 导航器，或 null
     */
    public Navigator navigate(Entity entity, PathResult result) {
        return navigate(entity, result, Navigator.DEFAULT_SPEED);
    }

    /**
     * 提交导航任务（边走边搜版）：接受完整路径或部分路径，支持<b>无缝续段</b>。
     * <p>
     * 与 {@link #navigate} 的区别：本方法只要 {@link PathResult#hasPath()} 为真即接受结果，
     * 因此即使寻路仅得到 {@link PathResult.Status#PARTIAL}（未到达目标的部分路径），
     * 实体也会沿该路径开始移动——这正是"边走边搜"所需的语义。
     * <p>
     * <b>无缝续段</b>：若该实体存在一个<b>已走完</b>（{@link Navigator#isFinished()}）的旧导航器，
     * 不再"停止旧导航器 + 从路径头重建"，而是复用同一实例调用
     * {@link Navigator#retarget(PathResult)}——保留速度向量与视角修正器状态
     * （含 {@code SmoothGaze} 的平滑进度），段切换瞬间无停顿、无回头路。
     * 旧导航器<b>进行中</b>时仍为取代语义（先停旧的再建新的）。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果（成功或部分路径）
     * @param speed  行进速度（方块/tick，仅新建导航器时生效；续段沿用旧速度）
     * @return 导航器，或 null（路径完全不可用）
     * @see io.github.JiangHu.jframe.ai.core.behavior.LoopBehavior
     */
    public Navigator navigateOrPartial(Entity entity, PathResult result, double speed) {
        if (entity == null || result == null || !result.hasPath()) {
            return null;
        }
        Navigator existing = navigators.get(entity.getId());
        if (existing != null && existing.isFinished()) {
            // 无缝续段：复用已结束的导航器，保留速度与视角状态
            existing.retarget(result);
            ensureStarted();
            return existing;
        }
        if (existing != null) {
            // 取代语义：进行中的导航被新导航取代
            existing.stop();
        }
        Navigator navigator = new Navigator(entity, result, speed);
        navigators.put(entity.getId(), navigator);
        ensureStarted();
        return navigator;
    }

    /**
     * 停止并移除指定实体的导航。
     *
     * @param entity 实体
     */
    public void stop(Entity entity) {
        if (entity == null) {
            return;
        }
        Navigator removed = navigators.remove(entity.getId());
        if (removed != null) {
            removed.stop();
        }
    }

    /**
     * 停止所有导航。
     */
    public void stopAll() {
        for (Navigator navigator : navigators.values()) {
            navigator.stop();
        }
        navigators.clear();
    }

    /**
     * 查询某实体当前是否正在导航。
     *
     * @param entity 实体
     * @return true 表示存在未完成的导航器
     */
    public boolean isNavigating(Entity entity) {
        return entity != null && navigators.containsKey(entity.getId());
    }

    /**
     * 获取某实体的导航器（可能为 null）。
     *
     * @param entity 实体
     * @return 导航器，或 null
     */
    public Navigator getNavigator(Entity entity) {
        return entity == null ? null : navigators.get(entity.getId());
    }

    /**
     * 当前活跃导航器数量。
     *
     * @return 数量
     */
    public int activeCount() {
        return navigators.size();
    }

    /**
     * 单次 tick：推进所有导航器，移除已完成的。
     * <p>
     * 由调度器每 tick 调用。单个导航器的异常被捕获并记录，不影响其他导航器。
     * <p>
     * <b>移除语义（修复竞态）</b>：旧版 {@code entrySet().removeIf} 按 key 无条件删除，
     * 会误删两类"不应删"的导航器：
     * <ul>
     *   <li>{@code onComplete} 回调在本 tick 调用栈内注册的<b>新导航器</b>（同 key 不同实例）</li>
     *   <li>回调中经 {@link #navigateOrPartial} 对刚走完的导航器
     *       {@link Navigator#retarget(PathResult) 复活续段}的同一实例（finished 已重置）</li>
     * </ul>
     * 现改为：快照迭代 + tick 返回 false 后<b>双重校验</b>——仅当该实例
     * {@link Navigator#isFinished()} 仍为 true 且 map 中仍映射到同一实例时才移除
     * （{@link ConcurrentHashMap#remove(Object, Object) 条件删除}）。
     */
    public void tickAll() {
        if (navigators.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Navigator> entry : new ArrayList<>(navigators.entrySet())) {
            Navigator navigator = entry.getValue();
            boolean alive;
            try {
                alive = navigator.tick();
            } catch (Exception e) {
                JFrameLog.error("NavigatorManager",
                        "AI 导航器 tick 异常: " + navigator.getEntity(), e);
                navigators.remove(entry.getKey(), navigator);
                continue;
            }
            if (!alive && navigator.isFinished()) {
                // 条件删除：仅当仍映射到同一实例时移除，
                // 不触碰回调中注册的新导航器或已复活的实例
                navigators.remove(entry.getKey(), navigator);
            }
        }
    }

    /**
     * 确保调度任务已启动（幂等）。
     */
    private void ensureStarted() {
        if (started || plugin == null) {
            return;
        }
        synchronized (this) {
            if (started || plugin == null) {
                return;
            }
            // 每 1 tick（约 50ms）推进一次，同步执行（主线程）
            taskHandler = Server.getInstance().getScheduler()
                    .scheduleRepeatingTask(plugin, this::tickAll, 1);
            started = true;
        }
    }

    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return;
        }
        this.plugin = plugin;
        ensureStarted();
    }

    /**
     * 关闭管理器：停止所有导航并取消调度任务。
     * <p>
     * 供插件禁用时调用。
     */
    public void shutdown() {
        stopAll();
        if (taskHandler != null) {
            taskHandler.cancel();
            taskHandler = null;
        }
        started = false;
    }
}
