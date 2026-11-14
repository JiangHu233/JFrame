package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectLoader;

/**
 * 测试夹具 — 装配一套最小可用的引擎组件（五张注册表 + loader + engine）。
 */
public class EcsTestHarness {

    /** 组件注册表 */
    public final ComponentRegistry componentRegistry = new ComponentRegistry();

    /** 选择器注册表 */
    public final SelectorRegistry selectorRegistry = new SelectorRegistry();

    /** 生成器注册表 */
    public final SpawnerRegistry spawnerRegistry = new SpawnerRegistry();

    /** 回收器注册表 */
    public final DespawnerRegistry despawnerRegistry = new DespawnerRegistry();

    /** 解析器注册表 */
    public final ResolverRegistry resolverRegistry = new ResolverRegistry();

    /** 系统事件注册表 */
    public final SystemEventRegistry systemEventRegistry = new SystemEventRegistry();

    /** 引擎 */
    public final EcsEngine engine;

    /** 定义加载器 */
    public final GameObjectLoader loader;

    /**
     * 构造夹具并预注册内置（与 EcsAPI 构造行为一致；默认六参引擎 = 默认池开启）。
     */
    public EcsTestHarness() {
        this(null);
    }

    /**
     * 构造夹具并注入自定义对象池（EcsPoolTest 用；null = 走六参默认池）。
     *
     * @param ecsPool 池实例（可 null）
     */
    public EcsTestHarness(io.github.JiangHu.jframe.engine.pool.EcsPool ecsPool) {
        engine = ecsPool == null
                ? new EcsEngine(componentRegistry, selectorRegistry, spawnerRegistry,
                        resolverRegistry, systemEventRegistry)
                : new EcsEngine(componentRegistry, selectorRegistry, spawnerRegistry,
                        resolverRegistry, systemEventRegistry, ecsPool);
        loader = new GameObjectLoader(componentRegistry, selectorRegistry, spawnerRegistry,
                despawnerRegistry);
        io.github.JiangHu.jframe.engine.ecs.selector.BuiltinSelectors.registerAll(selectorRegistry);
        io.github.JiangHu.jframe.engine.ecs.spawner.BuiltinSpawners.registerAll(spawnerRegistry);
        io.github.JiangHu.jframe.engine.ecs.resolver.BuiltinResolvers.registerAll(resolverRegistry);
    }
}
