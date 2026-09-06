package io.github.JiangHu.jframe.title.nukkit;

import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;
import cn.nukkit.scheduler.TaskHandler;

import java.util.Objects;

/**
 * 默认调度器：委托 Nukkit {@code ServerScheduler}
 *
 * <p>实测 Nukkit MOT API 签名（与设计一致）：</p>
 * <ul>
 *   <li>{@code ServerScheduler.scheduleDelayedTask(Runnable, int)} → {@code TaskHandler}</li>
 *   <li>{@code ServerScheduler.scheduleRepeatingTask(Runnable, int)} → {@code TaskHandler}</li>
 *   <li>{@code TaskHandler.cancel()}</li>
 *   <li>{@code Server.isPrimaryThread()}</li>
 * </ul>
 *
 * <p>Server 未初始化（如单元测试环境）时 {@link #isPrimaryThread()} 返回 true，
 * 使 {@link #runOnPrimaryThread(Runnable)} 退化为同步执行，保证纯逻辑测试可跑。</p>
 */
public final class DefaultTitleScheduler implements TitleScheduler {

    @Override
    public Object scheduleDelayed(Runnable task, int delayTicks) {
        return scheduler().scheduleDelayedTask(Objects.requireNonNull(task, "任务不得为 null"),
                Math.max(0, delayTicks));
    }

    @Override
    public Object scheduleRepeating(Runnable task, int periodTicks) {
        return scheduler().scheduleRepeatingTask(Objects.requireNonNull(task, "任务不得为 null"),
                Math.max(1, periodTicks));
    }

    @Override
    public void cancel(Object handle) {
        if (handle instanceof TaskHandler taskHandler && !taskHandler.isCancelled()) {
            taskHandler.cancel();
        }
    }

    @Override
    public boolean isPrimaryThread() {
        try {
            return Server.getInstance().isPrimaryThread();
        } catch (IllegalStateException serverNotReady) {
            // Server 尚未初始化（单元测试环境）：视为主线程，任务同步执行
            return true;
        }
    }

    private ServerScheduler scheduler() {
        return Server.getInstance().getScheduler();
    }
}
