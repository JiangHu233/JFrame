package io.github.JiangHu.jframe.title.nukkit;

/**
 * 调度适配接口：隔离对 Nukkit {@code ServerScheduler} 的直接依赖
 *
 * <p>title 模块内所有延迟 / 周期任务（瞬时模式到期清除、动作栏周期续期）
 * 与主线程调度均经本接口完成；测试以手动时钟桩替换以脱离 Nukkit 运行时。</p>
 */
public interface TitleScheduler {

    /**
     * 调度延迟任务（一次性）
     *
     * @param task       任务
     * @param delayTicks 延迟（tick）
     * @return 任务句柄（用于取消；实现保证非 null）
     */
    Object scheduleDelayed(Runnable task, int delayTicks);

    /**
     * 调度周期任务
     *
     * @param task         任务
     * @param periodTicks  周期（tick）
     * @return 任务句柄（用于取消；实现保证非 null）
     */
    Object scheduleRepeating(Runnable task, int periodTicks);

    /**
     * 取消任务；句柄为 null 时静默忽略（幂等）
     *
     * @param handle {@link #scheduleDelayed} / {@link #scheduleRepeating} 返回的句柄
     */
    void cancel(Object handle);

    /** 当前线程是否为主线程 */
    boolean isPrimaryThread();

    /**
     * 在主线程执行任务：已在主线程则同步执行，否则转交主线程调度
     *
     * @param task 任务
     */
    default void runOnPrimaryThread(Runnable task) {
        if (isPrimaryThread()) {
            task.run();
        } else {
            scheduleDelayed(task, 1);
        }
    }

    /**
     * 创建默认调度器（绑定 Nukkit Server 调度器）
     *
     * @return 默认调度器实例
     */
    static TitleScheduler defaultScheduler() {
        return new DefaultTitleScheduler();
    }
}
