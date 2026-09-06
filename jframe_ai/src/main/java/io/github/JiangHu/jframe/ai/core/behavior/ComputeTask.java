package io.github.JiangHu.jframe.ai.core.behavior;

import io.github.JiangHu.jframe.ai.core.executor.PlannedPath;

/**
 * 载体任务:一次循环计算的载体视图。
 * <p>
 * 由 {@link LoopBehavior} 骨架构造并交给 {@link ComputeCarrier} 派发——
 * 载体在<b>自选线程</b>执行 {@link #run()} 的纯计算,完成后以
 * {@link #complete(PlannedPath)} / {@link #fail(Throwable)} 交还骨架。
 *
 * <h3>线程无关的完成通知</h3>
 * {@code complete}/{@code fail} 可在<b>任意线程</b>调用——骨架检测当前线程:
 * 已在主线程直接推进;否则经 ServerScheduler 回主线程。载体也可自行切回主线程
 * 后再调(骨架检测到已在主线程则直接推进,零开销)——两种写法均合法,
 * 回主线程的兜底始终由骨架保证,载体漏写回程代码不会引发线程事故。
 */
public interface ComputeTask {

    /**
     * 本次计算所属的循环上下文(只读视图,读取当下值)。
     * <p>
     * 默认返回 {@code null}(无循环上下文,如连续导航的段重寻路任务);
     * {@link LoopBehavior} 派发的任务总会提供上下文。
     *
     * @return 上下文,或 null
     */
    default LoopContext context() {
        return null;
    }

    /**
     * 纯计算逻辑:解析目标 + 寻路,产出已计算态。
     * <p>
     * 由载体决定执行线程——实现<b>不得</b>操作实体写入或注册调度。
     * 返回 {@code null} 约定为"目标失效"(骨架以 TARGET_LOST 结束行为)。
     *
     * @return 已计算态;{@code null} 表示目标失效
     * @throws Exception 计算异常(经 {@link #fail} 交还骨架)
     */
    PlannedPath run() throws Exception;

    /**
     * 计算完成(线程无关:任意线程调用均可,骨架保证回主线程推进)。
     *
     * @param plan 计算产物;{@code null} 表示目标失效
     */
    void complete(PlannedPath plan);

    /**
     * 计算失败(线程无关,同 {@link #complete})。
     *
     * @param error 失败原因
     */
    void fail(Throwable error);
}
