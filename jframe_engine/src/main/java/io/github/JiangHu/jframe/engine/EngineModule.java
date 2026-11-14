package io.github.JiangHu.jframe.engine;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.engine.behavior.BtAPI;
import io.github.JiangHu.jframe.engine.ecs.EcsAPI;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsScheduler;
import io.github.JiangHu.jframe.engine.ecs.event.EcsEventBridge;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectLoader;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.DespawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.ResolverRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SelectorRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SpawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SystemEventRegistry;
import io.github.JiangHu.jframe.engine.ecs.world.NukkitWorldScanner;
import io.github.JiangHu.jframe.engine.ecs.world.NukkitWorldTracker;
import io.github.JiangHu.jframe.engine.pool.EcsPool;
import io.github.JiangHu.jframe.engine.pool.EcsPoolConfig;

import java.util.Set;

/**
 * 游戏引擎模块工厂（ECS + 行为树一体）— 一对一替代原
 * {@code EcsSpringConfig} + {@code ecs-spring.xml}。
 * <p>
 * 装配 DAG（与原 XML 等价，构造器参数顺序一致）：
 * 六张注册表 → 对象池（config → pool）→ GameObjectLoader(组件/选择器/生成器/回收器) →
 * EcsEngine(五注册表 + pool) → 世界扫描器/跟踪器 → EcsScheduler(engine, scanner) →
 * EcsEventBridge(engine, component, resolver, systemEvent) →
 * EcsAPI(engine, loader, bridge, scheduler, tracker, despawner) + BtAPI(engine)。
 * <p>
 * BtAPI 与 EcsAPI 共享 {@link EcsEngine} 内部 Bean，因此 ECS 与 BT 是一个模块、
 * 两个公开服务；getEcsAPI() / getBtAPI() 均触发本模块加载。
 * 关闭顺序 = 10（最先停止，卸载全部组件并取消调度）。
 */
public class EngineModule implements JFrameModule {

    private EcsAPI ecsAPI;

    @Override
    public String id() {
        return "engine";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(EcsAPI.class, BtAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        ComponentRegistry componentRegistry = new ComponentRegistry();
        SelectorRegistry selectorRegistry = new SelectorRegistry();
        SpawnerRegistry spawnerRegistry = new SpawnerRegistry();
        DespawnerRegistry despawnerRegistry = new DespawnerRegistry();
        ResolverRegistry resolverRegistry = new ResolverRegistry();
        SystemEventRegistry systemEventRegistry = new SystemEventRegistry();

        EcsPoolConfig poolConfig = new EcsPoolConfig();
        EcsPool ecsPool = new EcsPool(poolConfig);

        GameObjectLoader loader = new GameObjectLoader(
                componentRegistry, selectorRegistry, spawnerRegistry, despawnerRegistry);

        EcsEngine engine = new EcsEngine(componentRegistry, selectorRegistry,
                spawnerRegistry, resolverRegistry, systemEventRegistry, ecsPool);

        NukkitWorldScanner scanner = new NukkitWorldScanner(engine);
        NukkitWorldTracker tracker = new NukkitWorldTracker(engine);
        EcsScheduler scheduler = new EcsScheduler(engine, scanner);
        EcsEventBridge bridge = new EcsEventBridge(engine, componentRegistry,
                resolverRegistry, systemEventRegistry);

        ecsAPI = new EcsAPI(engine, loader, bridge, scheduler, tracker, despawnerRegistry);
        ctx.register(EcsAPI.class, ecsAPI);

        BtAPI btAPI = new BtAPI(engine);
        ctx.register(BtAPI.class, btAPI);
    }

    @Override
    public int shutdownOrder() {
        return 10;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (ecsAPI != null) {
            ecsAPI.stop();
        }
    }
}
