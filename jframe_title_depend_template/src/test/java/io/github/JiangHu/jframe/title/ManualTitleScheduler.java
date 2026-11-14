package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.title.nukkit.TitleScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动时钟调度器测试桩——不启动任何真实线程，由测试显式驱动时间前进。
 *
 * <ul>
 *   <li>{@link #fireDelayed()}：触发全部延迟任务（模拟瞬时模式到期）</li>
 *   <li>{@link #tickRepeating()}：触发全部周期任务一次（模拟动作栏续期周期）</li>
 *   <li>{@link #isPrimaryThread()} 恒为 true——{@code runOnPrimaryThread} 同步执行，
 *       数据更新后可立即断言发包结果</li>
 * </ul>
 */
public class ManualTitleScheduler implements TitleScheduler {

    /** 内部任务记录 */
    private static class TaskRecord {
        final Runnable runnable;
        final boolean repeating;

        TaskRecord(Runnable runnable, boolean repeating) {
            this.runnable = runnable;
            this.repeating = repeating;
        }
    }

    private final List<TaskRecord> delayedTasks = new ArrayList<>();
    private final List<TaskRecord> repeatingTasks = new ArrayList<>();
    private final List<Integer> delayedDelays = new ArrayList<>();
    private final List<Integer> repeatingPeriods = new ArrayList<>();
    private final List<TaskRecord> cancelledTasks = new ArrayList<>();

    @Override
    public Object scheduleDelayed(Runnable task, int delay) {
        TaskRecord record = new TaskRecord(task, false);
        delayedTasks.add(record);
        delayedDelays.add(delay);
        return record;
    }

    @Override
    public Object scheduleRepeating(Runnable task, int period) {
        TaskRecord record = new TaskRecord(task, true);
        repeatingTasks.add(record);
        repeatingPeriods.add(period);
        return record;
    }

    @Override
    public void cancel(Object handle) {
        if (handle instanceof TaskRecord) {
            TaskRecord record = (TaskRecord) handle;
            cancelledTasks.add(record);
            delayedTasks.remove(record);
            repeatingTasks.remove(record);
        }
    }

    @Override
    public boolean isPrimaryThread() {
        return true; // runOnPrimaryThread → 同步执行，便于测试断言
    }

    /** 触发全部未取消的延迟任务（模拟到期），触发后移除 */
    public void fireDelayed() {
        List<TaskRecord> snapshot = new ArrayList<>(delayedTasks);
        delayedTasks.clear();
        for (TaskRecord record : snapshot) {
            if (!cancelledTasks.contains(record)) {
                record.runnable.run();
            }
        }
    }

    /** 触发全部未取消的周期任务一次（模拟一个续期周期），任务保留 */
    public void tickRepeating() {
        for (TaskRecord record : new ArrayList<>(repeatingTasks)) {
            if (!cancelledTasks.contains(record)) {
                record.runnable.run();
            }
        }
    }

    /** 当前存活的延迟任务数 */
    public int delayedTaskCount() {
        return delayedTasks.size();
    }

    /** 当前存活的周期任务数 */
    public int repeatingTaskCount() {
        return repeatingTasks.size();
    }

    /** 最近一次延迟调度的 delay 参数（无调度时抛断言错误） */
    public int lastDelayedDelay() {
        if (delayedDelays.isEmpty()) {
            throw new AssertionError("无延迟调度记录");
        }
        return delayedDelays.get(delayedDelays.size() - 1);
    }

    /** 最近一次周期调度的 period 参数（无调度时抛断言错误） */
    public int lastRepeatingPeriod() {
        if (repeatingPeriods.isEmpty()) {
            throw new AssertionError("无周期调度记录");
        }
        return repeatingPeriods.get(repeatingPeriods.size() - 1);
    }
}
