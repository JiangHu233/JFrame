package io.github.JiangHu.jframe.ai.core.behavior;

import cn.nukkit.entity.Entity;
import io.github.JiangHu.jframe.ai.core.executor.PlannedPath;
import io.github.JiangHu.jframe.ai.core.navigation.Navigator;
import io.github.JiangHu.jframe.ai.core.targeting.Target;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;

/**
 * 循环行为的只读上下文:每轮计算与 {@code until} 判断的输入视图。
 * <p>
 * 由 {@link LoopBehavior} 骨架在主线程构造;{@code compute}/{@code execute}/
 * {@code until} 回调均以此接收行为状态。除查询外不提供任何变更入口。
 */
public interface LoopContext {

    /**
     * 行为主体。
     *
     * @return 行为所属实体
     */
    Entity self();

    /**
     * 行为已运行的游戏 tick 数(自 {@code start()} 起)。
     *
     * @return tick 计数
     */
    long ticks();

    /**
     * 已完成的循环轮数(首轮计算期间为 0)。
     *
     * @return 轮数
     */
    int rounds();

    /**
     * 上一轮的寻路结果。
     *
     * @return 上轮结果;首轮为 {@code null}
     */
    PathResult lastResult();

    /**
     * 上一轮的执行计划。
     *
     * @return 上轮计划;首轮为 {@code null}
     */
    PlannedPath lastPlan();

    /**
     * 当前正在推进本实体移动的导航器(若有)。
     *
     * @return 当前导航器;无活跃导航时为 {@code null}
     */
    Navigator navigator();

    /**
     * 行为的目标引用(活引用,可中途换目标)。
     *
     * @return 目标;未设置目标的纯自定义循环为 {@code null}
     */
    Target target();
}
