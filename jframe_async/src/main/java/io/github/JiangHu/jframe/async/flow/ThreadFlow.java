package io.github.JiangHu.jframe.async.flow;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import io.github.JiangHu.jframe.core.module.PluginAware;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 线程切换流程服务 —— 异步线程与 Nukkit 主线程之间的优雅切换编排器。
 * <p>
 * 本类是 {@code jframe_async} 模块的上层封装，基于已有的 {@link ThreadAPI}（命名线程池）
 * 和 Nukkit {@link cn.nukkit.scheduler.ServerScheduler}（主线程调度），提供两种线程切换语法糖：
 *
 * <h3>模式一：回调链（{@link FlowChain}）</h3>
 * <pre>{@code
 * threadFlow.create()
 *     .async("database", () -> userDao.findByName("Steve"))  // 异步线程
 *     .thenMain(user -> player.teleport(user.getSpawn()))     // 主线程
 *     .thenAsync("cache", () -> saveCache())                  // 异步线程
 *     .onError(e -> logger.error("流程失败", e))
 *     .start();
 * }</pre>
 *
 * <h3>模式二：虚拟线程同步风格（{@link FlowContext}）</h3>
 * <pre>{@code
 * threadFlow.virtual(ctx -> {
 *     var data = ctx.awaitAsync("database", () -> loadFromDB());  // 阻塞虚拟线程
 *     ctx.awaitMain(() -> player.teleport(data.getSpawn()));      // 阻塞虚拟线程
 *     ctx.awaitAsync("cache", () -> saveCache(data));             // 阻塞虚拟线程
 * });
 * }</pre>
 *
 * <h3>架构定位</h3>
 * <pre>{@code
 *   用户代码
 *     └── ThreadFlow（编排层：决定任务在哪执行、如何切换）
 *           ├── ThreadAPI（基础设施：命名线程池、生命周期、异常处理）
 *           │     └── ExecutorService（实际 OS 线程）
 *           └── Nukkit ServerScheduler（主线程调度）
 * }</pre>
 *
 * <h3>Spring 装配</h3>
 * <ul>
 *   <li>本类为 Spring 单例 Bean，通过构造器注入 {@link ThreadAPI}</li>
 *   <li>实现 {@link PluginAware}，由 {@code JFrameMain.bindPlugin()} 自动注入 {@link Plugin}</li>
 *   <li>业务侧通过 {@code JFrameMain.getThreadFlow()} 获取</li>
 * </ul>
 *
 * @see FlowChain
 * @see FlowContext
 * @see ThreadAPI
 */
public class ThreadFlow implements PluginAware {

    /** 命名线程池服务，由 Spring 构造器注入 */
    private final ThreadAPI threadAPI;

    /** 关联插件，由 {@link PluginAware} 机制自动注入，用于 Nukkit 主线程调度 */
    @Getter
    private Plugin plugin;

    /**
     * Spring 构造器注入。
     *
     * @param threadAPI 命名线程池服务
     */
    public ThreadFlow(ThreadAPI threadAPI) {
        this.threadAPI = threadAPI;
    }

    // ==================== PluginAware ====================

    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 入口：回调链模式 ====================

    /**
     * 创建一个空的流程链（起始类型为 {@code Void}），用于回调链模式。
     * <p>
     * 返回的 {@link FlowChain} 可通过 {@code async / thenAsync / thenMain / main}
     * 等方法链式编排线程切换，最终调用 {@link FlowChain#start()} 启动。
     *
     * @return 新的流程链
     * @throws IllegalStateException 插件尚未绑定时抛出
     */
    public FlowChain<Void> create() {
        requireReady();
        return new FlowChain<>(this);
    }

    // ==================== 入口：虚拟线程模式 ====================

    /**
     * 在虚拟线程上执行同步风格的线程切换代码。
     * <p>
     * 内部通过 {@link Thread#startVirtualThread(Runnable)} 创建虚拟线程。
     * 代码体中通过 {@link FlowContext} 的 {@code awaitAsync / awaitMain} 方法切换线程，
     * 这些方法会 <b>park 虚拟线程</b>（不阻塞 OS 线程），在目标线程完成任务后自动恢复。
     *
     * <h3>示例</h3>
     * <pre>{@code
     * threadFlow.virtual(ctx -> {
     *     String data = ctx.awaitAsync("io", () -> loadFromDB());
     *     ctx.awaitMain(() -> player.sendMessage("加载完成: " + data));
     *     ctx.awaitAsync("cache", () -> saveCache(data));
     * });
     * }</pre>
     *
     * @param body 流程体，通过 {@link FlowContext} 的 await 方法切换线程
     * @throws IllegalStateException 插件尚未绑定时抛出
     */
    public void virtual(Consumer<FlowContext> body) {
        requireReady();
        Thread.startVirtualThread(() -> {
            FlowContext ctx = new FlowContext(this);
            try {
                body.accept(ctx);
            } catch (Exception e) {
                logVirtualThreadError(e);
            }
        });
    }

    // ==================== 内部：供 FlowChain / FlowContext 使用 ====================

    /**
     * 获取底层 {@link ThreadAPI} 实例（包级可见，供测试清理使用）。
     *
     * @return ThreadAPI 实例
     */
    ThreadAPI getThreadAPI() {
        return threadAPI;
    }

    /**
     * 获取指定名称的异步线程池执行器。
     * <p>
     * 队列必须已通过 {@link ThreadAPI#createThreadTask(String)} 预先创建，
     * 否则抛出 {@link IllegalArgumentException}，避免拼写错误意外创建线程。
     *
     * @param queue 队列名称
     * @return 对应的执行器
     * @throws IllegalArgumentException 队列不存在时抛出
     */
    ExecutorService getExecutor(String queue) {
        ExecutorService executor = threadAPI.getThreadExecutor(queue);
        if (executor == null) {
            throw new IllegalArgumentException(
                    "线程队列 '" + queue + "' 不存在，请先通过 ThreadAPI.createThreadTask() 创建");
        }
        return executor;
    }

    /**
     * 向异步队列提交任务（fire-and-forget）。
     * <p>
     * 先通过 {@link #getExecutor(String)} 校验队列存在性（队列不存在时抛出
     * {@link IllegalArgumentException}），再通过 {@link ThreadAPI#pushTask} 提交，
     * 保留 ThreadAPI 的异常包装处理。
     *
     * @param queue    队列名称
     * @param runnable 要执行的任务
     * @throws IllegalArgumentException 队列不存在时抛出
     */
    void fireAsync(String queue, Runnable runnable) {
        getExecutor(queue);
        threadAPI.pushTask(queue, runnable);
    }

    /**
     * 在 Nukkit 主线程上执行任务，返回 {@link CompletableFuture}。
     * <p>
     * 通过 {@link cn.nukkit.scheduler.ServerScheduler#scheduleTask(Plugin, Runnable)}
     * 提交到主线程，任务完成后 {@code complete} / {@code completeExceptionally}。
     * <p>
     * {@code protected} 修饰，便于单元测试覆盖为同步执行（避免依赖真实 Nukkit 服务器）。
     *
     * @param task 要在主线程执行的任务
     * @param <T>  返回值类型
     * @return 任务完成时完成的 Future
     */
    protected <T> CompletableFuture<T> scheduleOnMain(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * 向主线程提交任务（fire-and-forget），不等待完成。
     *
     * @param runnable 要执行的任务
     */
    protected void fireMain(Runnable runnable) {
        Server.getInstance().getScheduler().scheduleTask(plugin, runnable);
    }

    /**
     * 当前线程是否为 Nukkit 主线程。
     * <p>
     * {@code protected} 修饰，便于单元测试覆盖。
     *
     * @return {@code true} 表示当前在主线程
     */
    protected boolean isPrimaryThread() {
        return Server.getInstance().isPrimaryThread();
    }

    /**
     * 记录虚拟线程执行异常。
     * <p>
     * {@code protected} 修饰，便于单元测试覆盖（避免依赖真实 Nukkit 服务器）。
     * 生产环境中通过 Nukkit {@link Server#getLogger()} 输出错误日志。
     *
     * @param e 虚拟线程中抛出的异常
     */
    protected void logVirtualThreadError(Exception e) {
        Server server = Server.getInstance();
        if (server != null) {
            server.getLogger().error("ThreadFlow 虚拟线程执行异常", e);
        } else {
            // 测试环境或 Server 未初始化时的兜底
            e.printStackTrace();
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 校验插件已绑定。
     *
     * @throws IllegalStateException 插件未绑定时抛出
     */
    private void requireReady() {
        if (plugin == null) {
            throw new IllegalStateException(
                    "ThreadFlow 插件尚未绑定，请先导入 async 模块（JFrameMain 自动绑定）");
        }
    }
}
