package io.github.JiangHu.jframe.ai.core.behavior;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 内建计算载体工厂。
 * <p>
 * 循环行为未显式指定载体时的默认是 {@link #sync()}——主线程直接计算,
 * 语义与旧版行为完全一致;需要把寻路挪出主线程时用
 * {@link #of(Executor)} 委托外部线程池(如 jframe_async 的调度器)。
 */
public final class ComputeCarriers {

    private ComputeCarriers() {
    }

    /**
     * 同步载体(默认):dispatch 内直接 run + complete,全程主线程。
     * <p>
     * 与旧版行为语义一致——寻路计算发生在主线程 tick 内。
     */
    public static ComputeCarrier sync() {
        return SyncCarrier.INSTANCE;
    }

    /**
     * 委托载体:计算交给外部 {@link Executor} 执行。
     * <p>
     * {@code task.run()} 在 executor 线程执行;完成后 {@code complete/fail}
     * 由骨架回主线程(载体无需自己切线程)。
     *
     * @param executor 外部线程池
     * @return 委托载体
     */
    public static ComputeCarrier of(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return task -> executor.execute(() -> {
            try {
                task.complete(task.run());
            } catch (Throwable error) {
                task.fail(error);
            }
        });
    }

    private enum SyncCarrier implements ComputeCarrier {
        INSTANCE;

        @Override
        public void dispatch(ComputeTask task) {
            try {
                task.complete(task.run());
            } catch (Throwable error) {
                task.fail(error);
            }
        }
    }
}
