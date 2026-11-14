package io.github.JiangHu.jframe.engine.behavior;

/**
 * 条件叶节点 — 只读判断（黑板/组件/世界），无副作用。
 *
 * <p>词表实现 {@link #test(BtContext)} 返回布尔（true=SUCCESS / false=FAILURE）；
 * 参数经词表注册表反射注入公有字段（字段名=YAML params 键）。
 */
public abstract class BtCondition extends BtNode {

    @Override
    public final BtStatus tick(BtContext context) {
        return test(context) ? BtStatus.SUCCESS : BtStatus.FAILURE;
    }

    /**
     * 判定（只读；异常视为 FAILURE 不炸树）。
     *
     * @param context 上下文
     * @return true = 满足
     */
    public abstract boolean test(BtContext context);
}
