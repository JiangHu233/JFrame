package io.github.JiangHu.jframe.engine.ecs.engine;

import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.engine.ecs.despawner.DespawnContext;
import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.entity.Component;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.engine.ecs.registry.ComponentRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.ParamBinder;
import io.github.JiangHu.jframe.engine.ecs.registry.ResolverRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SelectorRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SpawnerRegistry;
import io.github.JiangHu.jframe.engine.ecs.registry.SystemEventRegistry;
import io.github.JiangHu.jframe.engine.ecs.spawner.SpawnContext;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.system.EcsEngineAware;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.pool.EcsPool;
import io.github.JiangHu.jframe.engine.pool.EcsPoolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ECS 引擎（内部核心）— 实体注册表 + 组件倒排存储 + 挂载/卸载管线 + tick 调度。
 *
 * <h3>挂载管线</h3>
 * <pre>
 * enter（diff 新增 / spawn / 手动注册）
 *   → 建 EcsEntity（代理任意 Object 引用）→ 遍历全部带 selector 的定义 → matches
 *   → 实例化组件 → bindHost → ParamBinder 注入 → onMount() → 写入倒排索引
 *   （onMount 异常：回滚该组件，不影响其他组件）
 * remove（diff 消失 / destroy）
 *   → 逆挂载序逐组件 onUnmount() → despawn 回调（销毁通知驱动，见下）→ 清实体表与索引
 * 定义加载 → 存量实体回溯评估（"已经有的也要识别到"）
 * refresh(entity) → 重跑全部 selector 评估，只补挂不卸载
 * </pre>
 *
 * <h3>despawn 触发（销毁通知驱动）</h3>
 * <ul>
 *   <li>触发：显式 {@code destroy()}；世界侧销毁通知（tracker：生物死亡 / 方块破坏 / 区块卸载
 *       → {@link #removeByBinding}）——均经 {@link #removeEntity(EcsEntity)}，在全部
 *       onUnmount <b>之后</b>回调出生定义的 despawner（产物经 {@link DespawnContext#product()} 传回）</li>
 *   <li>不触发：{@link #removeAll()}（引擎 stop，产物存续）；非 spawn/spawnBound 出生的实体
 *       （tracker 收养 / 手动注册，无出生记录）</li>
 *   <li>引擎未跟踪的销毁事件（如 LevelUnload）：用户在 component 里加 {@code @OnEcsEvent}
 *       监听方法手动处理</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * 全部操作发生在 Nukkit 主线程（diff 扫描、tick、事件分发同线程）；
 * 单元测试中可在任意单线程直接调用。
 */
public class EcsEngine {

    /** 组件注册表 */
    private final ComponentRegistry componentRegistry;

    /** 选择器注册表 */
    private final SelectorRegistry selectorRegistry;

    /** 生成器注册表 */
    private final SpawnerRegistry spawnerRegistry;

    /** 宿主解析器注册表 */
    private final ResolverRegistry resolverRegistry;

    /** 系统事件注册表（系统实例上的 @OnEcsEvent 全局处理器） */
    private final SystemEventRegistry systemEventRegistry;

    /** 对象池（组件 opt-in 池化 + 实体壳复用；disabled 实例 = 行为与无池一致） */
    private final EcsPool ecsPool;

    /** 实体 id → EcsEntity */
    private final Map<Long, EcsEntity> entities = new HashMap<>();

    /** 代理引用（任意对象，引用相等）→ EcsEntity */
    private final Map<Object, EcsEntity> byBinding = new IdentityHashMap<>();

    /** 定义名 → 定义（保持加载顺序，决定"首个定义生效"） */
    private final Map<String, GameObjectDefinition> definitions = new LinkedHashMap<>();

    /** 组件类 → （实体 id → 组件实例）倒排索引 */
    private final Map<Class<?>, Map<Long, Component>> componentIndex = new HashMap<>();

    /** ticking 系统（注册期检测到 override tick 者，按 order 升序；纯事件系统不进此表=零轮询） */
    private final List<EcsSystem> tickingSystems = new ArrayList<>();

    /** 实体 id 计数器 */
    private long nextId = 1;

    /** 累计 tick 数（系统 interval 对齐用） */
    private long tickCount;

    /**
     * 构造引擎（默认开启对象池：组件 opt-in 池化 + 实体壳复用）。
     *
     * @param componentRegistry   组件注册表
     * @param selectorRegistry    选择器注册表
     * @param spawnerRegistry     生成器注册表
     * @param resolverRegistry    宿主解析器注册表
     * @param systemEventRegistry 系统事件注册表
     */
    public EcsEngine(ComponentRegistry componentRegistry,
                     SelectorRegistry selectorRegistry,
                     SpawnerRegistry spawnerRegistry,
                     ResolverRegistry resolverRegistry,
                     SystemEventRegistry systemEventRegistry) {
        this(componentRegistry, selectorRegistry, spawnerRegistry, resolverRegistry,
                systemEventRegistry, new EcsPool(new EcsPoolConfig()));
    }

    /**
     * 构造引擎（七参：注入池；传 null 等价关闭池化，行为与无池完全一致）。
     *
     * @param componentRegistry   组件注册表
     * @param selectorRegistry    选择器注册表
     * @param spawnerRegistry     生成器注册表
     * @param resolverRegistry    宿主解析器注册表
     * @param systemEventRegistry 系统事件注册表
     * @param ecsPool             对象池（null = 关闭）
     */
    public EcsEngine(ComponentRegistry componentRegistry,
                     SelectorRegistry selectorRegistry,
                     SpawnerRegistry spawnerRegistry,
                     ResolverRegistry resolverRegistry,
                     SystemEventRegistry systemEventRegistry,
                     EcsPool ecsPool) {
        this.componentRegistry = componentRegistry;
        this.selectorRegistry = selectorRegistry;
        this.spawnerRegistry = spawnerRegistry;
        this.resolverRegistry = resolverRegistry;
        this.systemEventRegistry = systemEventRegistry;
        this.ecsPool = ecsPool != null ? ecsPool : EcsPool.disabled();
    }

    // ==================== 实体进入 / 移除 ====================

    /**
     * 统一进入路径：以任意对象为代理引用建实体（幂等）。
     * <p>代理目标不限类型——Entity / BlockEntity / Level / Server / 资源 POJO 皆可。
     *
     * @param binding 被代理的游戏内容引用（null = 纯虚拟）
     * @return 对应 EcsEntity（已注册则返回既有实例）
     */
    public EcsEntity enterBound(Object binding) {
        if (binding != null) {
            EcsEntity existing = byBinding.get(binding);
            if (existing != null) {
                return existing;
            }
        }
        // 壳复用：池中有空闲壳则 recycle（id 单调不复用），否则新建
        EcsEntity entity = ecsPool.obtainEntityShell();
        if (entity != null) {
            entity.recycle(nextId++, binding);
        } else {
            entity = new EcsEntity(nextId++, binding, this);
        }
        entities.put(entity.id(), entity);
        if (binding != null) {
            byBinding.put(binding, entity);
        }
        applyDefinitions(entity);
        return entity;
    }

    /**
     * 注册 Nukkit 实体（世界 diff 新增 / spawn / 手动）；幂等。{@link #enterBound} 的类型化入口。
     *
     * @param nukkitEntity Nukkit 实体
     * @return 对应 EcsEntity（已注册则返回既有实例）
     */
    public EcsEntity enterNukkitEntity(Entity nukkitEntity) {
        return enterBound(nukkitEntity);
    }

    /**
     * 注册方块实体（世界 diff 新增 / spawn / 手动）；幂等。{@link #enterBound} 的类型化入口。
     *
     * @param blockEntity 方块实体（如箱子）
     * @return 对应 EcsEntity
     */
    public EcsEntity enterBlockEntity(BlockEntity blockEntity) {
        return enterBound(blockEntity);
    }

    /**
     * 创建纯虚拟实体（无代理引用）。{@link #enterBound} 的空引用入口。
     *
     * @return 新实体（已进入挂载管线）
     */
    public EcsEntity createVirtual() {
        return enterBound(null);
    }

    /**
     * 按代理引用取实体。
     *
     * @param binding 任意被代理对象（Entity / BlockEntity / Level / ...）
     * @return 实体；未跟踪 null
     */
    public EcsEntity getByBinding(Object binding) {
        return byBinding.get(binding);
    }

    /**
     * 按实体 id 取实体。
     *
     * @param id 实体 id
     * @return 实体；不存在 null
     */
    public EcsEntity getEntity(long id) {
        return entities.get(id);
    }

    /**
     * 移除实体：逆挂载序卸载全部组件 → despawn 回调（销毁通知驱动）→ 出表失效。
     *
     * @param entity 实体
     */
    public void removeEntity(EcsEntity entity) {
        removeEntity(entity, true);
    }

    /**
     * 移除实体（引擎内部）：removeAll（引擎 stop）走 fireDespawn=false，产物存续不做回收。
     *
     * @param entity      实体
     * @param fireDespawn 是否触发出生定义的 despawn 回调
     */
    private void removeEntity(EcsEntity entity, boolean fireDespawn) {
        if (entity == null || !entities.containsKey(entity.id())) {
            return;
        }
        entities.remove(entity.id());
        if (entity.binding() != null) {
            byBinding.remove(entity.binding());
        }
        List<Component> mounted = entity.mountedComponents();
        Collections.reverse(mounted);
        for (Component component : mounted) {
            try {
                component.onUnmount();
            } catch (Exception e) {
                EcsLog.error("组件 " + component.getClass().getSimpleName() + " onUnmount 异常", e);
            }
            entity.removeComponent(component.getClass());
            unindex(component.getClass(), entity.id());
            ecsPool.freeComponent(component);
        }
        // despawn 回调：全部 onUnmount 之后（产物经 DespawnContext 传回；异常只记录）
        EcsEntity.SpawnRecord record = entity.spawnRecord();
        if (fireDespawn && record != null && record.despawner() != null) {
            try {
                record.despawner().despawn(
                        new DespawnContext(record.definitionName(), entity.binding(), this));
            } catch (Exception e) {
                EcsLog.error("回收器 despawn 异常（定义 '" + record.definitionName() + "'）", e);
            }
        }
        // 回调完成后才失效：onUnmount / despawn 回调期间 binding 与 tags 仍可读
        entity.invalidate();
        // 壳归还池（tags 已快照化、组件表已清空、spawnRecord 由 recycle 重生时清）
        ecsPool.freeEntityShell(entity);
    }

    /**
     * 按代理引用移除实体（世界侧销毁通知：生物死亡 / 方块破坏 / 区块卸载）。
     *
     * @param binding 任意被代理对象
     */
    public void removeByBinding(Object binding) {
        removeEntity(byBinding.get(binding));
    }

    /**
     * 移除全部实体（引擎 stop 用；<b>不触发</b> despawn——产物随插件生命周期存续）；
     * 移除后清空对象池（释放壳与组件空闲引用）。
     */
    public void removeAll() {
        for (EcsEntity entity : List.copyOf(entities.values())) {
            removeEntity(entity, false);
        }
        ecsPool.clear();
    }

    // ==================== 定义与挂载 ====================

    /**
     * 加载定义：冲突检查 → 入表 → 回溯评估全部存量实体。
     *
     * @param definition 定义
     * @throws EcsConfigException 定义名冲突
     */
    public void loadDefinition(GameObjectDefinition definition) {
        if (definitions.containsKey(definition.name())) {
            throw new EcsConfigException("GameObject 定义名冲突: '" + definition.name()
                    + "'（resources 与数据目录不合并，同名一律拒绝；热更新请用 reloadDefinition）");
        }
        definitions.put(definition.name(), definition);
        for (EcsEntity entity : List.copyOf(entities.values())) {
            applyDefinition(entity, definition);
        }
    }

    /**
     * 热重载定义（配置驱动工作流的核心原语）：
     * <ul>
     *   <li>新名字 → 等价 {@link #loadDefinition}（装载 + 全量回溯挂载）</li>
     *   <li>已存在 → <b>覆盖入表</b>，并对受影响实体做差量重挂（按挂载来源
     *       {@link Component#mountedFrom()} 判定归属，组件 id 相同不误伤）：
     *     <ol>
     *       <li>「出生自本定义（spawnRecord）」的实体 → 卸载旧 spec 全部组件实例
     *           （onUnmount 正常回调）</li>
     *       <li>spawn 出生实体 → 直接挂载新 spec 全部组件（新参数实例）+ 重打 tags</li>
     *       <li>其余实体 → 仅卸载<b>确由本定义挂载</b>的旧 spec 组件（selector 挂载），
     *           手动挂载（来源 null）不剥除；随后 {@code applyDefinition} 重评估
     *           （selector 仍命中者补挂新 spec，不再命中者保持已卸载状态）</li>
     *     </ol></li>
     * </ul>
     * <p>语义边界：tags 只增不删（并集）；手动挂载组件由挂载方自理，定义重载不动；
     * 定义<b>文件删除</b>不在热重载范围（需重启）。
     *
     * @param definition 新定义
     * @throws EcsConfigException 组件规格引用未注册的组件 id
     */
    public void reloadDefinition(GameObjectDefinition definition) {
        GameObjectDefinition old = definitions.get(definition.name());
        if (old == null) {
            loadDefinition(definition);
            return;
        }
        definitions.put(definition.name(), definition);
        for (EcsEntity entity : List.copyOf(entities.values())) {
            boolean fromThisDefinition = entity.spawnRecord() != null
                    && definition.name().equals(entity.spawnRecord().definitionName());
            if (fromThisDefinition) {
                // ① spawn 出生实体：卸载旧 spec 全部在场组件（本定义全权管辖）
                for (GameObjectDefinition.ComponentSpec spec : old.components()) {
                    Class<? extends Component> type = componentRegistry.require(spec.id());
                    if (entity.has(type)) {
                        unmountComponent(entity, spec.id());
                    }
                }
                // ② 直接挂载新 spec（新参数）+ 重打 tags
                for (String tag : definition.tags()) {
                    entity.addTag(tag);
                }
                for (GameObjectDefinition.ComponentSpec spec : definition.components()) {
                    Class<? extends Component> type = componentRegistry.require(spec.id());
                    if (entity.has(type)) {
                        continue;
                    }
                    mountComponent(entity, type, spec.params(), definition.name());
                }
            } else {
                // ③ 其余实体：仅卸载确由本定义挂载的旧 spec 组件（手动挂载不动），再重评估 selector
                for (GameObjectDefinition.ComponentSpec spec : old.components()) {
                    Class<? extends Component> type = componentRegistry.require(spec.id());
                    Component component = entity.get(type);
                    if (component != null && definition.name().equals(component.mountedFrom())) {
                        unmountComponent(entity, spec.id());
                    }
                }
                applyDefinition(entity, definition);
            }
        }
    }

    /**
     * 对实体应用全部带 selector 的定义。
     */
    private void applyDefinitions(EcsEntity entity) {
        for (GameObjectDefinition definition : definitions.values()) {
            applyDefinition(entity, definition);
        }
    }

    /**
     * 应用单条定义：selector 命中 → 打标签 + 挂组件（同类组件已挂则跳过 = 定义并集去重）。
     */
    private void applyDefinition(EcsEntity entity, GameObjectDefinition definition) {
        if (definition.selector() == null) {
            return;
        }
        boolean matched;
        try {
            matched = definition.selector().matches(entity);
        } catch (Exception e) {
            EcsLog.error("选择器匹配异常（定义 '" + definition.name() + "'）", e);
            return;
        }
        if (matched) {
            mountDefinition(entity, definition);
        }
    }

    /**
     * 挂载定义的组件与标签（不做 selector 判定；spawn 路径与本路径共用）。
     */
    private void mountDefinition(EcsEntity entity, GameObjectDefinition definition) {
        for (String tag : definition.tags()) {
            entity.addTag(tag);
        }
        for (GameObjectDefinition.ComponentSpec spec : definition.components()) {
            Class<? extends Component> type = componentRegistry.require(spec.id());
            if (entity.has(type)) {
                continue; // 同一实体同类组件只挂首个（按定义加载顺序）
            }
            mountComponent(entity, type, spec.params(), definition.name());
        }
    }

    /**
     * 实例化并挂载单个组件：<b>手动挂载</b>（无来源，定义重载不剥除）。
     *
     * @param entity 宿主实体
     * @param type   组件类
     * @param params 参数表
     */
    public void mountComponent(EcsEntity entity, Class<? extends Component> type,
                               Map<String, Object> params) {
        mountComponent(entity, type, params, null);
    }

    /**
     * 实例化并挂载单个组件：实例化 → bindHost → 绑定挂载来源 → 参数注入 → onMount → 入索引。
     * <p>任一步异常：回滚该组件（记录日志），不影响其他组件。
     *
     * @param entity 宿主实体
     * @param type   组件类
     * @param params 参数表
     * @param source 挂载来源定义名（定义驱动挂载传入；null = 手动挂载）
     */
    private void mountComponent(EcsEntity entity, Class<? extends Component> type,
                                Map<String, Object> params, String source) {
        // 池化组件优先取池（池空 / 非池化类走反射新建）
        Component component = componentRegistry.isPooled(type) ? ecsPool.obtainComponent(type) : null;
        try {
            if (component == null) {
                var constructor = type.getDeclaredConstructor();
                constructor.trySetAccessible();
                component = (Component) constructor.newInstance();
            }
            component.bindHost(entity);
            component.bindSource(source);
            ParamBinder.bind(component, params);
        } catch (ReflectiveOperationException e) {
            EcsLog.error("组件实例化失败: " + type.getName(), e);
            return;
        } catch (EcsConfigException e) {
            EcsLog.error("组件参数注入失败: " + type.getName(), e);
            return;
        }
        entity.putComponent(component);
        componentIndex.computeIfAbsent(type, k -> new HashMap<>()).put(entity.id(), component);
        try {
            component.onMount();
        } catch (Exception e) {
            EcsLog.error("组件 onMount 异常，回滚: " + type.getSimpleName()
                    + " → " + entity, e);
            entity.removeComponent(type);
            unindex(type, entity.id());
            ecsPool.freeComponent(component);
        }
    }

    /**
     * 按注册 id 手动卸载单个组件。
     *
     * @param entity      宿主实体
     * @param componentId 组件注册 id
     */
    public void unmountComponent(EcsEntity entity, String componentId) {
        Class<? extends Component> type = componentRegistry.require(componentId);
        Component component = entity.get(type);
        if (component == null) {
            return;
        }
        try {
            component.onUnmount();
        } catch (Exception e) {
            EcsLog.error("组件 onUnmount 异常: " + componentId, e);
        }
        entity.removeComponent(type);
        unindex(type, entity.id());
        ecsPool.freeComponent(component);
    }

    /**
     * 手动重评：重跑该实体全部 selector 评估（只补挂不卸载）。
     *
     * @param entity 实体
     */
    public void refresh(EcsEntity entity) {
        applyDefinitions(entity);
    }

    // ==================== spawn / spawnBound ====================

    /**
     * 现场生成：取定义已实例化的 spawner 执行世界侧生产 → 产物（任意对象）即实体代理引用
     * → 挂本定义组件（无需 selector 命中）→ selector 管线继续并集挂载。
     *
     * @param definitionName 定义名
     * @param position       生成位置（纯虚拟定义可传 null）
     * @return 新实体
     * @throws EcsConfigException 定义不存在或无 spawn 配置
     */
    public EcsEntity spawn(String definitionName, Position position) {
        GameObjectDefinition definition = definitions.get(definitionName);
        if (definition == null) {
            throw new EcsConfigException("未注册的 GameObject 定义: '" + definitionName
                    + "'（已加载: " + definitions.keySet() + "）");
        }
        if (definition.spawner() == null) {
            throw new EcsConfigException("定义 '" + definitionName + "' 未配置 spawn（use: 生成器名）");
        }
        Object product;
        try {
            product = definition.spawner().spawn(new SpawnContext(definitionName, position, this));
        } catch (Exception e) {
            throw new EcsConfigException("生成器执行失败（定义 '" + definitionName + "'）", e);
        }
        EcsEntity entity = enterBound(product);
        entity.recordSpawn(new EcsEntity.SpawnRecord(definitionName, definition.despawner()));
        mountDefinition(entity, definition);
        return entity;
    }

    /**
     * 自备产物入口：调用方自行完成世界侧生产（复制 level 目录 / 加载 level 等重 IO），
     * 把产物直接交引擎默认过程（建实体代理 → 挂本定义组件 → 记录出生，despawn 照常生效）。
     * <p>"Unity 管理器包 Instantiate"模式——产物规则同 spawn：任意非 null 对象。
     *
     * @param definitionName 定义名（无需配置 spawn）
     * @param target         调用方产物（将作为实体代理引用；纯虚拟走 {@link #spawn}）
     * @return 新实体
     * @throws EcsConfigException 定义不存在或产物为 null
     */
    public EcsEntity spawnBound(String definitionName, Object target) {
        GameObjectDefinition definition = definitions.get(definitionName);
        if (definition == null) {
            throw new EcsConfigException("未注册的 GameObject 定义: '" + definitionName
                    + "'（已加载: " + definitions.keySet() + "）");
        }
        if (target == null) {
            throw new EcsConfigException("spawnBound 产物不得为 null（纯虚拟请走 spawn）");
        }
        EcsEntity entity = enterBound(target);
        entity.recordSpawn(new EcsEntity.SpawnRecord(definitionName, definition.despawner()));
        mountDefinition(entity, definition);
        return entity;
    }

    // ==================== query ====================

    /**
     * 倒排索引查询：返回<b>同时挂有全部指定组件</b>的实体快照。
     *
     * @param componentTypes 组件类（可变参数；空 = 全部实体）
     * @return 实体列表快照
     */
    public List<EcsEntity> query(Class<?>... componentTypes) {
        if (componentTypes.length == 0) {
            return List.copyOf(entities.values());
        }
        Map<Long, Component> smallest = smallestIndex(componentTypes);
        if (smallest == null) {
            return List.of();
        }
        List<EcsEntity> result = new ArrayList<>();
        outer:
        for (Long entityId : smallest.keySet()) {
            EcsEntity entity = entities.get(entityId);
            if (entity == null) {
                continue;
            }
            for (Class<?> type : componentTypes) {
                if (!entity.has(type)) {
                    continue outer;
                }
            }
            result.add(entity);
        }
        return result;
    }

    /**
     * 取参数中规模最小的组件索引。
     */
    private Map<Long, Component> smallestIndex(Class<?>[] componentTypes) {
        Map<Long, Component> smallest = null;
        for (Class<?> type : componentTypes) {
            Map<Long, Component> index = componentIndex.get(type);
            if (index == null || index.isEmpty()) {
                return null;
            }
            if (smallest == null || index.size() < smallest.size()) {
                smallest = index;
            }
        }
        return smallest;
    }

    // ==================== 系统 / tick ====================

    /**
     * 注册系统。
     * <p>同时：实现 {@link EcsEngineAware} 的系统先回调 bindEngine 注入引擎引用；
     * 再扫描其实例类上的 {@code @OnEcsEvent} 方法建立全局事件处理器（校验失败 fail-fast）；
     * 最后做 tick override 检测——<b>未 override {@code tick(EcsEngine)} 的系统不进入
     * tick 执行路径</b>（纯事件系统零轮询），只存在于事件注册表。
     *
     * @param system 系统
     */
    public synchronized void addSystem(EcsSystem system) {
        if (system instanceof EcsEngineAware aware) {
            aware.bindEngine(this);
        }
        systemEventRegistry.register(system);
        if (overridesTick(system.getClass())) {
            tickingSystems.add(system);
            tickingSystems.sort(Comparator.comparingInt(EcsSystem::order));
        }
    }

    /**
     * 驱动一帧：tickCount 递增，命中 interval 的 <b>ticking 系统</b>（注册期检测到
     * override tick 者）按序执行（单个异常只记录）；纯事件系统零轮询、零调用。
     */
    public void tick() {
        tickCount++;
        for (EcsSystem system : tickingSystems) {
            int interval = Math.max(1, system.interval());
            if (tickCount % interval != 0) {
                continue;
            }
            try {
                system.tick(this);
            } catch (Exception e) {
                EcsLog.error("系统 " + system.getClass().getSimpleName() + " tick 异常", e);
            }
        }
    }

    /**
     * 检测系统类是否 override 了 {@code tick(EcsEngine)}：沿实例类继承链（不含 Object）
     * 查<b>声明</b>方法——接口 default 不在类声明中，"未 override"判定天然正确；
     * 抽象基类声明了 tick 的继承体系判为 ticking（基类逻辑会被调用）。
     *
     * @param systemClass 系统类
     * @return override 了 tick 返回 true
     */
    static boolean overridesTick(Class<?> systemClass) {
        for (Class<?> c = systemClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("tick")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == EcsEngine.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 累计 tick 数。
     *
     * @return tick 计数
     */
    public long tickCount() {
        return tickCount;
    }

    // ==================== 事件宿主解析（事件桥调用） ====================

    /**
     * 把宿主候选换算为已跟踪实体（含<b>惰性收养</b>）。
     *
     * <h3>收养语义（零延迟挂载的关键）</h3>
     * <p>
     * 候选为<b>存在但未跟踪</b>的 Entity / BlockEntity（含 Position 换算出的方块实体）时，
     * 当场走进入管线（enter，幂等，回溯挂载立即生效）并返回新实体——
     * <b>同一事件立即分发给刚挂载的组件</b>（玩家右键/打开刚放的箱子，当 tick 即得行为）。
     * 这使事件分发具备挂载副作用（同线程、幂等、选择器异常已有 per-definition 捕获），
     * 并与 NukkitWorldTracker 的事件跟踪、低频对账共同保证覆盖。
     *
     * @param candidate 宿主候选（Entity/BlockEntity/Position/Block/EcsEntity）
     * @return 已跟踪（或刚收养）的实体；无法换算返回 null
     */
    public EcsEntity resolveCandidate(Object candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof EcsEntity ecsEntity) {
            return entities.containsKey(ecsEntity.id()) ? ecsEntity : null;
        }
        if (candidate instanceof Entity nukkit) {
            EcsEntity tracked = byBinding.get(nukkit);
            return tracked != null ? tracked : enterBound(nukkit);
        }
        if (candidate instanceof BlockEntity blockEntity) {
            EcsEntity tracked = byBinding.get(blockEntity);
            return tracked != null ? tracked : enterBound(blockEntity);
        }
        if (candidate instanceof Position position) {
            if (position.getLevel() == null) {
                return null;
            }
            BlockEntity blockEntity = position.getLevelBlockEntity();
            if (blockEntity == null) {
                return null;
            }
            EcsEntity tracked = byBinding.get(blockEntity);
            return tracked != null ? tracked : enterBound(blockEntity);
        }
        return null;
    }

    // ==================== 访问器 ====================

    /**
     * 全部实体快照。
     *
     * @return 实体列表
     */
    public List<EcsEntity> entities() {
        return List.copyOf(entities.values());
    }

    /**
     * 已加载定义快照（名称 → 定义，保持加载顺序）。
     *
     * @return 定义 map
     */
    public Map<String, GameObjectDefinition> definitions() {
        return Collections.unmodifiableMap(definitions);
    }

    /**
     * 组件注册表。
     *
     * @return 注册表
     */
    public ComponentRegistry componentRegistry() {
        return componentRegistry;
    }

    /**
     * 对象池（组件 opt-in 池化 + 实体壳复用；指标观测 / clear 用）。
     *
     * @return 池实例（永不 null；关闭状态为 disabled 实例）
     */
    public EcsPool ecsPool() {
        return ecsPool;
    }

    /**
     * 选择器注册表。
     *
     * @return 注册表
     */
    public SelectorRegistry selectorRegistry() {
        return selectorRegistry;
    }

    /**
     * 生成器注册表。
     *
     * @return 注册表
     */
    public SpawnerRegistry spawnerRegistry() {
        return spawnerRegistry;
    }

    /**
     * 宿主解析器注册表。
     *
     * @return 注册表
     */
    public ResolverRegistry resolverRegistry() {
        return resolverRegistry;
    }

    /**
     * 系统事件注册表。
     *
     * @return 注册表
     */
    public SystemEventRegistry systemEventRegistry() {
        return systemEventRegistry;
    }

    /**
     * 从倒排索引移除。
     */    private void unindex(Class<?> type, long entityId) {
        Map<Long, Component> index = componentIndex.get(type);
        if (index != null) {
            index.remove(entityId);
        }
    }
}
