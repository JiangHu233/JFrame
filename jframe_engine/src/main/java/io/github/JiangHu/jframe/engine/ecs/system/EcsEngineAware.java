package io.github.JiangHu.jframe.engine.ecs.system;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;

/**
 * 引擎绑定感知（可选接口）— 系统需要在<b>事件处理器内</b>使用引擎时实现。
 *
 * <p>{@link EcsEngine#addSystem(EcsSystem)} 注册系统时，若系统实现本接口，
 * 引擎会立即回调 {@link #bindEngine(EcsEngine)} 注入自身引用；系统可保存该引用，
 * 供 {@code @OnEcsEvent} 处理器内 {@code query()} 等全局操作使用
 * （tick 路径本就通过 {@link EcsSystem#tick(EcsEngine)} 收到引擎，无需本接口）。
 *
 * <pre>{@code
 * @EcsSystemAnno
 * public class JoinEchoSystem implements EcsSystem, EcsEngineAware {
 *     private EcsEngine engine;
 *
 *     @Override
 *     public void bindEngine(EcsEngine engine) { this.engine = engine; }
 *
 *     @OnEcsEvent   // 系统事件：全局分发，无需解析器
 *     public void onJoin(PlayerJoinEvent event) {
 *         int chests = engine.query(MagicChestComponent.class).size();
 *     }
 * }
 * }</pre>
 */
public interface EcsEngineAware {

    /**
     * 绑定引擎（引擎在 addSystem 时回调一次）。
     *
     * @param engine 引擎实例
     */
    void bindEngine(EcsEngine engine);
}
