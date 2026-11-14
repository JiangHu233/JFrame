package io.github.JiangHu.jframe.engine.behavior;

/**
 * 行为树节点执行状态（业界标准三值语义）。
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 本周期该节点职责完成</li>
 *   <li>{@link #FAILURE} — 本周期该节点无法履行职责（条件不满足/行为失败）</li>
 *   <li>{@link #RUNNING} — 职责履行中，跨周期持续（本框架 reactive 模式下用于
 *       告知父级"此枝在忙"，不保留跨周期节点记忆）</li>
 * </ul>
 */
public enum BtStatus {
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILURE,
    /** 履行中 */
    RUNNING
}
