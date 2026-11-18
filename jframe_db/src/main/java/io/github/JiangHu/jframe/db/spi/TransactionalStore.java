package io.github.JiangHu.jframe.db.spi;

import java.util.function.Function;

/**
 * 事务可选能力（适配器按需实现）。
 * <p>
 * 适配器支持则 {@code @ChildTable} / {@code @RelationTable} 的多行写入
 * （主行 upsert + 子表重写 + 关联表差集维护）会被整体包进事务；
 * <b>未实现</b>时这些多行写入无跨行原子性，存在半写风险（写入中途失败
 * 会留下部分数据），使用指南会显著提示优先实现本接口。
 * <p>
 * 实现示例（MyBatis）：在 {@code inTransaction} 内以
 * {@code SqlSession} 开启/提交/回滚事务，work 内的所有 SPI 调用
 * 走同一会话。
 *
 * @see RelationalStore
 */
public interface TransactionalStore {

    /**
     * 在事务中执行 work：work 正常返回则提交，抛异常则回滚并传播异常。
     *
     * @param work 事务体（入参为所属 store，便于复用 ensureTable 句柄）
     * @param <R>  返回类型
     * @return work 的返回值
     */
    <R> R inTransaction(Function<RelationalStore, R> work);
}
