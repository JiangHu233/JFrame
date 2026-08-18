package io.github.JiangHu.jframe.ai.core.behavior;

/**
 * 计算载体:循环行为计算线程策略的抽象。
 * <p>
 * 行为骨架在主线程 tick 到点时调 {@link #dispatch(ComputeTask)},
 * 载体在<b>自选线程</b>执行 {@code task.run()} 的纯计算(解析目标 + 寻路),
 * 完成后以 {@code task.complete(...)} / {@code task.fail(...)} 交还——
 * 这两个方法线程无关,回主线程的兜底由骨架保证。
 *
 * <h3>内建载体</h3>
 * 见 {@link ComputeCarriers} 工厂:
 * <ul>
 *   <li>{@link ComputeCarriers#sync() sync()} —— 默认,主线程直接计算</li>
 *   <li>{@link ComputeCarriers#of(java.util.concurrent.Executor) of(Executor)} —— 委托外部线程池</li>
 * </ul>
 */
@FunctionalInterface
public interface ComputeCarrier {

    /**
     * 派发一次循环计算。
     * <p>
     * 载体保证 {@code task.run()} 最终被执行(同步或异步均可),
     * 且恰好一次;异常经 {@code task.fail} 交还而非吞掉。
     *
     * @param task 本次计算任务
     */
    void dispatch(ComputeTask task);
}
