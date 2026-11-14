package io.github.JiangHu.jframe.engine.behavior;

import java.util.ArrayList;
import java.util.List;

/**
 * 选择节点（Selector/Fallback）— 优先级裁决：从左到右依次求值子节点，
 * 第一个非 {@link BtStatus#FAILURE} 的子节点状态即本节点状态（左高右低）。
 *
 * <p>全部 FAILURE 才 FAILURE；遇 RUNNING 即短路返回 RUNNING
 * （本周期此枝在忙，不再尝试右侧——reactive 模式下右侧自然让位）。
 */
public final class BtSelector extends BtNode {

    /** 子节点（顺序即优先级） */
    private final List<BtNode> nodes;

    /**
     * 构造选择节点。
     *
     * @param nodes 子节点（至少一个）
     */
    public BtSelector(List<BtNode> nodes) {
        this.nodes = new ArrayList<>(nodes);
    }

    @Override
    public BtStatus tick(BtContext context) {
        for (BtNode node : nodes) {
            BtStatus status = node.tick(context);
            context.record(node.name(), status);
            if (status != BtStatus.FAILURE) {
                return status;
            }
        }
        return BtStatus.FAILURE;
    }

    @Override
    public List<BtNode> children() {
        return nodes;
    }
}
