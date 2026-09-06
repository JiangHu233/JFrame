package io.github.JiangHu.jframe.ai.core.targeting;

import cn.nukkit.math.Vector3;

import java.util.function.Supplier;

/**
 * 目标插槽:无参获取目标位置,是执行器与行为骨架统一的目标抽象。
 * <p>
 * 所有导航 / 战斗 / 循环行为只认本接口,不关心目标背后是静态点、移动实体
 * 还是战术计算——<b>新战术 = 新 {@link Target} 实现,执行器零改动</b>。
 *
 * <h3>语义约定</h3>
 * <ul>
 *   <li><b>无参方法</b>:上下文(自身实体、威胁、参数)在构造时绑定,Target 完全自包含</li>
 *   <li><b>活引用</b>:Target 以引用传入调用链,{@code compute()} 与持续行为每轮重搜时
 *       才调 {@link #get()} 解析——中途修改 Target 参数,下次解析自动生效</li>
 *   <li><b>null = 无有效目标</b>:{@link #get()} 返回 {@code null} 表示当前无可去位置,
 *       由调用方决定跳过 / 停止 / 重试</li>
 *   <li><b>解析即快照</b>:实现应返回解析时刻的位置快照(新建 {@link Vector3}),
 *       不暴露内部可变引用</li>
 * </ul>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数)。
 *
 * <h3>内置实现</h3>
 * <ul>
 *   <li>{@link PointTarget} —— 静态点</li>
 *   <li>{@link EntityTarget} —— 实体当前位置(支持移动目标)</li>
 *   <li>{@code io.github.JiangHu.jframe.ai.core.tactical} 包下的战术 Target 适配器
 *       (CoverTarget / FleeTarget / FlankTarget / HighGroundTarget / SightTarget / ApproximateTarget)</li>
 * </ul>
 *
 * <pre>{@code
 * // lambda 可直接作为 Target(继承 Supplier)
 * Target home = () -> new Vector3(100, 64, 100);
 * }</pre>
 */
public interface Target extends Supplier<Vector3> {

    /**
     * 解析当前目标位置。
     *
     * @return 目标位置快照;{@code null} 表示当前无有效目标
     */
    @Override
    Vector3 get();
}
