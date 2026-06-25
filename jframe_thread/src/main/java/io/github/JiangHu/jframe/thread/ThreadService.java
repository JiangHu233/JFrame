package io.github.JiangHu.jframe.thread;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * ThreadService service = new ThreadService();
 * service.createThreadTask("data-save");           // 创建名为 "data-save" 的任务队列
 * service.pushTask("data-save", () -> saveData()); // 向该队列提交任务
 * service.stopAll();                               // 关闭所有队列
 * }</pre>
 */
public class ThreadService {

    /**
     * 关闭线程池时，等待已提交任务完成的最长时间（单位：秒）。
     */
    public static Integer AWAIT_TIME = 1;

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
    private Map<String, ExecutorService> threadExecutors = new ConcurrentHashMap<>();


    /**
     * 创建一个指定名称的任务队列（线程池）。
     * <p>
     * 若该名称已存在，则不会重复创建（保持原有队列不变）。
     *
     * @param name 任务队列名称
     */
    public void createThreadTask(String name) {
        if (!threadExecutors.containsKey(name)) threadExecutors.put(name, Executors.newSingleThreadExecutor());
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
     * 否则会因获取到 {@code null} 而抛出空指针异常。
     *
     * @param name     任务队列名称
     * @param runnable 要执行的任务
     */
    public void pushTask(String name, @NonNull Runnable runnable) {
        if (!threadExecutors.containsKey(name)) throw new NullPointerException("Thread task executor not found: " + name);
        threadExecutors.get(name).execute(
                () -> runTaskWrapper(runnable)
        );
    }

    /**
     * 关闭所有任务队列（线程池）。
     * <p>
     * 会尝试等待每个队列中已提交的任务在 {@link #AWAIT_TIME} 内完成，
     * 随后执行 {@link ExecutorService#shutdown() 关闭}。
     */
    public void stopAll() {
        for (ExecutorService executorService : threadExecutors.values()) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(AWAIT_TIME, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
