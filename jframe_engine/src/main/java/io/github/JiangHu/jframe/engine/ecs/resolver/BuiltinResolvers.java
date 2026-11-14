package io.github.JiangHu.jframe.engine.ecs.resolver;

import cn.nukkit.event.Event;
import cn.nukkit.event.entity.EntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsResolver;

import java.util.List;

/**
 * 内置宿主解析器集合 — 模块装配时预注册。
 *
 * <ul>
 *   <li>{@code entity-events} — {@code EntityEvent.getEntity()}（声明父类型，宽匹配全部实体事件）</li>
 *   <li>{@code block-interact} — 点击方块 → 位置 → 方块实体（覆盖箱子点击场景）</li>
 * </ul>
 */
public final class BuiltinResolvers {

    private BuiltinResolvers() {
    }

    /**
     * 向注册表预注册全部内置解析器。
     *
     * @param registry 解析器注册表
     */
    public static void registerAll(io.github.JiangHu.jframe.engine.ecs.registry.ResolverRegistry registry) {
        registry.register(EntityEventResolver.NAME, EntityEventResolver.class);
        registry.register(BlockInteractResolver.NAME, BlockInteractResolver.class);
    }
}

/**
 * 内置实体事件解析器 — 从 {@code EntityEvent} 家族提取当事实体。
 *
 * <p>声明事件类型为 {@code EntityEvent}（父类型），引擎按宽匹配适用于
 * {@code EntityDamageEvent}、{@code EntityDeathEvent} 等全部子类事件。
 */
@EcsResolver(EntityEventResolver.NAME)
class EntityEventResolver implements EcsEventResolver {

    /** 保留注册名 */
    static final String NAME = "entity-events";

    @Override
    public Class<? extends Event> eventType() {
        return EntityEvent.class;
    }

    @Override
    public List<Object> extractHosts(Event event) {
        if (event instanceof EntityEvent entityEvent && entityEvent.getEntity() != null) {
            return List.of(entityEvent.getEntity());
        }
        return List.of();
    }
}

/**
 * 内置方块交互解析器 — 玩家点击方块 → 位置 → 方块实体（如点击箱子）。
 */
@EcsResolver(BlockInteractResolver.NAME)
class BlockInteractResolver implements EcsEventResolver {

    /** 保留注册名 */
    static final String NAME = "block-interact";

    @Override
    public Class<? extends Event> eventType() {
        return PlayerInteractEvent.class;
    }

    @Override
    public List<Object> extractHosts(Event event) {
        if (event instanceof PlayerInteractEvent interactEvent) {
            cn.nukkit.blockentity.BlockEntity blockEntity = interactEvent.getBlock().getLevelBlockEntity();
            if (blockEntity != null) {
                return List.of(blockEntity);
            }
        }
        return List.of();
    }
}
