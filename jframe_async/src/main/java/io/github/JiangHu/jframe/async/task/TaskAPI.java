package io.github.JiangHu.jframe.async.task;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 可挂起任务管理器。
 * <p>
 * 提供基于 {@link SuspendableTask} 的集中管理能力，包括：
 * <ul>
 *   <li><b>插件绑定</b>：实现 {@link PluginAware}，由 {@code JFrameMain} 自动注入 {@link Plugin} 实例</li>
 *   <li><b>任务注册表</b>：维护「名称 → 任务」映射（{@link ConcurrentHashMap}），按名管理</li>
 *   <li><b>工厂方法</b>：{@link #createTask(String, int, Runnable, BooleanSupplier)} 通过 Lambda 快速创建</li>
 *   <li><b>批量管理</b>：{@link #cancelAll()} 在插件卸载时一键关闭全部任务</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * TaskAPI taskAPI = jframeMain.getTaskAPI();
 *
 * // 方式一：函数式创建（自动注册）
 * SuspendableTask search = taskAPI.createTask("pathfinding", 1,
 *     () -> expandNode(), () -> !found());
 * search.execute();
 *
 * // 方式二：按名触发（幂等）
 * taskAPI.execute("pathfinding");  // 已在跑则保持，未跑则启动
 *
 * // 方式三：注册自定义子类
 * BuildTask build = new BuildTask(taskAPI.getPlugin(), blocks);
 * taskAPI.register("castle", build);
 * build.execute();
 *
 * // 关闭
 * taskAPI.cancel("pathfinding");
 * taskAPI.cancelAll();   // 插件卸载时
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 注册表使用 {@link ConcurrentHashMap}，{@code createTask / register / execute / cancel}
 * 均可在任意线程调用。任务内部的调度由 Nukkit 主线程驱动。
 *
 * @see SuspendableTask
 * @see PluginAware
 */
public class TaskAPI implements PluginAware {

    /** 关联插件，由 {@link PluginAware} 机制自动注入 */
    @Getter
    private Plugin plugin;

    /** 名称 → 可挂起任务 的映射表 */
    private final Map<String, SuspendableTask> tasks = new ConcurrentHashMap<>();

    // ==================== 任务创建 / 注册 ====================

    /**
     * 创建函数式可挂起任务并注册到管理器。
     * <p>
     * 若该名称已存在任务，则返回已有实例（幂等，不会覆盖）。
     * 创建后任务处于 Idle 态，需调用 {@link SuspendableTask#execute()} 或
     * {@link #execute(String)} 启动。
     *
     * @param name     任务名称（唯一标识）
     * @param interval 调度间隔（tick），必须 > 0
     * @param onTick   每个 tick 执行的工作
     * @param isValid  是否还有有效工作（返回 false 时任务自动挂起）
     * @return 创建的（或已存在的）任务实例
     * @throws IllegalStateException 插件尚未绑定时抛出
     */
    public SuspendableTask createTask(String name, int interval, Runnable onTick, BooleanSupplier isValid) {
        return tasks.computeIfAbsent(name, n ->
                new SuspendableTask.Functional(requirePlugin(), interval, onTick, isValid));
    }

    /**
     * 注册自定义子类任务实例。
     * <p>
     * 用于将继承 {@link SuspendableTask} 的子类实例纳入管理器的按名管理。
     * 若该名称已存在任务，则返回已有实例，不会覆盖。
     *
     * @param name 任务名称
     * @param task 任务实例
     * @param <T>  任务子类型
     * @return 注册的任务实例（若名称已存在则返回先前的实例）
     */
    public <T extends SuspendableTask> T register(String name, T task) {
        SuspendableTask existing = tasks.putIfAbsent(name, task);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            T result = (T) existing;
            return result;
        }
        return task;
    }

    // ==================== 任务控制 ====================

    /**
     * 按名触发任务执行（幂等）。
     * <p>
     * 等价于 {@code getTask(name).execute()}。若任务不存在则静默忽略。
     *
     * @param name 任务名称
     */
    public void execute(String name) {
        SuspendableTask task = tasks.get(name);
        if (task != null) {
            task.execute();
        }
    }

    /**
     * 按名取消并移除任务。
     *
     * @param name 任务名称
     * @return 若任务存在并已移除返回 {@code true}；不存在返回 {@code false}
     */
    public boolean cancel(String name) {
        SuspendableTask task = tasks.remove(name);
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }

    /**
     * 取消所有任务并清空注册表。
     * <p>
     * 供插件卸载（{@code onDisable}）时调用，确保不遗留调度任务。
     */
    public void cancelAll() {
        for (SuspendableTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    // ==================== 查询 ====================

    /**
     * 获取指定名称的任务实例。
     *
     * @param name 任务名称
     * @return 任务实例，或 {@code null}（不存在）
     */
    public SuspendableTask getTask(String name) {
        return tasks.get(name);
    }

    /**
     * 查询指定任务是否正在执行。
     *
     * @param name 任务名称
     * @return {@code true} 表示任务存在且正在执行
     */
    public boolean isRunning(String name) {
        SuspendableTask task = tasks.get(name);
        return task != null && task.isRunning();
    }

    /**
     * 当前注册的任务总数。
     *
     * @return 任务数量
     */
    public int size() {
        return tasks.size();
    }

    // ==================== PluginAware ====================

    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 内部 ====================

    /**
     * 获取已绑定的插件，未绑定时抛出异常。
     *
     * @return 插件实例
     * @throws IllegalStateException 插件尚未绑定
     */
    private Plugin requirePlugin() {
        if (plugin == null) {
            throw new IllegalStateException(
                    "TaskAPI 插件尚未绑定，请先导入 async 模块（JFrameMain 自动绑定）");
        }
        return plugin;
    }
}
