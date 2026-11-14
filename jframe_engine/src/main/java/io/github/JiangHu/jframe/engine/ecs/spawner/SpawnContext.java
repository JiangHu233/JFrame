package io.github.JiangHu.jframe.engine.ecs.spawner;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;

/**
 * 生成上下文 — 传递给 {@link Spawner#spawn(SpawnContext)} 的现场生成参数。
 *
 * @param definitionName GameObject 定义名（调用 {@code ecs.spawn(name, pos)} 时传入）
 * @param position       生成位置（纯虚拟 gameobject 可为 null）
 * @param engine         引擎访问器（生成器可借其查询/挂载辅助实体）
 * @see DespawnContext
 */
public record SpawnContext(String definitionName, Position position, EcsEngine engine) {

    /**
     * 生成位置（便捷判空）。
     *
     * @return 位置；未提供时抛出 {@link IllegalStateException}
     */
    public Position requirePosition() {
        if (position == null) {
            throw new IllegalStateException("定义 '" + definitionName + "' 的生成器需要位置，但 spawn 未提供");
        }
        return position;
    }
}
