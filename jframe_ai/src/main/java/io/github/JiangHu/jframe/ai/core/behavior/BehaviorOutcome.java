package io.github.JiangHu.jframe.ai.core.behavior;

/**
 * 行为完成原因:单次导航({@code PlannedPath})与循环行为({@code LoopBehavior})共用的结局语义。
 * <p>
 * 完成回调({@code onComplete})以此枚举告知调用方行为<b>为何</b>结束,
 * 作为状态机转移的判据。
 *
 * <table>
 *   <tr><th>值</th><th>含义</th><th>典型触发</th></tr>
 *   <tr><td>{@link #ARRIVED}</td><td>到达目标</td><td>单次走完 / 循环 {@code until} 达成 / 连续模式进入到达半径</td></tr>
 *   <tr><td>{@link #TARGET_LOST}</td><td>目标失效</td><td>{@code Target.get()} 返回 {@code null}</td></tr>
 *   <tr><td>{@link #PATH_FAILED}</td><td>寻路失败</td><td>无可用路径(partial 也不可用)/ 连续模式段数超限</td></tr>
 *   <tr><td>{@link #STOPPED}</td><td>外部停止</td><td>{@code stop()} 或被同实体新行为取代</td></tr>
 * </table>
 */
public enum BehaviorOutcome {

    /** 到达目标(单次走完 / 循环 {@code until} 达成) */
    ARRIVED,

    /** 目标失效({@code Target.get()} 返回 null) */
    TARGET_LOST,

    /** 寻路失败(partial 也不可用) */
    PATH_FAILED,

    /** 外部 {@code stop()} 或被同实体新行为取代 */
    STOPPED
}
