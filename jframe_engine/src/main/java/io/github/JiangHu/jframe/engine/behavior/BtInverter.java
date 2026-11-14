package io.github.JiangHu.jframe.engine.behavior;

import java.util.List;

/**
 * 取反装饰器 — 子节点 SUCCESS→FAILURE、FAILURE→SUCCESS（RUNNING 透传）。
 */
public final class BtInverter extends BtNode {

    /** 被装饰子节点 */
    private final BtNode child;

    /**
     * 构造取反装饰器。
     *
     * @param child 子节点
     */
    public BtInverter(BtNode child) {
        this.child = child;
    }

    @Override
    public BtStatus tick(BtContext context) {
        BtStatus status = child.tick(context);
        context.record(child.name(), status);
        return switch (status) {
            case SUCCESS -> BtStatus.FAILURE;
            case FAILURE -> BtStatus.SUCCESS;
            case RUNNING -> BtStatus.RUNNING;
        };
    }

    @Override
    public List<BtNode> children() {
        return List.of(child);
    }
}
