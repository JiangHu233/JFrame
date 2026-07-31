package io.github.JiangHu.jframe.thread;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 线程任务服务。
 * <p>
 * 提供基于「命名线程池」的异步任务管理能力。每个任务队列通过名称标识，
 * 内部使用单线程执行器（{@link Executors#newSingleThreadExecutor()}），
 * 从而保证同一队列中的任务按提交顺序串行执行。
 * <p>
 * 典型用法：
 * <pre>{@code
 * ThreadAPI service = new ThreadAPI();
 * service.createThreadTask("data-save");           // 创建名为 "data-save" 的任务队列
 * service.pushTask("data-save", () -> saveData()); // 向该队列提交任务
 * service.removeThreadTask("data-save");           // 关闭并移除单个队列
 * service.stopAll();                               // 关闭所有队列
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>队列的创建（{@link #createThreadTask(String)}）基于 {@link ConcurrentHashMap#computeIfAbsent}，
 *       多线程并发创建同名队列不会产生竞态，也不会泄漏被覆盖的执行器。</li>
 *   <li>每个队列的工作线程由自定义 {@link ThreadFactory} 创建，命名为 {@code jframe-thread-{name}-{n}}，
 *       便于在线程转储 / 日志中定位；线程为守护线程，不会阻止 JVM 退出。</li>
 * </ul>
 */
public class ThreadAPI {

    /**
     * 关闭线程池时等待已提交任务完成的默认最长时间（单位：秒）。
     */
    public static final int DEFAULT_AWAIT_TIME = 1;

    /**
     * 关闭线程池时，等待已提交任务完成的最长时间（单位：秒）。
     * <p>
     * 默认值为 {@link #DEFAULT_AWAIT_TIME}，可通过 {@link #setAwaitTime(Integer)} 按实例调整，
     * 避免使用全局可变静态字段造成跨实例污染。
     */
    @Getter
    @Setter
    private Integer awaitTime = DEFAULT_AWAIT_TIME;

    /**
     * 全局异常处理器。
     * <p>
     * 当任务执行抛出异常时会被捕获，并交由此处理器处理。
     * 为 {@code null} 时异常将被静默忽略。
     */
    @Getter
    @Setter
    private Consumer<Exception> exceptionHandler = null;

    /**
     * 名称 -> 单线程执行器 的映射表。
     * <p>
     * 每个名称对应一个独立的任务队列，队列内任务串行执行。
     */
    private final Map<String, ExecutorService> threadExecutors = new ConcurrentHashMap<>();

    /**
     * 工作线程全局序号，用于为每个队列的工作线程生成唯一编号，便于调试。
     */
    private final AtomicInteger threadIndex = new AtomicInteger(0);

    /**
     * 创建一个指定名称的任务队列（线程池）。
     * <p>
     * 基于 {@link ConcurrentHashMap#computeIfAbsent} 实现，保证并发安全：
     * 多个线程同时创建同名队列时，只会创建一个执行器，不会出现「后者覆盖前者、
     * 被覆盖的执行器未 shutdown 导致线程泄漏」的竞态问题。
     * <p>
     * 若该名称已存在，则不会重复创建（保持原有队列不变）。
     *
     * @param name 任务队列名称
     */
    public void createThreadTask(String name) {
        threadExecutors.computeIfAbsent(name, this::createNamedExecutor);
    }

    /**
     * 创建一个带自定义命名的单线程执行器。
     * <p>
     * 工作线程命名为 {@code jframe-thread-{name}-{n}}，替代默认的 {@code pool-N-thread-M}，
     * 便于在线程转储与日志中快速定位是哪个任务队列。线程设为守护线程，
     * 避免在插件卸载 / JVM 关闭时阻塞退出。
     *
     * @param name 任务队列名称，用于线程命名前缀
     * @return 单线程执行器
     */
    private ExecutorService createNamedExecutor(String name) {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "jframe-thread-" + name + "-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /**
     * 获取指定名称的任务队列（线程池）。
     *
     * @param name 任务队列名称
     * @return 对应的执行器；若不存在则返回 {@code null}
     */
    public ExecutorService getThreadExecutor(String name) {
        return threadExecutors.get(name);
    }

    /**
     * 向指定名称的任务队列提交一个任务。
     * <p>
     * 任务会被包装后执行，执行过程中的异常由 {@link #exceptionHandler} 统一处理。
     * <p>
     * 注意：调用前需确保已通过 {@link #createThreadTask(String)} 创建该队列，
     * 否则会抛出 {@link NullPointerException}。
     *
     * @param name     任务队列名称
     * @param runnable 要执行的任务
     */
    public void pushTask(String name, @NonNull Runnable runnable) {
        ExecutorService executor = threadExecutors.get(name);
        if (executor == null) {
            throw new NullPointerException("Thread task executor not found: " + name);
        }
        executor.execute(() -> runTaskWrapper(runnable));
    }

    /**
     * 移除并关闭指定名称的任务队列（线程池）。
     * <p>
     * 会尝试等待该队列中已提交的任务在 {@link #awaitTime} 内完成，随后执行
     * {@link ExecutorService#shutdown() 关闭} 并从内部映射中移除。
     *
     * @param name 任务队列名称
     * @return 若队列存在并已移除返回 {@code true}；若不存在返回 {@code false}
     */
    public boolean removeThreadTask(String name) {
        ExecutorService executor = threadExecutors.remove(name);
        if (executor == null) {
            return false;
        }
        shutdownExecutor(executor);
        return true;
    }

    /**
     * 关闭所有任务队列（线程池）。
     * <p>
     * 会尝试等待每个队列中已提交的任务在 {@link #awaitTime} 内完成，
     * 随后执行 {@link ExecutorService#shutdown() 关闭}。
     */
    public void stopAll() {
        for (ExecutorService executorService : threadExecutors.values()) {
            shutdownExecutor(executorService);
        }
        this.threadExecutors.clear();
    }

    /**
     * 关闭服务（等价于 {@link #stopAll()}）。
     * <p>
     * 提供语义更直观的关闭入口。
     */
    public void close() {
        stopAll();
    }

    /**
     * 关闭单个执行器并等待其终止。
     * <p>
     * 先 {@link ExecutorService#shutdown() 停止接收新任务}，再在 {@link #awaitTime} 内
     * {@link ExecutorService#awaitTermination 等待已提交任务完成}；若等待被中断则恢复中断状态。
     *
     * @param executor 要关闭的执行器
     */
    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            executor.awaitTermination(awaitTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 任务执行包装器：统一捕获任务执行过程中的异常。
     * <p>
     * 若设置了 {@link #exceptionHandler}，则将异常交由其处理；
     * 否则异常被静默忽略。
     *
     * @param runnable 要执行的任务
     */
    protected void runTaskWrapper(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            if (exceptionHandler != null) {
                exceptionHandler.accept(e);
            }
        }
    }
}
