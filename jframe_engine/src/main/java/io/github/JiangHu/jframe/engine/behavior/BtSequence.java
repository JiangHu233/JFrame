package io.github.JiangHu.jframe.engine.behavior;

import java.util.ArrayList;
import java.util.List;

/**
 * 顺序节点（Sequence）— 检查单：从左到右依次求值，全部 SUCCESS 才 SUCCESS，
 * 任一 FAILURE 短路为 FAILURE，遇 RUNNING 短路返回 RUNNING（右侧下周期再续）。
 */
public final class BtSequence extends BtNode {

    /** 子节点（顺序即步骤） */
    private final List<BtNode> nodes;

    /**
     * 构造顺序节点。
     *
     * @param nodes 子节点（至少一个）
     */
    public BtSequence(List<BtNode> nodes) {
        this.nodes = new ArrayList<>(nodes);
    }

    @Override
    public BtStatus tick(BtContext context) {
        for (BtNode node : nodes) {
            BtStatus status = node.tick(context);
            context.record(node.name(), status);
            if (status != BtStatus.SUCCESS) {
                return status;
            }
        }
        return BtStatus.SUCCESS;
    }

    @Override
    public List<BtNode> children() {
        return nodes;
    }
}
