package io.github.JiangHu.jframe.async;

import io.github.JiangHu.jframe.async.flow.ThreadFlow;
import io.github.JiangHu.jframe.async.release.ReleaseAPI;
import io.github.JiangHu.jframe.async.task.TaskAPI;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;

import java.util.Set;

/**
 * 异步模块工厂 — 一对一替代原 {@code AsyncSpringConfig} + {@code async-spring.xml}。
 * <p>
 * 装配（与原 XML 等价）：ThreadAPI、TaskAPI、ThreadFlow(threadAPI)、ReleaseAPI（各自独立）。
 * 关闭顺序与原 {@code JFrameMain.onDisable} 一致：线程池 → 可挂起任务 → 缓释调度器。
 */
public class AsyncModule implements JFrameModule {

    private ThreadAPI threadAPI;
    private TaskAPI taskAPI;
    private ReleaseAPI releaseAPI;

    @Override
    public String id() {
        return "async";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(ThreadAPI.class, TaskAPI.class, ThreadFlow.class, ReleaseAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        threadAPI = new ThreadAPI();
        ctx.register(ThreadAPI.class, threadAPI);

        taskAPI = new TaskAPI();
        ctx.bind(taskAPI);
        ctx.register(TaskAPI.class, taskAPI);

        ThreadFlow threadFlow = new ThreadFlow(threadAPI);
        ctx.bind(threadFlow);
        ctx.register(ThreadFlow.class, threadFlow);

        releaseAPI = new ReleaseAPI();
        ctx.bind(releaseAPI);
        ctx.register(ReleaseAPI.class, releaseAPI);
    }

    @Override
    public int shutdownOrder() {
        return 30;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (threadAPI != null) {
            threadAPI.stopAll();
        }
        if (taskAPI != null) {
            taskAPI.cancelAll();
        }
        if (releaseAPI != null) {
            releaseAPI.close();
        }
    }
}
