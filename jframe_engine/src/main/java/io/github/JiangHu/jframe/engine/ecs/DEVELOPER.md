# ECS 模块 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，记录每个设计决策、运行时数据流、类的内部状态与线程模型，
> 并说明修改代码时**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个模块，请看 [README.md](README.md)。

---

## 目录

- [1. 设计决策回放](#1-设计决策回放)
- [2. 架构总览](#2-架构总览)
- [3. 运行时数据流](#3-运行时数据流)
- [4. 类逐一剖析](#4-类逐一剖析)
- [5. 线程安全模型](#5-线程安全模型)
- [6. 生命周期管理](#6-生命周期管理)
- [7. 扩展点](#7-扩展点)
- [8. 已知限制与陷阱](#8-已知限制与陷阱)
- [9. 修改指南（改代码前必读）](#9-修改指南改代码前必读)

---

## 1. 设计决策回放

以下决策在规划阶段与使用者逐条确认，是本模块的行为契约。改动任何一条都是**破坏性变更**。

| # | 决策 | 要点 |
|---|------|------|
| 1 | **E = 轻量包装 + 双绑定 + 虚拟** | `EcsEntity`（long id + tags）可绑 `Entity` / `BlockEntity`（箱子是方块实体不是 Entity！），也支持无绑定纯虚拟 |
| 2 | **绑定规则 = 选择器 + 组件列表** | 选择器是注册类 + 参数表；定义加载**回溯**存量实体，实体进入时评估全部定义（两个方向都命中） |
| 3 | **状态变化不自动重评** | 筛选只在"实体进入"和"定义加载"时评估；需要时手动 `refresh(entity)`（只补挂不卸载） |
| 4 | **属性即子类字段** | 组件/选择器/生成器的配置参数就是类字段，`ParamBinder` 按名反射注入；未知字段加载期 fail-fast |
| 5 | **组件任意时刻识别宿主** | 引擎实例化后立即 `bindHost(entity)`（先于参数注入），`host()` 在钩子/事件回调/任意时刻可用；钩子为**无参** `onMount()`/`onUnmount()` |
| 6 | **六类对象同构** | 组件/选择器/生成器/回收器/解析器/系统 = 注解扫描 + 手动注册并存；配置侧统一 `use`/`id` + `params` |
| 7 | **事件自包含桥** | 不依赖 jframe_listener 模块；宿主解析参考其 `@KeyExtractor` 多槽位思想；四级过滤链分发 |
| 8 | **世界跟踪事件化（修订）** | 原方案"每 tick 全量 diff"（因 BlockEntity 无生成事件）→ 现事件驱动为主：实体走 Spawn/Despawn/Join 事件、破坏即时卸载、区块批量；**方块实体放置零延迟**靠 `resolveCandidate` 惰性收养（放置事件触发时方块实体尚不存在，同步挂载原理不可行）；diff 降级为每 100 tick 对账兜底 |
| 9 | **方法级过滤 = 纯代码守卫（修订二）** | when SpEL + filter 双轨 → filter 统一 → **filter 机制整体移除**：方法内首行 `if` 直接读 `this` 字段（外部 filter 类曾被实机验证为认知错位来源——YAML 阈值/严格性/方法体三处分离）；`ignoreCancelled` 保留 |
| 10 | **tick 按需** | `EcsSystem.tick` 可选（default 空实现）；注册期 override 检测，无 tick 系统不进 tick 路径（零轮询非空调用） |
| 11 | **生成语义** | spawner 一次调用只生成其对应一个实体；多数量由调用方循环多次 `spawn`（不设引擎批量 API） |
| 12 | **代理统一绑定（修订）** | `EcsEntity` 不按类型枚举绑定（旧 `nukkitEntity`/`blockEntity` 双字段 + `entity()/blockEntity()/position()` 糖已删）——单一 `Object binding` 代理任意游戏内容（Entity/BlockEntity/Level/Server/POJO）；取引用仅 `<T> T binding()`（无参、赋值目标推断强转，类型不符抛 CCE）+ `isVirtual()`，判型一律 instanceof；引擎统一入口 `enterBound(Object)`（`byNukkit` 更名 `byBinding`） |
| 13 | **despawn 销毁通知驱动** | despawn = 第六类注册对象（`Despawner`/`@EcsDespawner`/`DespawnerRegistry`，契约对象是**产物**非实体：`DespawnContext(definitionName, product, engine)`）；仅 spawn/spawnBound 出生的实体带 SpawnRecord；触发 = 显式 `destroy()` 或 tracker 世界侧销毁通知（均经 `removeEntity`，回调在全部逆序 onUnmount **之后**）；**stop 不触发**；引擎未覆盖的销毁事件由用户在 component 加 `@OnEcsEvent` 手动处理；产物契约同理适用于 spawn（`EntitySpawner` 更名 `Spawner`，返回任意对象） |

### 1.1 与计划的命名偏差（实现时必要修正）

1. **生成器接口为 [`Spawner`](spawner/Spawner.java)**：注解 `@EcsSpawner` 与接口同名（旧 `EcsSpawner`/`EntitySpawner`）时用户类需要 import 两个同名类——Java 不允许；与 `EntitySelector` 对称命名，且契约产物化后不再冠 Entity 前缀。回收器同理为 `Despawner` + `@EcsDespawner`。
2. **系统注解为 [`@EcsSystemAnno`](annotation/EcsSystemAnno.java)**：避免与 [`EcsSystem`](system/EcsSystem.java) 接口重名，理由同上。
3. **类型选择器参数是 `type` 而非 `class`**：`class` 是 Java 关键字不能作字段名。

### 1.2 细节修正

- `ComponentBase.host` 是 **`transient`**（非计划所述 final）：`bindHost` 必须在构造后调用，字段无法 final；`ParamBinder` 排除 `transient` 字段达到同样的"不参与注入"效果。
- 注册表构造校验用 `getDeclaredConstructor()` + `trySetAccessible()`（而非"公共无参构造"）：内置选择器/生成器/解析器是包私有类，隐式构造非 public。
- 引擎卸载为**逆挂载序**（`removeEntity` 内 `Collections.reverse`）。

---

## 2. 架构总览

### Bean DAG（[ecs-spring.xml](../../../../../../../../src/main/resources/ecs-spring.xml) 装配，单向无循环）

```
componentRegistry ┐
selectorRegistry  ├─→ gameObjectLoader（只读四表做校验/实例化）
spawnerRegistry   │
despawnerRegistry ┘

componentRegistry ┐
selectorRegistry  ├─→ ecsEngine（五表 + 实体/索引/定义/系统）
spawnerRegistry   ┘        ↑
resolverRegistry ──────────┤
systemEventRegistry ───────┘
        │
        ↓
worldScanner(engine) → ecsScheduler(engine, scanner)
worldTracker(engine) → ecsAPI（构造注入 despawnerRegistry；bindPlugin/start 转发绑定与激活）
ecsEventBridge(engine, componentRegistry, resolverRegistry, systemEventRegistry)
ecsAPI(engine, loader, bridge, scheduler, tracker, despawnerRegistry)   ← 构造时预注册全部内置（BuiltinSelectors/Spawners/Resolvers）
```

### 分层职责

| 层 | 类 | 职责 |
|----|----|------|
| L0 注册表 | `registry/*` | 注册名校验、`@OnEcsEvent` 建表（签名校验共享 `HandlerValidation`）、选择器/生成器实例化注入、解析器按事件类型索引 |
| L1 引擎 | [`EcsEngine`](engine/EcsEngine.java) | 实体表、Nukkit 绑定索引、组件倒排索引、挂载/卸载管线、定义回溯、query、ticking 系统调度（`overridesTick` 检测）、`resolveCandidate` 惰性收养 |
| L2 桥 | [`NukkitWorldTracker`](world/NukkitWorldTracker.java)（事件跟踪）/ [`NukkitWorldScanner`](world/NukkitWorldScanner.java)（低频对账）/ [`EcsEventBridge`](event/EcsEventBridge.java) / [`EcsScheduler`](engine/EcsScheduler.java) | 实体生成移除/区块进出/破坏卸载事件、对账 diff、事件统一 filter 分发、主循环 |
| L3 门面 | [`EcsAPI`](EcsAPI.java) / [`EcsPluginScope`](EcsPluginScope.java) | 用户入口、注解扫描（复用 core 的 `AnnotatedClassScanner`）、classpath 定义加载 |

---

## 3. 运行时数据流

### 3.1 绑定与启动（`PluginAware.bindPlugin` → `EcsAPI.start`）

```
Spring 刷新 → JFrameMain.bindPlugin() → ecsAPI.bindPlugin
    → bridge.bindPlugin（刷新暂存的事件类型注册）→ bridge.syncRegistrations
用户 onEnable：
    ecs.forPlugin(this).scan("包")     → 六路 AnnotatedClassScanner → register*（单个类失败只记日志）
    ecs.forPlugin(this).loadGameObjects("前缀") → loader.loadClasspath → engine.loadDefinition（逐条回溯）
    ecs.start()
        → scheduler.initialScan()      ← 存量快照：全部实体+方块实体走 enter 管线（建 diff 基线）
        → bridge.activate()            ← active=true + 按需补注册监听
        → scheduler.start(plugin)      ← Nukkit scheduleRepeatingTask(1 tick)
```

### 3.2 世界跟踪与每帧（事件驱动 + 低频对账 + 主循环）

```
【即时】NukkitWorldTracker（事件，零延迟）：
    EntitySpawnEvent / PlayerJoinEvent → engine.enterNukkitEntity（幂等）
    EntityDespawnEvent                 → engine.removeByBinding
    BlockBreakEvent（ignoreCancelled） → removeByBinding(位置方块实体)——世界移除虽在其后，语义成立
    ChunkLoadEvent                     → 批量 enter 该区块实体+方块实体
    ChunkUnloadEvent                   → 批量 remove 该区块绑定
【即时】resolveCandidate 惰性收养（事件桥分发时）：
    未跟踪的 Entity/BlockEntity 候选 → 当场 enter → 同一事件立即分发给刚挂载组件
    （放置零延迟的关键：BlockPlaceEvent 触发时方块实体尚不存在，故不监听放置）
【每帧】EcsScheduler.onTick：
    每 NukkitWorldScanner.RECONCILE_INTERVAL_TICKS(100) tick → scanner.scanAll() 对账 diff
    engine.tick()：tickCount++ → 命中 interval 的 ticking 系统按 order 序执行（异常捕获记录）
```

### 3.3 enter 管线（`EcsEngine.registerInternal` → `applyDefinitions`）

```
enterBound(Object)（统一进入：建 EcsEntity（代理任意引用）→ 入 entities/byBinding）
→ 遍历全部带 selector 的定义（按加载序）：
     selector.matches（异常 → 记录跳过）
     命中 → mountDefinition：
         entity.addTags(def.tags)
         对每个 ComponentSpec：
             已挂同类 → 跳过（并集去重，首个定义生效）
             实例化 → bindHost → ParamBinder.bind → 入实体表+倒排索引 → onMount()
             onMount 异常 → 回滚该组件（出表出索引，不留半挂载，不影响其他组件）
```

### 3.4 spawn / spawnBound（`EcsEngine`，一次调用 = 一个实体）

```
spawn(name, pos)：
  def.spawner().spawn(ctx)（加载时已实例化注入的生成器）
      返回任意产物 → enterBound(产物)（null 即虚拟；产物 = 实体代理引用）
  → entity.recordSpawn(SpawnRecord(defName, def.despawner()))
  → mountDefinition(entity, def)   ← 本定义组件必挂（不要求 selector 命中）+ tags

spawnBound(name, target)：
  调用方自造产物（复制 level 目录/加载 level 等重 IO）→ enterBound(target)
  → recordSpawn → mountDefinition（不执行 spawn 钩子；despawn 照定义配置生效）
```

> 生成器只生成其对应的一个产物；多数量由调用方循环多次 `spawn`（不设引擎批量 API）。
> 生成器返回的 Entity/BlockEntity 会即时触发 Nukkit 事件被跟踪，`enterBound` 幂等保证不重复。

### 3.5 事件分发（`EcsEventBridge.dispatch`，主线程同步）

```
① resolversFor(触发类型)：全部声明类型 isAssignableFrom 触发类型的解析器（宽匹配）
② 【系统处理器，先执行】systemEventRegistry.handlersFor(触发类型)（已按 (order, 注册序) 排序）：
        按 order 组推进；组内：ignoreCancelled（Cancellable.isCancelled）
        → method.invoke(系统实例, event)（异常记录隔离且不截断；条件过滤=方法内首行守卫）
        组边界：本组任一 truncate 声明者正常返回 → 更低 order 系统跳过 + 返回截断标记
    ⚠ 必须在「resolvers.isEmpty() → return」早退之前执行——系统订阅无需解析器
    ⚠ 系统层截断 → ②' 组件分发整体跳过（含宿主解析与惰性收养）
③ extractHosts(event)（异常记录跳过）→ 逐候选 engine.resolveCandidate：
        Entity/BlockEntity → byNukkit 查；Position/Block → getLevelBlockEntity() 再查；EcsEntity → 直接采用
        未跟踪且合法 → 惰性收养（当场 enter，同一事件立即生效）
        候选非 null 但换算失败 → EcsLog.warning（含解析器与候选类型，链路异常可见化）
    同事件同实体 IdentityHashMap 去重
④ handlersFor(触发类型)（已按 (order, 注册序) 排序）按 order 组推进；组内逐个：
        entity.get(handler.componentClass) == null → 跳过（挂载即订阅）
⑤ ignoreCancelled（Cancellable.isCancelled）
    method.invoke（异常记录，不中断其余 handler 且不截断；条件过滤=方法内首行守卫）
    组边界：本组任一 truncate 声明者正常返回 → 同实体更低 order 跳过（其他宿主不受影响）
```

> **顺序与截断设计依据**（调研结论，详见 README 第五节契约表）：
> Unity 经典 `[DefaultExecutionOrder]` / DOTS `[UpdateBefore/After]` / Bevy `.before/.after`
> 三家一致铁律——「平级/无约束 = 非确定，禁止依赖」。本框架单线程顺序分发，因此把
> 平级也做成确定（注册序）并文档化为契约，可依赖性强于 Unity；截断采用 jframe_listener
> `exclusive` 的事件级垄断语义（组边界检查，平级全执行），未引入拓扑排序（那是并行
> 调度器的问题域）。`EcsSystem.priority` 已更名 `order()` 与事件侧词汇统一。

> 监听注册（`syncRegistrations`）：组件订阅的事件类型需存在宽匹配解析器；
> **系统订阅的事件类型有订阅即注册**（全局处理器无需宿主解析）。

### 3.6 remove / stop（销毁通知驱动的 despawn）

```
removeEntity(entity, fireDespawn)【destroy / removeByBinding 传 true；removeAll(stop) 传 false】
  → 出表出索引（byBinding 单引用一次清理）
  → 逆序 onUnmount 全部组件（单个异常只记录）
  → fireDespawn 且 SpawnRecord 非 null 且 despawner 非 null：
        despawner.despawn(new DespawnContext(defName, entity.binding(), engine))
        （产物=代理引用原样传回；异常只记录；回调内再 destroy 已 no-op）
  → invalidate()（回调期间 binding/tags 仍可读）
```

> **触发边界**：显式 `destroy()` 与世界侧销毁通知（tracker：EntityDespawn/BlockBreak/ChunkUnload
> → `removeByBinding`）触发；`stop()`（removeAll）不触发——产物随插件生命周期存续；
> 引擎未跟踪的销毁事件（LevelUnload 等）由用户在 component 加 `@OnEcsEvent` 手动处理。

### 3.7 stop（`EcsAPI.stop`，由 `JFrameMain.onDisable` 最先调用）

```
scheduler.stop（cancel 任务）→ bridge.deactivate（active=false，已注册监听变空操作）
→ engine.removeAll（逐实体逆序 onUnmount，清全部表/索引；不触发 despawn）
```

---

## 4. 类逐一剖析

### 包布局总览（按职责分层，根级只留 API 门面）

```
io.github.JiangHu.jframe.ecs
├── EcsAPI / EcsPluginScope      # 根级：API 门面对（scope 与 API 包私有互调，同包零成本）
├── annotation/                  # 7 个声明注解（@EcsComponent/@OnEcsEvent/@EcsSpawner/@EcsDespawner/...）
├── config/EcsSpringConfig       # Spring 装配入口（jframe_main import 此类，勿动包名）
├── exception/EcsConfigException # 全模块共用校验异常
├── engine/                      # EcsEngine / EcsScheduler / EcsLog
├── entity/                      # EcsEntity / Component / ComponentBase
├── system/                      # EcsSystem / EcsEngineAware
├── gameobject/                  # GameObjectDefinition / GameObjectLoader
├── spawner/                     # Spawner / SpawnContext（契约）+ BuiltinSpawners
├── despawner/                   # Despawner / DespawnContext（与 spawner 对称）
├── selector/                    # EntitySelector（契约）+ BuiltinSelectors
├── resolver/                    # EcsEventResolver（契约）+ BuiltinResolvers
├── event/EcsEventBridge         # 事件桥
├── world/                       # NukkitWorldTracker / NukkitWorldScanner
└── registry/                    # 6 注册表 + EventHandlerInfo/HandlerValidation + ParamBinder（实例化注入）
```

> 契约与内置同包（selector/spawner/resolver 模式）；registry 整包保持不拆（`HandlerValidation`/
> `requireNoArgConstructor` 包私有内聚）。跨包必需的引擎专用成员已升 public 并标注「仅引擎调用」。

### 根级（API 门面）

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`EcsAPI`](EcsAPI.java) | `plugin`（bindPlugin 幂等）、`started` | 门面；构造时 `Builtin*.registerAll`（保留字预占） |
| [`EcsPluginScope`](EcsPluginScope.java) | `classLoader` | 六路扫描（单个类失败记日志不中断）；注解 value 空串回退类简单名 |

### engine 包

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`EcsEngine`](engine/EcsEngine.java) | `entities: Map<Long,EcsEntity>`；`byBinding: IdentityHashMap<Object,EcsEntity>`（任意代理引用，引用相等）；`definitions: LinkedHashMap`（加载序=去重优先级）；`componentIndex: Map<Class,Map<Long,Component>>`；`tickingSystems`（按 order 排序）；`nextId`/`tickCount` | 全部管线入口；`enterBound(Object)` 统一进入（幂等）；`removeEntity(e, fireDespawn)` 含 despawn 回调；`smallestIndex` 优化 query 从最小集合起交集 |
| [`EcsScheduler`](engine/EcsScheduler.java) | `task: TaskHandler` | 先 diff 后 tick（新实体当帧可见） |
| [`EcsLog`](engine/EcsLog.java) | — | 包装 `JFrameLog`，无 Server 时降级 stderr（单测可跑错误路径）；包拆分后升 public |

### entity 包

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`EcsEntity`](entity/EcsEntity.java) | `binding: Object`（被代理的游戏内容，可空=虚拟）；`components: LinkedHashMap<Class,Component>`（挂载序，卸载逆序依据）；`tags: LinkedHashSet`；`spawnRecord`（仅 spawn/spawnBound 填写） | `<T> T binding()` 无参赋值目标推断强转（不符抛 CCE）+ `isVirtual()`；`binding/tags` 销毁后仍可读；`get/has` 按组件类精确匹配；`destroy()` 委托引擎；引擎专用成员（构造器/putComponent/recordSpawn 等）已升 public 标注「仅引擎调用」 |
| [`Component`](entity/Component.java) / [`ComponentBase`](entity/ComponentBase.java) | `host`（transient，引擎注入） | 接口要求 `bindHost`/`host`；基类实现存取 |

### spawner / despawner 包（产物契约）

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`Spawner`](spawner/Spawner.java) / [`Despawner`](despawner/Despawner.java) + [`SpawnContext`](spawner/SpawnContext.java) / [`DespawnContext`](despawner/DespawnContext.java) | — | 产物契约：spawn 返回任意对象（=代理引用，null=虚拟）；despawn 收 `product()`（=binding）做世界侧回收；两上下文均不含实体参数 |

### gameobject 包

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`GameObjectLoader`](gameobject/GameObjectLoader.java) | 无状态 | `load(String/File)` 与 `loadClasspath`（`PathMatchingResourcePatternResolver` 解析 `classpath*:前缀` 递归 `*.yml`，按 URL 去重）；`parseRef` 统一解析 `{use, params}`（selector/spawn/despawn 三节点共用）；`parseComponents` 对组件类做 `ParamBinder.validate` |
| [`GameObjectDefinition`](gameobject/GameObjectDefinition.java) | record：selector/spawner/despawner 为**已实例化注入**的实例；components 为 `ComponentSpec(id, params)` 规格 | spawner/selector/despawner 每条定义独立实例，组件每挂载一次实例化 |

### event / world 包

| 类 | 关键内部状态 | 说明 |
|----|-------------|------|
| [`EcsEventBridge`](event/EcsEventBridge.java) | `registered/pending`（插件绑定前暂存，模式同 EventEngine）；`active` | `syncRegistrations`：组件类型需宽匹配 resolver、系统类型有订阅即注册；分发先系统后组件，固定 LOWEST |
| [`NukkitWorldScanner`](world/NukkitWorldScanner.java) | `snapshot: identity set` | `scanAll` 在无 Server 时空操作（单测友好） |

### system 包

| 类 | 说明 |
|----|------|
| [`EcsEngineAware`](system/EcsEngineAware.java) | 可选接口：`addSystem` 时先回调 `bindEngine(this)`，系统事件处理器内做全局 query 用 |

### registry 包（6 注册表 + 实例化注入）

| 类 | 说明 |
|----|------|
| [`ComponentRegistry`](registry/ComponentRegistry.java) | 注册期全量校验：id 查重、无参构造、`@OnEcsEvent` 方法签名（共享 [`HandlerValidation`](registry/HandlerValidation.java)）；维护 `handlersByEvent` |
| [`EventHandlerInfo`](registry/EventHandlerInfo.java) | 单个事件处理方法元数据（方法级过滤由方法内首行守卫承担） |
| `Selector/Spawner/Despawner/ResolverRegistry` | 名→类 + 查重；前三者 `create(name, params)` = 实例化 + ParamBinder 注入（Spawner/Despawner 同构，两命名空间）；ResolverRegistry 实例化一次缓存并按 `eventType()` 建索引，`resolversFor(触发类型)` 做宽匹配 |
| [`SystemEventRegistry`](registry/SystemEventRegistry.java) | 系统实例上的 `@OnEcsEvent` 全局处理器：签名校验共享 `HandlerValidation`；订阅的事件类型无需解析器 |
| [`ParamBinder`](registry/ParamBinder.java) | `CLASS_CACHE`（含 CACHE_MISS 哨兵）；`collectFields`（子类优先、排除 static/final/transient/synthetic）；`coerce`（Number 互转/字符串解析/枚举按名，失败抛 EcsConfigException）；`resolveClass`（完整名 → 简单名试 `cn.nukkit.entity.*`/`cn.nukkit.blockentity.*`）；`validate` 供加载期静态校验 |

### 内置包（契约同包的 Builtin*）

| 类 | 内容 |
|----|------|
| [`BuiltinSelectors`](selector/BuiltinSelectors.java) | `tag` / `entity-class` / `blockentity-class`（`type` 字段懒解析缓存，虚拟实体不触发解析） |
| [`BuiltinSpawners`](spawner/BuiltinSpawners.java) | `virtual`（null）/ `entity`（`Entity.createEntity(String|int, Position)` + nameTag + spawnToAll）/ `block`（`Block.get(id, meta, pos)` → `setBlock` → 返回产生的 BlockEntity） |
| [`BuiltinResolvers`](resolver/BuiltinResolvers.java) | `entity-events`（声明 `EntityEvent` 父类型，宽匹配）/ `block-interact`（`getBlock().getLevelBlockEntity()`） |

---

## 5. 线程安全模型

- **全部状态在 Nukkit 主线程访问**：diff 扫描、tick、事件分发、用户 API 都在主线程（或插件 onEnable 即主线程）。
- 引擎内部用普通 `HashMap`/`LinkedHashMap`（无锁），**不要从异步线程调用**引擎/API。若需异步处理，先在异步线程收集数据，主线程（如 `Server.getScheduler().scheduleTask`）再操作 ECS。
- 注册表的 `synchronized` 只为防御 Spring 刷新期与 bindPlugin 的时序，不构成异步使用许可。
- `EcsEventBridge.active` 为 `volatile`（stop 与事件回调可能竞态，读单字段安全）。

---

## 6. 生命周期管理

| 时机 | 动作 |
|------|------|
| Spring 容器刷新 | `EcsAPI` 构造 → 内置预注册；`JFrameMain` 构造容器（ECS SpringConfig 已在 MainSpringConfig `@Import`） |
| `JFrameMain.onEnable` | `bindPlugin` → 全部 `PluginAware`（含 EcsAPI）绑定；`registerServices` 注册 `EcsAPI` |
| 组件/解析器注册 | 若已绑定插件 → `bridge.syncRegistrations` 增补 Nukkit 监听；未绑定 → 暂存待刷 |
| `start()` | 存量扫描 → 事件桥激活 → 主循环；重复 start 抛 `IllegalStateException` |
| `stop()` / `JFrameMain.onDisable` | ECS **最先**停止（在箱子视图/线程池清理之前）：取消任务 → 桥失活 → 全部组件逆序 `onUnmount` |

> `stop()` 不反注册 Nukkit HandlerList（PluginManager 无细粒度反注册），靠 `active=false` 使监听变空操作——模块重载（framing reload）时旧监听残留但无害。

---

## 7. 扩展点

| 想扩展什么 | 怎么做 |
|-----------|--------|
| 新的筛选条件 | 实现 `EntitySelector` + `@EcsSelector("名")`（参数即字段），或手动 `registerSelector`；组合逻辑（and/or）写成新的参数化选择器类 |
| 新的生产方式 | 实现 `Spawner` + `@EcsSpawner("名")`；返回任意产物对象（null=虚拟） |
| 新的回收方式（level 卸载/文件清理） | 实现 `Despawner` + `@EcsDespawner("名")`；`ctx.product()` instanceof 守卫后做世界侧回收；定义文件配 `despawn: { use, params }` |
| 重 IO 自备产物 | `ecs.spawnBound(定义名, 产物)`：调用方造完产物直交引擎默认过程（不执行 spawn 钩子） |
| 新的事件来源（任意对象） | 实现 `EcsEventResolver` + `@EcsResolver("名")`：`eventType()` 可声明父类型，`extractHosts` 返回多槽位候选 |
| 方法级事件条件 | 处理器方法内首行 `if` 守卫（直接读 `this` 字段；无外部机制——filter/SpEL 均已移除） |
| 引擎未覆盖的销毁事件（LevelUnload 等） | 在 component 里加 `@OnEcsEvent` 监听方法，回调内手动回收或 `host().destroy()`（触发 despawn） |
| 每帧逻辑 | 实现 `EcsSystem` + `@EcsSystemAnno`，`query()` 取目标实体 |
| 组件挂载后持久化 | 边界外：当前不做落盘，后续可经 jframe_db / jframe_file 扩展（见 §8） |

---

## 8. 已知限制与陷阱

1. **同组件 id 多定义命中只挂首个**（按定义加载顺序，`entity.has(type)` 去重）——差异化配置需注册不同组件 id。
2. **状态变化不自动重评选择器**（决策 3）：筛选条件依赖运行时状态时需手动 `refresh(entity)`；响应式重评为边界外。
3. **事件监听建议具体类型**：Nukkit 按事件类型触发 HandlerList，父类型处理器不保证捕获子类事件（解析器侧已宽匹配，组件侧应监听具体类型）。
4. **定义名冲突一律报错**：resources 与数据目录同名不合并、不覆盖。
5. **`entity` 生成器参数**：`networkId`（int）或 `type`（String，`Entity.createEntity` 的标识名）二选一；`block` 生成器只支持数字 id（`blockId`），不支持方块名。
6. **无异步 system**：仅主线程同步 tick 与事件分发。
7. **`getLevelBlockEntity` 依赖 Position 带 level**：自定义解析器返回游离 Position 时需自行保证（`Block`/世界来源的候选天然满足）。
8. **与 jframe_listener 并存**：同一 Nukkit 事件会被两条管线各自消费一次，属预期；单管线适配为边界外。
9. **`binding()` 是擦除强转**：`var x = e.binding()` 推断为 Object 不抛错——赋值目标必须写具体类型；判型一律 instanceof 模式匹配。
10. **Level/Server 等产物无 tracker 销毁通知**：游戏侧来源只在 tracker 事件覆盖范围（Entity/BlockEntity/区块）内自动触发 despawn；其余销毁事件（LevelUnload 等）由用户在 component 加 `@OnEcsEvent` 手动处理（§7）；despawn 回调收到的产物可能已失效（如 closed 实体），需 instanceof/状态守卫。

---

## 9. 修改指南（改代码前必读）

| 改动 | 影响面 | 注意 |
|------|--------|------|
| 挂载顺序/去重规则 | `EcsEngine.mountDefinition` | 顺序同时决定 `EcsEntity.components` 的 LinkedHashMap 序 = **卸载逆序依据**，别只改一处 |
| onMount 回滚语义 | `EcsEngine.mountComponent` | 回滚不得触发 onUnmount（挂载未完成），测试 `EngineMountTest.onMountRollback` 锁定该行为 |
| removeEntity 时序（despawn 回调） | `EcsEngine.removeEntity(e, fireDespawn)` | 固定序：出表 → 逆序 onUnmount → despawn 回调 → invalidate；回调期间 binding/tags 必须可读；改时序先在 `DespawnTest` 补用例 |
| 事件注册策略 | `EcsEventBridge.syncRegistrations` | 注册类型 = 「组件 handler 类型 ∩ 宽匹配 resolver」∪ 系统订阅类型；无覆盖订阅进 `unresolvedWarned` 告警（按类型一次性）；改宽匹配去改 `ResolverRegistry.resolversFor`，两边共用 |
| ParamBinder 排除规则 | `collectFields` | 新增排除项（如新修饰符）会让既有 YAML 参数变"未知字段"报错——属于破坏性变更 |
| 世界跟踪 | `NukkitWorldTracker` / `resolveCandidate` | tracker 事件与惰性收养都走幂等 enter/remove——新增跟踪事件不得绕过这两个入口；`resolveCandidate` 的收养是分发副作用，单测与实机都要覆盖 |
| 新增内置注册名 | 对应 `Builtin*` + `EcsAPI` 构造 | 必须同步登记保留字（§README 七），并在 `BuiltinSelectorTest.reservedNamesRejected` 补断言 |
| Spring 装配 | `ecs-spring.xml` | 保持单向 DAG（注册表→loader/engine→桥/跟踪器→门面）；EcsAPI 构造依赖 despawnerRegistry Bean 已就绪 |

**验证基线**：`mvn -pl jframe_ecs test`（85 用例）+ 全量 `mvn compile`。任何管线语义改动都应先在 `EngineMountTest` / `EventBridgeTest` / `DespawnTest` 补用例再动实现。
