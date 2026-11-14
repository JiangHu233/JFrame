package io.github.JiangHu.jframe.engine.ecs.despawner;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;

/**
 * 回收上下文 — 传递给 {@link Despawner#despawn(DespawnContext)} 的参数（产物视角，不含实体）。
 *
 * @param definitionName GameObject 定义名（spawn/spawnBound 出生时记录）
 * @param product        正被回收的产物（= 实体代理引用 / 原始 spawn 返回值；可为 null = 虚拟产物）
 * @param engine         引擎访问器（回收器可借其查询/清理辅助实体）
 */
public record DespawnContext(String definitionName, Object product, EcsEngine engine) {
}
