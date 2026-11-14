package io.github.JiangHu.jframe.engine.behavior;

import java.util.List;

/**
 * 行为树节点基类 — 树模板是多宿主共享单例，节点<b>禁止持有每宿主状态</b>
 * （一切每宿主状态存 {@link BtBlackboard}）。
 *
 * <p>本框架采用 <b>reactive（无记忆）</b>求值：每周期从根重走，
 * 高优先级条件满足天然打断低优先级行为；跨周期持续性由行为叶的幂等
 * 语义承担（如导航动作幂等重提交），不保留节点级 Running 记忆。
 */
public abstract class BtNode {

    /** 节点名（诊断/日志用；默认类名） */
    private String name = getClass().getSimpleName();

    /**
     * 本周期求值。
     *
     * @param context 上下文（黑板/实体/tick/效果收集）
     * @return 执行状态
     */
    public abstract BtStatus tick(BtContext context);

    /**
     * 子节点（叶返回空列表）。
     *
     * @return 子节点列表
     */
    public List<BtNode> children() {
        return List.of();
    }

    /** 节点名 */
    public String name() {
        return name;
    }

    /**
     * 设置节点名（加载器按 YAML 键赋名）。
     *
     * @param name 名
     */
    void setName(String name) {
        this.name = name;
    }
}
