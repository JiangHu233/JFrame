package io.github.JiangHu.jframe.engine.behavior;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 行为树上下文 — 一次 tick 的全部输入输出载体（纯函数适配异步决策的关键）。
 *
 * <ul>
 *   <li>黑板 {@link #blackboard()} — 每宿主工作记忆</li>
 *   <li>宿主实体 {@link #entity()} — ECS 集成点（条件词表直读组件）</li>
 *   <li>世界 tick {@link #tick()} — 冷却/计时装饰器的时钟</li>
 *   <li>效果收集 {@link #emit(Object)} — 行为叶输出副作用对象列表，
 *       <b>行为叶不做任何直接副作用</b>：效果由宿主在 apply 阶段统一执行
 *       （FapEFT 的效果即 AiAction，由主线程 apply 段消费）——
 *       树 tick 因此可在异步线程安全运行</li>
 *   <li>调试路径 {@link #trace} — 非 null 时记录经过的节点名（诊断开关）</li>
 * </ul>
 */
public final class BtContext {

    /** 每宿主黑板 */
    private final BtBlackboard blackboard;

    /** 宿主实体（可为 null——无实体纯虚拟决策） */
    private final EcsEntity entity;

    /** 当前世界 tick（计时装饰器时钟） */
    private final long tick;

    /** 行为叶产出的效果列表（宿主 apply 阶段执行） */
    private final List<Object> effects = new ArrayList<>();

    /** 调试路径收集（null = 关闭；每元素形如 "节点名:STATUS"） */
    public final List<String> trace;

    /**
     * 构造上下文。
     *
     * @param blackboard 黑板
     * @param entity     宿主实体（可 null）
     * @param tick       当前世界 tick
     * @param tracing    开启调试路径收集
     */
    public BtContext(BtBlackboard blackboard, EcsEntity entity, long tick, boolean tracing) {
        this.blackboard = blackboard;
        this.entity = entity;
        this.tick = tick;
        this.trace = tracing ? new ArrayList<>() : null;
    }

    /** 每宿主黑板 */
    public BtBlackboard blackboard() {
        return blackboard;
    }

    /** 宿主实体（条件词表经此直读组件；可为 null） */
    public EcsEntity entity() {
        return entity;
    }

    /** 当前世界 tick */
    public long tick() {
        return tick;
    }

    /**
     * 行为叶输出副作用效果（apply 阶段由宿主执行）。
     *
     * @param effect 效果对象（宿主约定类型，如 AiAction）
     */
    public void emit(Object effect) {
        if (effect != null) {
            effects.add(effect);
        }
    }

    /**
     * 取走本周期收集的全部效果。
     *
     * @return 效果列表（顺序即产出顺序）
     */
    public List<Object> takeEffects() {
        return effects;
    }

    /**
     * 调试路径追加（tracing 开启时）。
     *
     * @param node   节点名
     * @param status 状态
     */
    void record(String node, BtStatus status) {
        if (trace != null) {
            trace.add(node + ":" + status);
        }
    }

    /**
     * 黑板遍历（诊断用）。
     *
     * @param consumer 键值消费器
     */
    public void forEachBlackboard(java.util.function.BiConsumer<String, Object> consumer) {
        blackboard.entries().forEach(entry -> consumer.accept(entry.getKey(), entry.getValue()));
    }
}
