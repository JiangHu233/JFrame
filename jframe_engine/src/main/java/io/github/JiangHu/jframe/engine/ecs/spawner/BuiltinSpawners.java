package io.github.JiangHu.jframe.engine.ecs.spawner;

import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner;

/**
 * 内置生成器集合 — 模块装配时预注册。
 *
 * <ul>
 *   <li>{@code virtual} — 纯虚拟实体（无世界对象）</li>
 *   <li>{@code entity} — 按 network id 或类型名生成 Nukkit 实体</li>
 *   <li>{@code block} — 放置方块产生方块实体（如箱子）</li>
 * </ul>
 * 三个内置名占用保留字，自定义同名注册会被拒绝。
 *
 * @see VirtualSpawner
 * @see NukkitEntitySpawner
 * @see BlockSpawner
 */
public final class BuiltinSpawners {

    private BuiltinSpawners() {
    }

    /**
     * 向注册表预注册全部内置生成器。
     *
     * @param registry 生成器注册表
     */
    public static void registerAll(io.github.JiangHu.jframe.engine.ecs.registry.SpawnerRegistry registry) {
        registry.register(VirtualSpawner.NAME, VirtualSpawner.class);
        registry.register(NukkitEntitySpawner.NAME, NukkitEntitySpawner.class);
        registry.register(BlockSpawner.NAME, BlockSpawner.class);
    }
}

/**
 * 内置虚拟生成器 — 返回 null，创建无世界对象的纯虚拟实体。
 *
 * <pre>{@code
 * spawn: { use: virtual }
 * }</pre>
 */
@EcsSpawner(VirtualSpawner.NAME)
class VirtualSpawner implements Spawner {

    /** 保留注册名 */
    static final String NAME = "virtual";

    @Override
    public Object spawn(SpawnContext context) {
        return null;
    }
}

/**
 * 内置实体生成器 — 按 network id 或类型名生成 Nukkit 实体。
 *
 * <pre>{@code
 * spawn: { use: entity, params: { networkId: 32 } }
 * spawn: { use: entity, params: { type: Zombie, nameTag: "守卫" } }
 * }</pre>
 */
@EcsSpawner(NukkitEntitySpawner.NAME)
class NukkitEntitySpawner implements Spawner {

    /** 保留注册名 */
    static final String NAME = "entity";

    /** Nukkit 实体网络 id（与 type 二选一；params.networkId 注入） */
    private int networkId;

    /** Nukkit 实体类型名（与 networkId 二选一；params.type 注入） */
    private String type;

    /** 生成后设置的名字标签（可选；params.nameTag 注入） */
    private String nameTag;

    @Override
    public Object spawn(SpawnContext context) {
        Position position = context.requirePosition();
        Entity entity;
        if (type != null && !type.isBlank()) {
            entity = Entity.createEntity(type.trim(), position);
        } else if (networkId > 0) {
            entity = Entity.createEntity(networkId, position);
        } else {
            throw new EcsConfigException("entity 生成器需要 networkId 或 type 参数（定义 '" + context.definitionName() + "'）");
        }
        if (entity == null) {
            throw new EcsConfigException("entity 生成器无法创建实体（定义 '" + context.definitionName() + "'）");
        }
        if (nameTag != null && !nameTag.isBlank()) {
            entity.setNameTag(nameTag);
        }
        entity.spawnToAll();
        return entity;
    }
}

/**
 * 内置方块生成器 — 放置方块产生方块实体（如箱子）。
 *
 * <pre>{@code
 * spawn: { use: block, params: { blockId: 54 } }   # 54 = chest
 * }</pre>
 *
 * <p>放置成功后返回该位置的方块实体（若有）；非方块实体方块返回 null（退化为纯虚拟绑定）。
 */
@EcsSpawner(BlockSpawner.NAME)
class BlockSpawner implements Spawner {

    /** 保留注册名 */
    static final String NAME = "block";

    /** 方块 id（params.blockId 注入） */
    private int blockId;

    /** 方块 meta（可选；params.meta 注入） */
    private int meta;

    @Override
    public Object spawn(SpawnContext context) {
        Position position = context.requirePosition();
        if (blockId <= 0) {
            throw new EcsConfigException("block 生成器需要 blockId 参数（定义 '" + context.definitionName() + "'）");
        }
        Block block = Block.get(blockId, meta, position);
        if (position.getLevel() == null) {
            throw new EcsConfigException("block 生成器位置缺少 level（定义 '" + context.definitionName() + "'）");
        }
        position.getLevel().setBlock(position, block, true, true);
        return position.getLevel().getBlockEntity(position);
    }
}
