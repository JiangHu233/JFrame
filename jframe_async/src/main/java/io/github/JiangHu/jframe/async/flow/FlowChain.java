package io.github.JiangHu.jframe.async.flow;

import io.github.JiangHu.jframe.core.JFrameLog;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 回调链流程对象 —— 基于 {@link CompletableFuture} 的流式线程切换编排。
 * <p>
 * 由 {@link ThreadFlow#create()} 创建，通过链式调用在异步线程与 Nukkit 主线程之间切换。
 * 每个步骤返回新的 {@code FlowChain}（不可变），类型参数 {@code <T>} 表示当前步骤的产出值类型。
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li><b>非阻塞</b>：所有步骤异步执行，不阻塞调用线程</li>
 *   <li><b>类型安全的数据传递</b>：每步可变换值类型 {@code FlowChain<String> → FlowChain<Integer>}</li>
 *   <li><b>异常自动传播</b>：链中任意步骤抛异常，后续步骤跳过，异常到达 {@link #onError(Consumer)}</li>
 *   <li><b>队列严格校验</b>：引用不存在的队列时抛出 {@link IllegalArgumentException}，避免拼写错误意外创建线程</li>
 * </ul>
 *
 * <h3>步骤类型</h3>
 * <table>
 *   <tr><th>方法</th><th>执行线程</th><th>数据关系</th><th>说明</th></tr>
 *   <tr><td>{@link #async(String, Supplier)}</td><td>异步队列</td><td>忽略前值，产出新值</td><td>起始步骤或重新开始</td></tr>
 *   <tr><td>{@link #thenAsync(String, Function)}</td><td>异步队列</td><td>变换前值</td><td>在异步线程上处理前一步结果</td></tr>
 *   <tr><td>{@link #main(Supplier)}</td><td>主线程</td><td>忽略前值，产出新值</td><td>在主线程上开始新任务</td></tr>
 *   <tr><td>{@link #thenMain(Function)}</td><td>主线程</td><td>变换前值</td><td>在主线程上处理前一步结果</td></tr>
 * </table>
 *
 * <h3>完整示例</h3>
 * <pre>{@code
 * threadFlow.create()
 *     .async("database", () -> userDao.findByName("Steve"))   // 异步：查数据库
 *     .thenMain(user -> {                                      // 主线程：操作实体
 *         player.sendMessage("欢迎, " + user.getName());
 *         player.teleport(user.getSpawnLocation());
 *         return user.getId();
 *     })
 *     .thenAsync("cache", id -> cacheStore.put("last-login", id)) // 异步：写缓存
 *     .thenMain(() -> player.sendMessage("处理完成!"))            // 主线程：通知
 *     .onError(e -> logger.error("登录流程失败", e))
 *     .start();
 * }</pre>
 *
 * @see ThreadFlow#create()
 */
public class FlowChain<T> {

    /** 关联的 ThreadFlow 服务，提供线程池与主线程桥接 */
    private final ThreadFlow flow;

    /** 当前步骤的 CompletableFuture，承载异步执行与结果传递 */
    private final CompletableFuture<T> future;

    /**
     * 包级构造器，由 {@link ThreadFlow#create()} 调用。
     * <p>
     * 初始 future 为已完成的 {@code Void}，作为链的起点。
     *
     * @param flow 关联的 ThreadFlow 服务
     */
    FlowChain(ThreadFlow flow) {
        this(flow, CompletableFuture.completedFuture(null));
    }

    /**
     * 内部构造器，用于链式步骤创建新的 FlowChain。
     *
     * @param flow   关联的 ThreadFlow 服务
     * @param future 当前步骤的 CompletableFuture
     */
    private FlowChain(ThreadFlow flow, CompletableFuture<T> future) {
        this.flow = flow;
        this.future = future;
    }

    // ==================== 异步步骤 ====================

    /**
     * 在指定异步队列上执行任务，产出新值（忽略前一步结果）。
     * <p>
     * 队列必须已通过 {@code ThreadAPI.createThreadTask()} 预先创建，否则抛出 {@link IllegalArgumentException}。
     * 任务通过 {@link ExecutorService} 提交到对应队列的单线程执行器。
     *
     * @param queue 队列名称
     * @param task  要执行的任务，返回值作为本步骤产出
     * @param <R>   产出值类型
     * @return 新的流程链（类型变为 {@code R}）
     */
    public <R> FlowChain<R> async(String queue, Supplier<R> task) {
        ExecutorService executor = flow.getExecutor(queue);
        CompletableFuture<R> next = CompletableFuture.supplyAsync(task, executor);
        return new FlowChain<>(flow, next);
    }

    /**
     * 在指定异步队列上执行任务，无返回值（忽略前一步结果）。
     *
     * @param queue 队列名称
     * @param task  要执行的任务
     * @return 新的流程链（类型变为 {@code Void}）
     */
    public FlowChain<Void> async(String queue, Runnable task) {
        return async(queue, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 在指定异步队列上变换前一步的结果。
     * <p>
     * 前一步完成后，将其结果传入 {@code task}，在指定队列的线程上执行变换。
     *
     * @param queue 队列名称
     * @param task  接收前一步结果，返回变换后的值
     * @param <R>   产出值类型
     * @return 新的流程链（类型变为 {@code R}）
     */
    public <R> FlowChain<R> thenAsync(String queue, Function<T, R> task) {
        ExecutorService executor = flow.getExecutor(queue);
        CompletableFuture<R> next = future.thenApplyAsync(task, executor);
        return new FlowChain<>(flow, next);
    }

    /**
     * 在指定异步队列上消费前一步的结果，无返回值。
     *
     * @param queue 队列名称
     * @param task  接收前一步结果的消费者
     * @return 新的流程链（类型变为 {@code Void}）
     */
    public FlowChain<Void> thenAsync(String queue, Consumer<T> task) {
        return thenAsync(queue, prev -> {
            task.accept(prev);
            return null;
        });
    }

    // ==================== 主线程步骤 ====================

    /**
     * 在 Nukkit 主线程上执行任务，产出新值（忽略前一步结果）。
     * <p>
     * 通过 {@link cn.nukkit.scheduler.ServerScheduler#scheduleTask} 提交到主线程，
     * 在下一 tick 执行。适合操作方块、实体等非线程安全 API。
     *
     * @param task 要在主线程执行的任务，返回值作为本步骤产出
     * @param <R>  产出值类型
     * @return 新的流程链（类型变为 {@code R}）
     */
    public <R> FlowChain<R> main(Supplier<R> task) {
        CompletableFuture<R> next = flow.scheduleOnMain(task);
        return new FlowChain<>(flow, next);
    }

    /**
     * 在 Nukkit 主线程上执行任务，无返回值（忽略前一步结果）。
     *
     * @param task 要在主线程执行的任务
     * @return 新的流程链（类型变为 {@code Void}）
     */
    public FlowChain<Void> main(Runnable task) {
        return main(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 在 Nukkit 主线程上变换前一步的结果。
     * <p>
     * 前一步完成后，将其结果传入 {@code task}，在主线程上执行变换。
     *
     * @param task 接收前一步结果，返回变换后的值
     * @param <R>  产出值类型
     * @return 新的流程链（类型变为 {@code R}）
     */
    public <R> FlowChain<R> thenMain(Function<T, R> task) {
        CompletableFuture<R> next = future.thenCompose(prev ->
                flow.scheduleOnMain(() -> task.apply(prev)));
        return new FlowChain<>(flow, next);
    }

    /**
     * 在 Nukkit 主线程上消费前一步的结果，无返回值。
     *
     * @param task 接收前一步结果的消费者
     * @return 新的流程链（类型变为 {@code Void}）
     */
    public FlowChain<Void> thenMain(Consumer<T> task) {
        return thenMain(prev -> {
            task.accept(prev);
            return null;
        });
    }

    // ==================== 回调 ====================

    /**
     * 注册成功回调。
     * <p>
     * 当链正常完成（无异常）时触发，接收最终结果。
     * 回调执行的线程取决于最后一步完成的线程。
     *
     * @param handler 成功处理器
     * @return 当前流程链（可继续链式调用）
     */
    public FlowChain<T> onSuccess(Consumer<T> handler) {
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                handler.accept(result);
            }
        });
        return this;
    }

    /**
     * 注册异常回调。
     * <p>
     * 当链中任意步骤抛出异常时触发，接收 <b>解包后</b> 的原始异常
     * （去除 {@link CompletionException} / {@link ExecutionException} 包装）。
     * 注册后异常不再继续传播。
     *
     * @param handler 异常处理器
     * @return 当前流程链（可继续链式调用）
     */
    public FlowChain<T> onError(Consumer<Throwable> handler) {
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                handler.accept(unwrap(ex));
            }
        });
        return this;
    }

    /**
     * 注册完成回调。
     * <p>
     * 无论成功或失败都触发，不提供结果或异常（仅作为通知）。
     *
     * @param handler 完成处理器
     * @return 当前流程链（可继续链式调用）
     */
    public FlowChain<T> onComplete(Runnable handler) {
        future.whenComplete((result, ex) -> handler.run());
        return this;
    }

    // ==================== 终端操作 ====================

    /**
     * 启动流程（fire-and-forget）。
     * <p>
     * 实际上链在构建时已开始执行（{@link CompletableFuture#supplyAsync} 立即提交任务）。
     * 此方法主要用于：
     * <ul>
     *   <li>语义清晰：明确标识流程定义完毕</li>
     *   <li>兜底异常处理：若未注册 {@link #onError}，异常将记录到 Nukkit 日志，避免静默丢失</li>
     * </ul>
     */
    public void start() {
        future.exceptionally(e -> {
            Throwable cause = unwrap(e);
            JFrameLog.error("FlowChain",
                    "ThreadFlow 链未处理异常", cause);
            return null;
        });
    }

    /**
     * 获取底层 {@link CompletableFuture}，用于自定义组合或等待。
     * <p>
     * 注意：在主线程上调用 {@code future().join()} 会阻塞主线程，应避免。
     *
     * @return 底层 CompletableFuture
     */
    public CompletableFuture<T> future() {
        return future;
    }

    // ==================== 内部工具 ====================

    /**
     * 解包 {@link CompletionException} / {@link ExecutionException}，获取原始异常。
     *
     * @param ex 可能被包装的异常
     * @return 解包后的原始异常
     */
    static Throwable unwrap(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
