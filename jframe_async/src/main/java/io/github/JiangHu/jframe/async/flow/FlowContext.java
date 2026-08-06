package io.github.JiangHu.jframe.async.flow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 虚拟线程流程上下文 —— 在虚拟线程中提供同步风格的线程切换能力。
 * <p>
 * 由 {@link ThreadFlow#virtual(java.util.function.Consumer)} 创建并传入流程体。
 * 通过 {@code awaitAsync} / {@code awaitMain} 方法在异步线程与主线程之间切换，
 * 这些方法会 <b>park 虚拟线程</b>（不阻塞 OS 线程），在目标线程完成任务后自动恢复。
 *
 * <h3>核心原理</h3>
 * <pre>{@code
 *   虚拟线程（JVM 管理，不占 OS 线程）
 *     │
 *     │  data = ctx.awaitAsync("io", () -> loadFromDB())
 *     │    ├── 提交任务到 ThreadAPI 的 "io" 队列（OS 线程执行）
 *     │    ├── CompletableFuture.join() → 虚拟线程 park
 *     │    └── io 线程完成 → unpark 虚拟线程 → 返回 data
 *     │
 *     │  ctx.awaitMain(() -> player.teleport(data))
 *     │    ├── 提交到 Nukkit 主线程调度器
 *     │    ├── join() → 虚拟线程 park
 *     │    └── 下一 tick 主线程执行 → unpark
 *     │
 *     │  ctx.awaitAsync("cache", () -> saveCache(data))
 *     │    └── ... 同上 ...
 * }</pre>
 *
 * <h3>与回调链（{@link FlowChain}）的区别</h3>
 * <table>
 *   <tr><th>维度</th><th>FlowChain（回调链）</th><th>FlowContext（虚拟线程）</th></tr>
 *   <tr><td>代码风格</td><td>链式 Lambda</td><td>同步线性代码</td></tr>
 *   <tr><td>变量传递</td><td>类型参数 {@code <T>}</td><td>普通局部变量</td></tr>
 *   <tr><td>控制流</td><td>受限（无法跨步骤 if/for）</td><td>完全自由（if/for/try 随意用）</td></tr>
 *   <tr><td>异常处理</td><td>{@code onError(handler)}</td><td>标准 try/catch</td></tr>
 *   <tr><td>调试</td><td>栈跟踪断裂</td><td>连续栈跟踪</td></tr>
 * </table>
 *
 * <h3>完整示例</h3>
 * <pre>{@code
 * threadFlow.virtual(ctx -> {
 *     // 异步线程：数据库查询
 *     UserData data = ctx.awaitAsync("database", () -> userDao.findByName("Steve"));
 *
 *     // 主线程：安全操作实体
 *     ctx.awaitMain(() -> {
 *         player.sendMessage("欢迎, " + data.getName());
 *         player.getInventory().addItem(data.getRewardItems());
 *     });
 *
 *     // 异步线程：写日志
 *     int logId = ctx.awaitAsync("log", () -> logService.record(data.getId()));
 *
 *     // 主线程：最终通知
 *     ctx.awaitMain(() -> player.sendMessage("登录完成! 日志ID: " + logId));
 * });
 * }</pre>
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><b>主线程防死锁</b>：{@link #awaitMain} 检测当前是否已在主线程，若是则直接同步执行</li>
 *   <li><b>队列严格校验</b>：引用不存在的队列时抛出异常，避免拼写错误意外创建线程</li>
 *   <li><b>不阻塞 OS 线程</b>：所有 await 通过虚拟线程 park 实现，载体线程被释放</li>
 * </ul>
 *
 * @see ThreadFlow#virtual(java.util.function.Consumer)
 */
public class FlowContext {

    /** 关联的 ThreadFlow 服务，提供线程池与主线程桥接 */
    private final ThreadFlow flow;

    /**
     * 包级构造器，由 {@link ThreadFlow#virtual(java.util.function.Consumer)} 创建。
     *
     * @param flow 关联的 ThreadFlow 服务
     */
    FlowContext(ThreadFlow flow) {
        this.flow = flow;
    }

    // ==================== 等待型（阻塞虚拟线程） ====================

    /**
     * 在指定异步队列上执行任务并等待结果。
     * <p>
     * 将任务提交到 ThreadAPI 的命名队列（OS 线程执行），当前虚拟线程 park，
     * 任务完成后自动恢复并返回结果。
     * <p>
     * 队列必须已通过 {@code ThreadAPI.createThreadTask()} 预先创建。
     *
     * @param queue 队列名称
     * @param task  要执行的任务，返回值作为本方法返回值
     * @param <T>   返回值类型
     * @return 任务执行结果
     * @throws IllegalArgumentException 队列不存在时抛出
     * @throws CompletionException 如果任务抛出异常（可通过 try/catch 捕获）
     */
    public <T> T awaitAsync(String queue, Supplier<T> task) {
        ExecutorService executor = flow.getExecutor(queue);
        return CompletableFuture.supplyAsync(task, executor).join();
    }

    /**
     * 在指定异步队列上执行任务并等待完成（无返回值）。
     *
     * @param queue 队列名称
     * @param task  要执行的任务
     * @throws CompletionException 如果任务抛出异常
     */
    public void awaitAsync(String queue, Runnable task) {
        awaitAsync(queue, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 在 Nukkit 主线程上执行任务并等待结果。
     * <p>
     * 通过 {@link cn.nukkit.scheduler.ServerScheduler#scheduleTask} 提交到主线程，
     * 当前虚拟线程 park，下一 tick 主线程执行后恢复。
     * <p>
     * <b>死锁防护</b>：若当前已在主线程（{@link #isMainThread()} 返回 {@code true}），
     * 直接同步执行，不提交调度（避免主线程等待自己）。
     *
     * @param task 要在主线程执行的任务，返回值作为本方法返回值
     * @param <T>  返回值类型
     * @return 任务执行结果
     * @throws CompletionException 如果任务抛出异常
     */
    public <T> T awaitMain(Supplier<T> task) {
        if (isMainThread()) {
            return task.get();
        }
        return flow.scheduleOnMain(task).join();
    }

    /**
     * 在 Nukkit 主线程上执行任务并等待完成（无返回值）。
     *
     * @param task 要在主线程执行的任务
     * @throws CompletionException 如果任务抛出异常
     */
    public void awaitMain(Runnable task) {
        awaitMain(() -> {
            task.run();
            return null;
        });
    }

    // ==================== 非等待型（fire-and-forget） ====================

    /**
     * 向异步队列提交任务，不等待完成。
     * <p>
     * 等价于 {@code threadAPI.pushTask(queue, task)}，虚拟线程不阻塞。
     * 适用于不需要等待结果的场景（如异步写日志）。
     *
     * @param queue 队列名称
     * @param task  要执行的任务
     */
    public void fireAsync(String queue, Runnable task) {
        flow.fireAsync(queue, task);
    }

    /**
     * 向 Nukkit 主线程提交任务，不等待完成。
     * <p>
     * 等价于 {@code Server.getInstance().getScheduler().scheduleTask(plugin, task)}，
     * 虚拟线程不阻塞。适用于不需要等待结果的主线程操作。
     *
     * @param task 要在主线程执行的任务
     */
    public void fireMain(Runnable task) {
        flow.fireMain(task);
    }

    // ==================== 线程检测 ====================

    /**
     * 当前线程是否为 Nukkit 主线程。
     * <p>
     * 用于在虚拟线程体中判断当前执行上下文，或在 {@link #awaitMain} 前做条件判断。
     *
     * @return {@code true} 表示当前在 Nukkit 主线程
     */
    public boolean isMainThread() {
        return flow.isPrimaryThread();
    }
}
