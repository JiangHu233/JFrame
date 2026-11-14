package io.github.JiangHu.jframe.engine.behavior;

import java.util.List;
import java.util.Map;

/**
 * 行为树定义 — 名称 + 根节点（树模板是多宿主<b>共享单例</b>：
 * 同一棵树实例可被任意多宿主 tick，每宿主状态全部在各自黑板）。
 */
public final class BtTree {

    /** 树名（YAML 顶层 key；AI 模板等按名引用） */
    private final String name;

    /** 根节点 */
    private final BtNode root;

    /**
     * 构造树定义。
     *
     * @param name 树名
     * @param root 根节点
     */
    public BtTree(String name, BtNode root) {
        this.name = name;
        this.root = root;
    }

    /** 树名 */
    public String name() {
        return name;
    }

    /** 根节点 */
    public BtNode root() {
        return root;
    }

    /**
     * 便捷求值：以给定黑板/实体/tick tick 根节点。
     *
     * @param context 上下文
     * @return 根节点状态
     */
    public BtStatus tick(BtContext context) {
        BtStatus status = root.tick(context);
        context.record(root.name(), status);
        return status;
    }

    /**
     * 诊断：根到叶的节点树形描述。
     *
     * @return 多行文本
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(name).append('\n');
        describe(root, 0, sb);
        return sb.toString();
    }

    private static void describe(BtNode node, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(node.name()).append('\n');
        List<BtNode> children = node.children();
        if (children.isEmpty()) {
            return;
        }
        for (BtNode child : children) {
            describe(child, depth + 1, sb);
        }
    }

    /**
     * 供加载器传递原始节的内部占位（保留 Map 透传能力）。
     *
     * @param any 任意
     * @return 任意
     */
    static Map<String, Object> unused(Map<String, Object> any) {
        return any;
    }
}
