package io.github.JiangHu.jframe.db.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * jframe_db 内部执行器（每 DbAPI 一份，随 shutdown 关闭）。
 * <ul>
 *   <li>异步方法：虚拟线程 per-task（JDK 24，daemon）——不依赖 jframe_async
 *       模块，开销极小</li>
 *   <li>周期任务：单线程 daemon 调度池——数据区域心跳、实例名册重播</li>
 * </ul>
 */
public final class DbExecutors {

    /** 异步操作执行器（虚拟线程 per task） */
    private final ExecutorService async =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jframe-db-async-", 0).factory());

    /** 周期任务调度器（心跳 / 重播） */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jframe-db-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 调度周期任务（固定延迟）。 */
    public ScheduledFuture<?> scheduleFixedDelay(Runnable task, long initialDelay,
                                                 long period, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(task, initialDelay, period, unit);
    }

    /** 执行一次性异步任务。 */
    public void async(Runnable task) {
        async.execute(task);
    }

    /** 异步执行并返回 Future（DataManager 的 *Async 方法底层）。 */
    public <R> java.util.concurrent.CompletableFuture<R> supplyAsync(
            java.util.function.Supplier<R> supplier) {
        java.util.concurrent.CompletableFuture<R> future = new java.util.concurrent.CompletableFuture<>();
        async.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** 关闭全部执行器（关服调用）。 */
    public void shutdown() {
        async.shutdownNow();
        scheduler.shutdownNow();
    }
}
