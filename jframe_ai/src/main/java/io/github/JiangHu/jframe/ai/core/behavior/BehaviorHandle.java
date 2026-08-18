package io.github.JiangHu.jframe.ai.core.behavior;

/**
 * 运行中行为的句柄:{@code start()} 的返回值。
 * <p>
 * 持有句柄方可停止行为——行为本身不暴露更多控制面,
 * 参数调整走行为配置对象(如 {@code Target})的活引用。
 */
public interface BehaviorHandle {

    /**
     * 行为是否仍在运行。
     *
     * @return 运行中返回 {@code true};已以任何原因结束后为 {@code false}
     */
    boolean isRunning();

    /**
     * 停止行为(幂等)。
     * <p>
     * 已结束的行为再调无效果。停止触发 {@code onComplete(STOPPED)}。
     */
    void stop();
}
