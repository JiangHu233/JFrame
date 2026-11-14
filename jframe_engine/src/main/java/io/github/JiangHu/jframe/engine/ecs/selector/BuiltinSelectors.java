package io.github.JiangHu.jframe.engine.ecs.selector;

import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.registry.ParamBinder;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSelector;

/**
 * 内置选择器集合 — 模块装配时预注册，常用场景零自定义类。
 *
 * <ul>
 *   <li>{@code tag} — 按标签匹配（{@code params: { tag: chest }}）</li>
 *   <li>{@code entity-class} — 按 Nukkit 实体类匹配（{@code params: { type: Zombie }}，
 *       支持完整类名或 {@code cn.nukkit.entity.*} 下的简单名）</li>
 *   <li>{@code blockentity-class} — 按方块实体类匹配（{@code params: { type: BlockEntityChest }}，
 *       支持完整类名或 {@code cn.nukkit.blockentity.*} 下的简单名）——"指定所有的箱子"开箱即用</li>
 * </ul>
 * 三个内置名占用保留字，自定义同名注册会被拒绝。
 *
 * @see EntityClassSelector
 * @see BlockEntityClassSelector
 * @see TagSelector
 */
public final class BuiltinSelectors {

    private BuiltinSelectors() {
    }

    /**
     * 向注册表预注册全部内置选择器。
     *
     * @param registry 选择器注册表
     */
    public static void registerAll(io.github.JiangHu.jframe.engine.ecs.registry.SelectorRegistry registry) {
        registry.register(TagSelector.NAME, TagSelector.class);
        registry.register(EntityClassSelector.NAME, EntityClassSelector.class);
        registry.register(BlockEntityClassSelector.NAME, BlockEntityClassSelector.class);
    }
}

/**
 * 内置标签选择器 — 按标签匹配实体。
 *
 * <pre>{@code
 * selector: { use: tag, params: { tag: chest } }
 * }</pre>
 */
@EcsSelector(TagSelector.NAME)
class TagSelector implements EntitySelector {

    /** 保留注册名 */
    static final String NAME = "tag";

    /** 匹配的标签名（params.tag 注入） */
    private String tag;

    @Override
    public boolean matches(EcsEntity entity) {
        return tag != null && entity.hasTag(tag);
    }
}

/**
 * 内置实体类选择器 — 按 Nukkit 实体类（isInstance）匹配。
 *
 * <pre>{@code
 * selector: { use: entity-class, params: { type: Zombie } }        # 简单名
 * selector: { use: entity-class, params: { type: cn.nukkit.entity.EntityZombie } }  # 完整名
 * }</pre>
 */
@EcsSelector(EntityClassSelector.NAME)
class EntityClassSelector implements EntitySelector {

    /** 保留注册名 */
    static final String NAME = "entity-class";

    /** 匹配的实体类名（params.type 注入；简单名在 cn.nukkit.entity.* 下解析） */
    private String type;

    /** 已解析的类缓存 */
    private Class<?> resolved;

    @Override
    public boolean matches(EcsEntity entity) {
        return entity.binding() instanceof cn.nukkit.entity.Entity nukkit
                && resolve().isInstance(nukkit);
    }

    private Class<?> resolve() {
        if (resolved == null) {
            try {
                resolved = ParamBinder.resolveClass(type);
            } catch (EcsConfigException e) {
                throw new EcsConfigException("entity-class 选择器参数非法: " + e.getMessage(), e);
            }
        }
        return resolved;
    }
}

/**
 * 内置方块实体类选择器 — 按方块实体类（isInstance）匹配，覆盖"指定所有的箱子"场景。
 *
 * <pre>{@code
 * selector: { use: blockentity-class, params: { type: BlockEntityChest } }
 * }</pre>
 */
@EcsSelector(BlockEntityClassSelector.NAME)
class BlockEntityClassSelector implements EntitySelector {

    /** 保留注册名 */
    static final String NAME = "blockentity-class";

    /** 匹配的方块实体类名（params.type 注入；简单名在 cn.nukkit.blockentity.* 下解析） */
    private String type;

    /** 已解析的类缓存 */
    private Class<?> resolved;

    @Override
    public boolean matches(EcsEntity entity) {
        return entity.binding() instanceof cn.nukkit.blockentity.BlockEntity blockEntity
                && resolve().isInstance(blockEntity);
    }

    private Class<?> resolve() {
        if (resolved == null) {
            try {
                resolved = ParamBinder.resolveClass(type);
            } catch (EcsConfigException e) {
                throw new EcsConfigException("blockentity-class 选择器参数非法: " + e.getMessage(), e);
            }
        }
        return resolved;
    }
}
