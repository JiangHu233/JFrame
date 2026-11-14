package io.github.JiangHu.jframe.engine.ecs.engine;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.engine.ecs.world.NukkitWorldScanner;
import io.github.JiangHu.jframe.engine.ecs.world.NukkitWorldTracker;

/**
 * 调度器 — 单个 Nukkit 主线程重复任务，驱动系统 tick 与低频世界对账。
 *
 * <p>世界对象的即时跟踪由 {@link NukkitWorldTracker}（事件驱动）负责；
 * 本任务每 {@link NukkitWorldScanner#RECONCILE_INTERVAL_TICKS} tick 触发一次对账 diff
 * （事件遗漏自愈），并每 tick 驱动 ticking 系统一帧（同线程，无并发问题）。
 */
public class EcsScheduler {

    /** 引擎（系统 tick） */
    private final EcsEngine engine;

    /** 世界扫描器（diff） */
    private final NukkitWorldScanner scanner;

    /** 活动任务句柄 */
    private TaskHandler task;

    /**
     * 构造调度器。
     *
     * @param engine  引擎
     * @param scanner 世界扫描器
     */
    public EcsScheduler(EcsEngine engine, NukkitWorldScanner scanner) {
        this.engine = engine;
        this.scanner = scanner;
    }

    /**
     * 启动重复任务（每 tick）。
     *
     * @param plugin 插件实例
     * @throws IllegalStateException Server 未就绪或已启动
     */
    public synchronized void start(Plugin plugin) {
        if (task != null) {
            throw new IllegalStateException("ECS 调度器已启动");
        }
        task = Server.getInstance().getScheduler().scheduleRepeatingTask(plugin, this::onTick, 1);
    }

    /**
     * 存量初始扫描（引擎 start 时、主循环启动前调用一次；幂等，建快照基线并触发 enter 管线）。
     */
    public void initialScan() {
        scanner.scanAll();
    }

    /**
     * 停止重复任务。
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 单帧：按对账间隔触发世界 diff（事件跟踪的兜底，见 {@link NukkitWorldScanner}），
     * 后驱动 ticking 系统一帧。新实体由 {@link NukkitWorldTracker} 事件即时跟踪，
     * 不再依赖每 tick 扫描。
     */
    private void onTick() {
        try {
            if (engine.tickCount() % NukkitWorldScanner.RECONCILE_INTERVAL_TICKS == 0) {
                scanner.scanAll();
            }
        } catch (Exception e) {
            EcsLog.error("世界对账扫描异常", e);
        }
        engine.tick();
    }

    /**
     * 是否在运行。
     *
     * @return 运行中 true
     */
    public synchronized boolean running() {
        return task != null;
    }
}
