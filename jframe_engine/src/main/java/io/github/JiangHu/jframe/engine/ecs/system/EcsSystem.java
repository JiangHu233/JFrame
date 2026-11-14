package io.github.JiangHu.jframe.engine.ecs.system;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;

/**
 * ECS 系统 — 每 N tick 在主线程执行的逻辑单元，或纯事件驱动的全局处理器。
 *
 * <h3>tick 可选（按需使用）</h3>
 * <ul>
 *   <li>需要每帧逻辑 → override {@link #tick(EcsEngine)}（通常配合 {@link EcsEngine#query} 遍历目标实体）</li>
 *   <li><b>纯事件驱动系统 → 无需 override</b>：只写 {@code @OnEcsEvent} 处理器即可；
 *       引擎注册期检测未 override tick 的系统，<b>不进入 tick 执行路径</b>（零轮询，
 *       而非"调用空方法"），{@code interval()}/{@code order()} 对其无意义</li>
 * </ul>
 * 单个系统 tick 抛出异常只记录日志，不影响其余系统。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 周期系统
 * @EcsSystemAnno
 * public class MagicChestSystem implements EcsSystem {
 *     @Override public int interval() { return 20; }
 *
 *     @Override
 *     public void tick(EcsEngine engine) {
 *         for (EcsEntity chest : engine.query(MagicAlarm.class)) { /* 吸附掉落物 *\/ }
 *     }
 * }
 *
 * // 纯事件系统（无 tick，零轮询；engine 经 EcsEngineAware 注入）
 * @EcsSystemAnno
 * public class JoinEchoSystem implements EcsSystem, EcsEngineAware {
 *     private EcsEngine engine;
 *     @Override public void bindEngine(EcsEngine engine) { this.engine = engine; }
 *
 *     @OnEcsEvent
 *     public void onJoin(PlayerJoinEvent event) { ... }
 * }
 * }</pre>
 *
 * @see io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno
 * @see EcsEngineAware
 */
public interface EcsSystem {

    /**
     * 执行一帧逻辑（可选——不 override 即纯事件系统，零轮询）。
     *
     * @param engine 引擎访问器（query / 实体访问等）
     */
    default void tick(EcsEngine engine) {
    }

    /**
     * 执行间隔（tick 数，仅对 override 了 tick 的系统有意义）。
     *
     * @return 间隔，&lt;1 视为 1
     */
    default int interval() {
        return 1;
    }

    /**
     * 执行顺序（int，小者先，仅对 override 了 tick 的系统有意义）。
     *
     * <p>与 {@code @OnEcsEvent#order()}（事件处理器顺序）词汇统一。
     *
     * @return 顺序
     */
    default int order() {
        return 0;
    }
}
