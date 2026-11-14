package io.github.JiangHu.jframe.engine.ecs;

import cn.nukkit.level.Position;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.module.ForPlugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsScheduler;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.event.EcsEventBridge;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectLoader;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.DespawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.ResolverRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SelectorRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SpawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.resolver.BuiltinResolvers;
import io.github.JiangHu.jframe.engine.ecs.resolver.EcsEventResolver;
import io.github.JiangHu.jframe.engine.ecs.selector.BuiltinSelectors;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.spawner.BuiltinSpawners;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.ecs.world.NukkitWorldTracker;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * ECS API（<b>面向用户的统一入口</b>）：注册（组件/选择器/生成器/回收器/解析器/系统）、
 * 定义加载（文件/字符串/classpath）、启动/停止、spawn/spawnBound、refresh、query。
 *
 * <h3>典型启动流程</h3>
 * <pre>{@code
 * EcsAPI ecs = JFrameMain.getInstance().getEcsAPI();
 * ecs.forPlugin(this).scan("com.mygame.ecs");                    // 扫描六类注解
 * ecs.forPlugin(this).loadGameObjects("gameobjects");            // 扫描 jar resources/gameobjects/
 * ecs.start();                                                   // 存量回溯 + diff 监听 + 事件桥 + tick
 * EcsEntity mgr = ecs.spawn("wave-manager", null);               // 现场生成
 * }</pre>
 *
 * <h3>内置注册（构造时预占）</h3>
 * <ul>
 *   <li>选择器：{@code tag} / {@code entity-class} / {@code blockentity-class}</li>
 *   <li>生成器：{@code virtual} / {@code entity} / {@code block}（回收器无内置——回收逻辑天然用户自定义）</li>
 *   <li>解析器：{@code entity-events} / {@code block-interact}</li>
 * </ul>
 */
public class EcsAPI implements PluginAware, ForPlugin<EcsPluginScope> {

    /** 引擎核心 */
    private final EcsEngine engine;

    /** 定义加载器 */
    private final GameObjectLoader loader;

    /** 事件桥 */
    private final EcsEventBridge bridge;

    /** 调度器 */
    private final EcsScheduler scheduler;

    /** 世界跟踪器（事件驱动） */
    private final NukkitWorldTracker tracker;

    /** 回收器注册表（registerDespawner 用） */
    private final DespawnerRegistry despawnerRegistry;

    /** 插件实例（Nukkit 调度/事件注册用） */
    private Plugin plugin;

    /** 启动标志 */
    private boolean started;

    /**
     * 构造 API 并预注册全部内置选择器/生成器/解析器。
     *
     * @param engine             引擎
     * @param loader             定义加载器
     * @param bridge             事件桥
     * @param scheduler          调度器
     * @param tracker            世界跟踪器
     * @param despawnerRegistry  回收器注册表
     */
    public EcsAPI(EcsEngine engine, GameObjectLoader loader,
                  EcsEventBridge bridge, EcsScheduler scheduler,
                  NukkitWorldTracker tracker, DespawnerRegistry despawnerRegistry) {
        this.engine = engine;
        this.loader = loader;
        this.bridge = bridge;
        this.scheduler = scheduler;
        this.tracker = tracker;
        this.despawnerRegistry = despawnerRegistry;
        BuiltinSelectors.registerAll(engine.selectorRegistry());
        BuiltinSpawners.registerAll(engine.spawnerRegistry());
        BuiltinResolvers.registerAll(engine.resolverRegistry());
    }

    // ==================== PluginAware ====================

    /**
     * 绑定插件实例（JFrameMain 在模块导入/插件启用时自动调用）。
     *
     * @param plugin 插件实例
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return;
        }
        this.plugin = plugin;
        bridge.bindPlugin(plugin);
        tracker.bindPlugin(plugin);
        bridge.syncRegistrations();
    }

    // ==================== 注册 ====================

    /**
     * 手动注册组件类。
     *
     * @param id   组件 id
     * @param type 组件类
     */
    public void registerComponent(String id, Class<? extends Component> type) {
        engine.componentRegistry().register(id, type);
        bridge.syncRegistrations();
    }

    /**
     * 手动注册组件类并显式指定池化属性（等价 {@code @EcsComponent(pooled=true)}）。
     *
     * @param id     组件 id
     * @param type   组件类
     * @param pooled 是否参与实例池化（重置契约见 {@link Component#reset()}）
     */
    public void registerComponent(String id, Class<? extends Component> type, boolean pooled) {
        engine.componentRegistry().register(id, type, pooled);
        bridge.syncRegistrations();
    }

    /**
     * 手动注册选择器类。
     *
     * @param name 注册名
     * @param type 选择器类
     */
    public void registerSelector(String name, Class<? extends EntitySelector> type) {
        engine.selectorRegistry().register(name, type);
    }

    /**
     * 手动注册生成器类。
     *
     * @param name 注册名
     * @param type 生成器类
     */
    public void registerSpawner(String name, Class<? extends Spawner> type) {
        engine.spawnerRegistry().register(name, type);
    }

    /**
     * 手动注册回收器类。
     *
     * @param name 注册名
     * @param type 回收器类
     */
    public void registerDespawner(String name, Class<? extends Despawner> type) {
        despawnerRegistry.register(name, type);
    }

    /**
     * 手动注册宿主解析器类。
     *
     * @param name 注册名
     * @param type 解析器类
     */
    public void registerResolver(String name, Class<? extends EcsEventResolver> type) {
        engine.resolverRegistry().register(name, type);
        bridge.syncRegistrations();
    }

    /**
     * 注册系统实例。
     * <p>注册时扫描系统类上的 {@code @OnEcsEvent} 方法建立<b>全局事件处理器</b>，
     * 并同步事件桥按需补注册监听。
     *
     * @param system 系统实例
     */
    public void registerSystem(EcsSystem system) {
        engine.addSystem(system);
        bridge.syncRegistrations();
    }

    /**
     * 引擎引用 — 插件侧程序化挂载/卸载组件、按绑定解析实体等高级操作的统一入口
     * （定义挂载之外的补充路径：状态组件随生命周期挂卸的玩法场景）。
     *
     * @return 引擎
     */
    public EcsEngine engine() {
        return engine;
    }

    /**
     * 注册系统（类） — 反射实例化（无参构造），与扫描路径注册等价。
     *
     * @param type 系统类
     */
    public void registerSystem(Class<? extends EcsSystem> type) {
        try {
            engine.addSystem(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new EcsConfigException("系统实例化失败: " + type.getName(), e);
        }
        bridge.syncRegistrations();
    }

    // ==================== 定义加载 ====================

    /**
     * 从文件或目录加载 GameObject 定义（目录递归 {@code *.yml}；同名定义冲突即报错）。
     *
     * @param file 文件或目录
     * @return 加载的定义列表
     */
    public List<GameObjectDefinition> loadGameObjects(File file) {
        return loadAll(loader.load(file));
    }

    /**
     * 从文件或目录<b>热重载</b> GameObject 定义（配置驱动工作流）：
     * 新名字装载并回溯挂载；同名覆盖并差量重挂（见
     * {@link EcsEngine#reloadDefinition}——旧 spec 组件卸载、spawn 出生实体挂新参数、
     * selector 重评估）。定义文件删除不在热重载范围。
     *
     * @param file 文件或目录
     * @return 重载的定义列表
     */
    public List<GameObjectDefinition> reloadGameObjects(File file) {
        List<GameObjectDefinition> definitions = loader.load(file);
        for (GameObjectDefinition definition : definitions) {
            engine.reloadDefinition(definition);
        }
        bridge.syncRegistrations();
        return definitions;
    }

    /**
     * 从 YAML 源码<b>热重载</b> GameObject 定义（语义同 {@link #reloadGameObjects(File)}）。
     *
     * @param yaml        YAML 源码
     * @param sourceDesc  来源描述（异常消息用）
     * @return 重载的定义列表
     */
    public List<GameObjectDefinition> reloadGameObjects(String yaml, String sourceDesc) {
        List<GameObjectDefinition> definitions = loader.load(yaml, sourceDesc);
        for (GameObjectDefinition definition : definitions) {
            engine.reloadDefinition(definition);
        }
        bridge.syncRegistrations();
        return definitions;
    }

    /**
     * 从 YAML 源码加载 GameObject 定义。
     *
     * @param yaml        YAML 源码
     * @param sourceDesc  来源描述（异常消息用）
     * @return 加载的定义列表
     */
    public List<GameObjectDefinition> loadGameObjects(String yaml, String sourceDesc) {
        return loadAll(loader.load(yaml, sourceDesc));
    }

    /**
     * 从 classpath 前缀加载（插件作用域调用，共用本 API 的 loader）。
     *
     * @param prefix      classpath 前缀
     * @param classLoader 插件类加载器
     * @return 加载的定义列表
     */
    List<GameObjectDefinition> loadGameObjectsFromClasspath(String prefix, ClassLoader classLoader) {
        return loadAll(loader.loadClasspath(prefix, classLoader));
    }

    /**
     * 批量装载定义并回溯挂载。
     */
    private List<GameObjectDefinition> loadAll(List<GameObjectDefinition> definitions) {
        for (GameObjectDefinition definition : definitions) {
            engine.loadDefinition(definition);
        }
        bridge.syncRegistrations();
        return definitions;
    }

    // ==================== 启动 / 停止 ====================

    /**
     * 启动引擎：存量快照（实体+方块实体 enter 管线）→ 事件桥激活 → 主循环任务。
     *
     * @throws IllegalStateException 插件未绑定或已启动
     */
    public synchronized void start() {
        if (plugin == null) {
            throw new IllegalStateException("ECS 插件尚未绑定（bindPlugin 由 JFrameMain 自动调用）");
        }
        if (started) {
            throw new IllegalStateException("ECS 引擎已启动");
        }
        scheduler.initialScan();
        bridge.activate();
        tracker.activate();
        scheduler.start(plugin);
        started = true;
        EcsLog.info("ECS 引擎已启动（定义 " + engine.definitions().size() + " 条，实体 "
                + engine.entities().size() + " 个）");
    }

    /**
     * 停止引擎：取消任务 → 事件桥/世界跟踪停用 → 全部实体逆序卸载（onUnmount）。
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        scheduler.stop();
        bridge.deactivate();
        tracker.deactivate();
        engine.removeAll();
        started = false;
        EcsLog.info("ECS 引擎已停止");
    }

    /**
     * 是否已启动。
     *
     * @return 启动 true
     */
    public synchronized boolean isStarted() {
        return started;
    }

    // ==================== 运行时操作 ====================

    /**
     * 现场生成实体（产物由定义的 spawner 生产，任意对象皆可为代理引用）。
     *
     * @param definitionName 定义名
     * @param position       生成位置（纯虚拟定义可 null）
     * @return 新实体
     */
    public EcsEntity spawn(String definitionName, Position position) {
        return engine.spawn(definitionName, position);
    }

    /**
     * 自备产物入口：调用方自行完成世界侧生产（复制 level 目录 / 加载 level 等重 IO），
     * 把产物直接交引擎默认过程（不执行 spawn 钩子；despawn 照 definition 配置生效）。
     *
     * @param definitionName 定义名（无需配置 spawn）
     * @param target         调用方产物（非 null，将作为实体代理引用）
     * @return 新实体
     */
    public EcsEntity spawnBound(String definitionName, Object target) {
        return engine.spawnBound(definitionName, target);
    }

    /**
     * 手动重评实体（只补挂不卸载）。
     *
     * @param entity 实体
     */
    public void refresh(EcsEntity entity) {
        engine.refresh(entity);
    }

    /**
     * 手动注册 Nukkit 实体为 ECS 实体（进入管线）。
     *
     * @param nukkitEntity Nukkit 实体
     * @return EcsEntity
     */
    public EcsEntity registerEntity(cn.nukkit.entity.Entity nukkitEntity) {
        return engine.enterNukkitEntity(nukkitEntity);
    }

    /**
     * 手动注册方块实体为 ECS 实体（进入管线）。
     *
     * @param blockEntity 方块实体
     * @return EcsEntity
     */
    public EcsEntity registerEntity(cn.nukkit.blockentity.BlockEntity blockEntity) {
        return engine.enterBlockEntity(blockEntity);
    }

    /**
     * 创建纯虚拟实体（不关联任何定义 spawn）。
     *
     * @return 新实体
     */
    public EcsEntity createVirtualEntity() {
        return engine.createVirtual();
    }

    /**
     * 倒排索引查询：同时挂有全部指定组件的实体。
     *
     * @param componentTypes 组件类
     * @return 实体列表快照
     */
    public List<EcsEntity> query(Class<?>... componentTypes) {
        return engine.query(componentTypes);
    }

    /**
     * 按实体 id 取实体。
     *
     * @param id 实体 id
     * @return 实体；不存在 null
     */
    public EcsEntity getEntity(long id) {
        return engine.getEntity(id);
    }

    /**
     * 全部实体快照。
     *
     * @return 实体列表
     */
    public List<EcsEntity> entities() {
        return engine.entities();
    }

    /**
     * 已加载定义快照。
     *
     * @return 定义名 → 定义
     */
    public Map<String, GameObjectDefinition> definitions() {
        return engine.definitions();
    }

    // ==================== ForPlugin ====================

    /**
     * 绑定当前插件，返回可扫描其类路径/加载其资源的作用域。
     *
     * @param plugin 插件实例
     * @return 作用域
     */
    @Override
    public EcsPluginScope forPlugin(Plugin plugin) {
        return new EcsPluginScope(this, plugin.getClass().getClassLoader());
    }

    /**
     * 按插件名绑定，返回作用域。
     *
     * @param pluginName 插件名（与 plugin.yml 一致）
     * @return 作用域
     */
    @Override
    public EcsPluginScope forPlugin(String pluginName) {
        return new EcsPluginScope(this, PluginClassLoaderFactory.getClassLoader(pluginName));
    }
}
