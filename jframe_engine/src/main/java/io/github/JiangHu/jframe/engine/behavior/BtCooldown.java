package io.github.JiangHu.jframe.engine.behavior;

import java.util.List;

/**
 * 冷却装饰器 — 子节点 SUCCESS 后进入冷却期（期间本节点直接 FAILURE，
 * 不求值子节点）；FAILURE/RUNNING 不触发冷却。
 *
 * <p>冷却计时存黑板（键 = "cooldown:" + 装饰器节点名）——树模板多宿主共享，
 * 节点字段禁止持有每宿主状态。时钟用 {@link BtContext#tick()}（世界 tick）。
 */
public final class BtCooldown extends BtNode {

    /** 冷却时长（tick） */
    private final long durationTicks;

    /** 被装饰子节点 */
    private final BtNode child;

    /** 黑板计时键（构造时按节点名定键） */
    private final String key;

    /**
     * 构造冷却装饰器。
     *
     * @param durationTicks 冷却时长（tick）
     * @param child         子节点
     */
    public BtCooldown(long durationTicks, BtNode child) {
        this.durationTicks = Math.max(1, durationTicks);
        this.child = child;
        this.key = "cooldown:" + child.name();
    }

    @Override
    public BtStatus tick(BtContext context) {
        long last = context.blackboard().getLong(key, Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && context.tick() - last < durationTicks) {
            context.record(name() + "(cooling)", BtStatus.FAILURE);
            return BtStatus.FAILURE; // 冷却期内：不求值子节点
        }
        BtStatus status = child.tick(context);
        context.record(child.name(), status);
        if (status == BtStatus.SUCCESS) {
            context.blackboard().put(key, context.tick()); // 成功才起表
        }
        return status;
    }

    @Override
    public List<BtNode> children() {
        return List.of(child);
    }
}
