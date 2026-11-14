package io.github.JiangHu.jframe.engine.behavior;

/**
 * 行为叶节点 — 产出效果（{@link BtContext#emit}），<b>禁止直接副作用</b>
 * （效果由宿主 apply 阶段统一执行，树 tick 因此可在异步线程安全运行）。
 *
 * <p>词表实现 {@link #execute(BtContext)} 返回状态；行为应幂等
 * （reactive 模式每周期重评，同行为连续周期重复产出效果是常态，
 * 宿主 apply 侧幂等消费）。
 */
public abstract class BtAction extends BtNode {

    @Override
    public final BtStatus tick(BtContext context) {
        try {
            return execute(context);
        } catch (Exception e) {
            // 异常隔离：单行为失败不炸整树（对齐 ECS 事件处理器异常隔离惯例）
            return BtStatus.FAILURE;
        }
    }

    /**
     * 执行（产出效果；异常视为 FAILURE）。
     *
     * @param context 上下文
     * @return 执行状态
     */
    public abstract BtStatus execute(BtContext context);
}
