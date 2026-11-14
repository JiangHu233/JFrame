# ECS 实体组件系统 (Entity-Component-System)

> 基于 Nukkit + Spring 的轻量 ECS 引擎 —— 把"游戏对象 = 实体 + 可动态挂载的组件"模型搬到 Minecraft 服务器。
>
> `EcsEntity` 是 gameobject 对游戏内容的<b>代理</b>：单一 Object 万能引用可指向**任意对象**（`Entity`、`BlockEntity`、`Level`、`Server`、资源 POJO），引用为空即纯虚拟 gameobject。
>
> 通过 YAML 定义 GameObject（选择器 + 组件 + 可选现场生成/回收），**已经存在的和后续出现的实体都能自动识别挂载**；Nukkit 事件经四级过滤落到宿主组件的 `@OnEcsEvent` 方法上。

---

## 📑 目录

- [一、这个框架是干什么的？](#一这个框架是干什么的)
- [二、核心概念](#二核心概念)
- [三、快速上手：魔法箱子](#三快速上手魔法箱子)
- [四、GameObject 定义文件](#四gameobject-定义文件)
- [五、事件桥与四级过滤](#五事件桥与四级过滤)
- [六、API 详解](#六api-详解)
- [七、内置注册名与保留字](#七内置注册名与保留字)
- [八、测试](#八测试)

---

## 一、这个框架是干什么的？

把"给某些实体附加行为"从散落的监听器/if-else，变成**声明式的绑定规则**：

- **指定所有的箱子**：一条内置类型选择器搞定（`blockentity-class`），零自定义类
- **按条件筛选**：注册自定义选择器类，YAML 里 `use: 名字 + params: 参数表` 引用
- **存量 + 后放都能识别**：定义加载时回溯扫描全部已跟踪实体；之后新生成/新放置/区块加载出现的实体在进入时自动评估
- **组件生命周期**：`onMount()` / `onUnmount()` 可重写钩子；挂载期间任意时刻 `host()` 取回宿主
- **属性即子类字段**：组件/选择器/生成器/回收器的配置参数就是类字段，YAML `params` 按字段名反射注入
- **任意对象代理**：gameobject 用单一 Object 引用代理游戏内容（Entity/BlockEntity/Level/Server/POJO 均可），无参泛型 getter 按赋值目标强转
- **生成/回收成对**：`spawn`（现场生产任意产物）与 `despawn`（销毁通知驱动的世界侧回收——level 卸载/文件 IO 的落点）同一套"注册类 + 参数表"配置
- **事件到组件**：玩家点击箱子、库存打开等事件经宿主解析器落到组件方法（挂载即订阅、卸载即退订）
- **系统 tick**：`EcsSystem` 每 N tick 执行，`query()` 走倒排索引不遍历全实体

### 与经典写法的对比

| 维度 | 经典监听器写法 | 本框架 |
|------|---------------|--------|
| 给某类实体附加行为 | 手写 Listener + `instanceof` 过滤 | YAML 定义一条绑定规则 |
| 识别"后面放的" | 自己监听生成/放置/区块事件 | 引擎世界 diff 自动处理 |
| 状态存放 | 监听器里的 Map<实体, 状态> | 组件字段（每实体一实例） |
| 事件分发给"这个实体上的逻辑" | 手动查 Map 再调用 | `@OnEcsEvent` 自动路由到宿主组件 |
| 行为卸载 | 手动清 Map、注监听 | 组件 `onUnmount()` 自动清理 |

---

## 二、核心概念

### 六类注册对象 —— 全部同构："一个注册过的类 + 一个参数表"

| 对象 | 接口 | 注解 | 职责 |
|------|------|------|------|
| 组件 | [`Component`](entity/Component.java) | [`@EcsComponent("id")`](annotation/EcsComponent.java) | 属性 + 行为载体，每实体一实例，`params` 挂载时注入字段 |
| 选择器 | [`EntitySelector`](selector/EntitySelector.java) | [`@EcsSelector("name")`](annotation/EcsSelector.java) | 判定实体是否命中定义，`params` 加载时注入字段 |
| 生成器 | [`Spawner`](spawner/Spawner.java) | [`@EcsSpawner("name")`](annotation/EcsSpawner.java) | 现场产出任意对象（产物=代理引用；null=虚拟），`params` 加载时注入 |
| 回收器 | [`Despawner`](despawner/Despawner.java) | [`@EcsDespawner("name")`](annotation/EcsDespawner.java) | gameobject 销毁时的世界侧回收（卸载 level/删文件），`params` 加载时注入 |
| 宿主解析器 | [`EcsEventResolver`](resolver/EcsEventResolver.java) | [`@EcsResolver("name")`](annotation/EcsResolver.java) | 从任意 Nukkit 事件提取宿主候选（解决"歪门邪道对象"） |
| 系统 | [`EcsSystem`](system/EcsSystem.java) | [`@EcsSystemAnno`](annotation/EcsSystemAnno.java) | 周期逻辑（**tick 可选**：不 override 即纯事件系统，零轮询） |

> 参数表（`params`）的 key 与类字段**按名匹配**，沿继承链收集非 `static`/`final`/`transient` 字段；未出现的字段保留代码初始值（可选参数语义）。

### 代理绑定（统一 Object 万能引用）

[`EcsEntity`](entity/EcsEntity.java) 不按类型枚举绑定字段，只持有一个代理引用（可空 = 纯虚拟 gameobject）：

```java
Level lvl = entity.binding();                          // 无参自动泛型识别：按赋值目标强转
if (entity.binding() instanceof BlockEntity chest) {}  // 判型用 instanceof 模式匹配
boolean virtual = entity.isVirtual();                  // 引用为空 = 纯虚拟（波次管理器等）
```

| 代理目标 | 典型场景 |
|----------|---------|
| `cn.nukkit.entity.Entity` | 生物、掉落物、玩家（tracker 自动跟踪） |
| `cn.nukkit.blockentity.BlockEntity` | **箱子**、告示牌、熔炉（tracker 自动跟踪） |
| `Level` / `Server` / 任意 POJO | 文件级资源、跨服对象等——**无自动跟踪**，经 `spawn`/`spawnBound`/`enterBound` 进入，销毁通知未覆盖的事件在 component 里加 `@OnEcsEvent` 手动处理 |
| `null` | 纯虚拟 gameobject |

### 引擎自动跟踪的世界对象（事件驱动，零延迟）

[`NukkitWorldTracker`](world/NukkitWorldTracker.java) 以事件即时跟踪世界对象：实体（玩家/生物/掉落物）经
`EntitySpawnEvent`/`EntityDespawnEvent`/`PlayerJoinEvent`；方块实体经 `BlockBreakEvent` 即时卸载、
`ChunkLoadEvent`/`ChunkUnloadEvent` 批量进出；**放置不延迟**——未跟踪的方块实体在首次被事件触及时由
引擎<b>惰性收养</b>（`resolveCandidate` 当场挂载，同一事件立即生效）。[`NukkitWorldScanner`](world/NukkitWorldScanner.java)
降级为每 100 tick 的低频对账，兜底绕过事件的路径。

---

## 三、快速上手：魔法箱子

场景：所有箱子（存量 + 后放）自动挂告警组件；点击/打开箱子触发组件事件；出生点附近强化；夜间现场生成幽灵群；波次管理器为纯虚拟对象。

### 1. 写组件（属性即字段，`host()` 随时识别宿主）

```java
@EcsComponent("magic-alarm")
public class MagicAlarm extends ComponentBase {
    private String sound = "random.orb";   // 可被 params.sound 覆盖
    private double radius = 5.0;
    private double threshold = 4.0;        // 重击判定阈值

    @Override
    public void onMount() {
        if (host().binding() instanceof BlockEntity chest) {   // 挂载时：初始效果（代理引用判型）
            // ...
        }
    }

    @Override
    public void onUnmount() {
        // 箱子被破坏 / 引擎 stop：清理效果
    }

    @OnEcsEvent   // 玩家点击箱子 → 内置 block-interact 解析器换算宿主 → 本方法收到调用
    public void onInteract(PlayerInteractEvent event) {
        // host() 即本组件挂载的箱子实体
    }

    @OnEcsEvent
    public void onHeavyHit(EntityDamageEvent event) {
        if (event.getDamage() <= threshold) {
            return;   // 方法级过滤 = 首行守卫：直接读 this 字段，无外部机制
        }
    }
}
```

### 2. 写选择器 / 生成器 / 回收器 / 解析器（都是"注册类 + 参数表"）

```java
@EcsSelector("near-spawn")
public class NearSpawnSelector implements EntitySelector {
    private double distance = 32;    // params.distance 注入
    private String tag;              // 未配置保留 null
    @Override
    public boolean matches(EcsEntity e) { /* e.binding() instanceof ... 判型后取位置 */ }
}

@EcsSpawner("ghost-pack")
public class GhostPackSpawner implements Spawner {
    private int count = 3;           // params.count 注入
    @Override
    public Object spawn(SpawnContext ctx) { /* 生成 count 只幽灵 */ return leader; }
}

@EcsDespawner("arena-recycle")
public class ArenaRecycleDespawner implements Despawner {
    private boolean broadcast = true;   // despawn.params.broadcast 注入
    @Override
    public void despawn(DespawnContext ctx) {
        if (!(ctx.product() instanceof ArenaRef arena)) return;   // 首行守卫
        Files.deleteIfExists(arena.markerPath());                 // 回收文件 IO（如卸载 level）
    }
}

// 宿主解析器：处理"歪门邪道对象"（库存持有者等）
@EcsResolver("chest-holder")
public class ChestHolderResolver implements EcsEventResolver {
    @Override public Class<? extends Event> eventType() { return InventoryOpenEvent.class; }
    @Override public List<Object> extractHosts(InventoryOpenEvent event) {
        return List.ofNullable(event.getInventory().getHolder());
    }
}
```

### 3. 写系统

```java
@EcsSystemAnno
public class MagicChestSystem implements EcsSystem {
    @Override public int interval() { return 20; }   // 每 20 tick

    @Override
    public void tick(EcsEngine engine) {
        for (EcsEntity chest : engine.query(MagicAlarm.class)) { /* 吸附掉落物 */ }
        for (EcsEntity mgr : engine.query(WaveState.class)) {
            WaveState st = mgr.get(WaveState.class);   // 取已挂实例，直接读写字段
        }
    }
}
```

### 4. 写定义文件 `src/main/resources/gameobjects/magic.yml`（打进插件 jar）

```yaml
all-chests:                                           # 所有箱子：内置类型选择器，零自定义类
  selector: { use: blockentity-class, params: { type: BlockEntityChest } }
  tags: [chest]
  components:
    - id: magic-alarm
      params: { sound: "random.orb", radius: 8.0 }

guardian-chests:                                      # 出生点附近：自定义选择器 + 参数
  selector: { use: near-spawn, params: { distance: 64.0, tag: chest } }
  components: [ { id: magic-alarm, params: { radius: 12.0 } } ]

night-ghosts:                                         # 现场生成：自定义生成器 + 参数
  tags: [wave]
  spawn: { use: ghost-pack, params: { count: 5 } }

arena:                                                # 生成 + 回收对：产物为任意对象（如 Level）
  spawn: { use: arena-template, params: { name: "wall-arena" } }
  despawn: { use: arena-recycle, params: { broadcast: true } }

wave-manager:                                         # 纯虚拟 gameobject
  spawn: { use: virtual }
  components: [ { id: wave-state, params: { wave: 1, mobsLeft: 10 } } ]
```

> 注：同一实体命中多条定义且组件 id 相同时只挂首个（按定义加载顺序）——差异化配置请注册为不同组件 id（如 `guardian-alarm`）。

### 5. 启动（4 行）

```java
EcsAPI ecs = JFrameMain.getInstance().getEcsAPI();
ecs.forPlugin(this).scan("com.mygame.ecs");                       // 扫描六类注解
ecs.forPlugin(this).loadGameObjects("gameobjects");               // 扫描 jar resources/gameobjects/*.yml
ecs.start();                                                      // 存量回溯 + diff 监听 + 事件桥 + System tick
EcsEntity mgr = ecs.spawn("wave-manager", null);                  // 现场生成虚拟对象
```

### 运行时效果

- `start()` 瞬间：世界里已存在的 300 个箱子全部挂上 `MagicAlarm`（**存量识别**）
- 之后玩家新放/砸掉箱子，下一 tick 自动挂载 / `onUnmount`（**后放识别**）
- 点击魔法箱子 → `block-interact` 解析 → `onInteract`；打开箱子 → `chest-holder` 解析 → `onOpen`；伤害 > threshold 才进 `onHeavyHit`
- 每晚 `ecs.spawn("night-ghosts", pos)` 生成幽灵群，全灭自动卸载
- `ecs.spawn("arena", null)` 写 marker 文件；`destroy()` / 幽灵死亡等销毁通知触发 despawn 回调删文件（引擎 stop 不触发）
- 插件停用 → `EcsAPI.stop()` → 全部组件依序 `onUnmount`、事件监听注销

---

## 四、GameObject 定义文件

一个 YAML 文件可声明多条定义，根为 `名称 → 定义` 的 map。三类来源：**classpath 扫描**（主推，含 jar 内资源）、`File`（单文件或目录递归 `*.yml`，供服主热改）、YAML 字符串。

### 字段参考

```yaml
定义名:                        # 全局唯一；重名一律报错（不做覆盖合并）
  selector:                    # 可选。选择器 = 注册名 + 参数表
    use: blockentity-class     # 已注册选择器名
    params: { type: BlockEntityChest }
  tags: [chest, loot]          # 可选。命中/spawn 时打到实体上
  components:                  # 可选。组件列表，id 即注册名
    - id: magic-alarm
      params: { sound: "note.bass", radius: 8.0 }
  spawn:                       # 可选。生成器 = 注册名 + 参数表；省略 → 纯绑定型
    use: block                 #   产物 = 任意对象（成为实体代理引用）；null → 纯虚拟
    params: { blockId: 54 }
  despawn:                     # 可选。回收器 = 注册名 + 参数表（销毁通知驱动）
    use: arena-recycle         #   触发：destroy() / 世界侧销毁通知；不触发：引擎 stop
    params: { broadcast: true }
```

### despawn 触发语义（销毁通知驱动）

| 销毁来源 | 路径 | despawn 回调 |
|----------|------|--------------|
| 引擎显式销毁 | `EcsEntity.destroy()`（回收 level 的主入口） | ✅ |
| 游戏自身（已跟踪） | 生物死亡 / 方块破坏 / 区块卸载 → tracker → `removeByBinding` | ✅ |
| 引擎 stop | `EcsAPI.stop()` → removeAll | ❌（产物存续） |
| 引擎未跟踪的销毁事件（LevelUnload 等） | — | 用户在 component 里加 `@OnEcsEvent` 监听对应事件手动处理 |

> 回调时机：全部组件逆序 `onUnmount` **之后**；`DespawnContext.product()` 传回产物引用（组件表已清，产物判定用 instanceof 守卫）。非 spawn/spawnBound 出生的实体（tracker 收养/手动注册）永不触发。

### 类型强转（params → 字段）

| YAML 值 | 目标字段类型 | 规则 |
|---------|-------------|------|
| 数字/布尔/字符串 | 同族类型 | `Number` 之间互转（intValue/longValue/...） |
| `"true"` / `"42"` | `boolean` / `int` 等 | 字符串解析 |
| `"RED"` | 枚举 | `Enum.valueOf` 按名 |
| 列表 / 映射 | `List` / `Map` | 直接赋值 |
| 任意 | 不兼容类型 | 抛 [`EcsConfigException`](exception/EcsConfigException.java) |

### 加载期校验（fail fast）

未知组件 id / 未知选择器名 / 未知生成器名 / params 未知字段名 / 类型不可强转 / 定义名冲突 → 拒绝整个加载并指明出处。选择器与生成器在**加载时实例化并注入一次**（每条定义引用独立实例）；组件在**每次挂载**时实例化注入。

---

## 五、事件桥与内联守卫分发

[`EcsEventBridge`](event/EcsEventBridge.java) 是自包含 Nukkit Listener（零依赖 listener 模块），分发链路：

```
Nukkit 事件
  ↓ ① 事件类型级：组件订阅需「有解析器(宽匹配)」；系统订阅有订阅即可（全局处理器无需解析器）
  ↓ ①' 系统处理器（全局，按 order 升序）：事件触发即调用一次（无宿主解析），先于组件执行；
  │     声明 truncate 且正常返回 → 更低 order 系统跳过 + 整个组件层不分发
  ↓ ② 宿主级：解析器提取候选（Entity/BlockEntity/Position/Block/EcsEntity）→ 查绑定索引
  │     （未跟踪候选被惰性收养——当场挂载，同一事件立即生效）；同事件同实体去重
  ↓ ③ 组件级：仅该实体当前已挂组件中声明了匹配事件类型的方法（挂载即订阅、卸载即退订），
  │     按 order 升序执行；声明截断 → 同实体更低 order 跳过（其他宿主不受影响）
  ↓ ④ 方法级：ignoreCancelled（条件过滤由方法内首行守卫承担）
组件方法（host() 可用）
```

### 组件事件 vs 系统事件

| 维度 | 组件事件（`@OnEcsEvent` 上组件方法） | 系统事件（`@OnEcsEvent` 上系统类方法） |
|------|-----------------------------------|--------------------------------------|
| 路由 | 宿主解析 → 已挂组件 | 全局处理器：事件触发即调用一次，**跳过宿主级/组件级** |
| 解析器要求 | 订阅的事件类型需有宽匹配解析器 | **无要求**——无解析器的事件（如 PlayerJoinEvent）也能订阅 |
| 方法级条件 | 方法内首行守卫（直接读 `this` 字段） | 同左（无宿主，用事件自身字段） |
| `ignoreCancelled` | ✅ | ✅ |
| engine 访问 | 组件经 `host()` 访问实体 | 实现 [`EcsEngineAware`](system/EcsEngineAware.java) 注册时绑定引擎，处理器内 `query()` 做全局反应 |

> 分发顺序**先系统（全局）后组件（局部）**：系统代表全局观察者/前置加工，系统若取消事件，
> 后续带 `ignoreCancelled` 的处理器可感知。方法要求（public 非静态、恰好 1 个 Event 子类参数）两侧一致。
>
> **方法级过滤 = 方法内首行守卫**（无 SpEL、无外部 filter 机制——直接读组件字段最简且最强）：
> ```java
> @OnEcsEvent(ignoreCancelled = true)
> public void onHit(EntityDamageEvent event) {
>     if (event.getDamage() <= hitThreshold) return;   // 守卫
> }
> ```

### `@OnEcsEvent` 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `ignoreCancelled` | `boolean` | `false` | 跳过已取消（`Cancellable`）事件 |
| `order` | `int` | `0` | 组内执行顺序（小者先）；**平级按注册顺序执行（文档化契约）**。建议：预处理负值 / 核心 0 / 观察者正值 |
| `truncate` | `boolean` | `false` | 下层截断声明（事件级垄断，对齐 jframe_listener `exclusive`）：系统处理器截断 → 同层更低 order 跳过 + **整个组件层不分发**（含惰性收养）；组件处理器截断 → 仅**同实体**更低 order 跳过（其他宿主不受影响）。**组边界检查**（同 order 平级仍全执行）；**正常返回后生效**（抛异常不截断）。⚠ 守卫 `return` 也算执行过 → 仅用于无条件终结型处理器；条件性垄断用 `setCancelled + ignoreCancelled` |

#### 顺序与截断契约（确定性）

1. **组间**：同一事件先系统（全局）后组件（宿主路由），固定不变
2. **组内**：系统处理器与组件处理器各自独立按 `order` 升序；平级按注册顺序（可依赖）
3. **跨宿主**：多宿主按解析器注册顺序分发（可依赖）
4. **截断与取消正交**：截断只影响 ECS 内部分发，不改变 Nukkit 下游

- 方法要求：public 非静态、恰好 1 个 `cn.nukkit.event.Event` 子类参数（参数类型即监听的事件类型）
- 建议**监听具体事件类型**（如 `EntityDamageByEntityEvent`）——Nukkit 按类型触发监听，父类型注册不保证捕获子类事件
- 单个 handler 异常只记录不影响其余；异常处理器不产生截断效果

### 性能门控（解析器不会跑在所有事件上）

1. **监听仅注册有订阅的事件类型**——未订阅类型每秒成百上千次事件与 ECS 零交集（Nukkit 只分发到已注册类型）；
2. **触发后仅运行宽匹配命中的解析器**——声明 `InventoryOpenEvent` 的不会跑在 `EntityDamageEvent` 上（接口参数为基类 `Event` 只是多态，服务父类型的全部子类）；
3. **`extractHosts` 契约为 O(1) getter**（`getEntity()`/`getHolder()`），不做扫描；
4. 解析器返回的是"该收到此事件的**宿主候选**"（通常 0~1 个，多主体可多个），不是"事件里全部对象"。

---

## 六、API 详解

### [`EcsAPI`](EcsAPI.java)（用户入口，经 `JFrameMain.getInstance().getEcsAPI()` 获取）

| 方法 | 说明 |
|------|------|
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回 [`EcsPluginScope`](EcsPluginScope.java)：`scan(包名...)` 六路注解扫描、`loadGameObjects(classpath前缀)` |
| `registerComponent(id, Class)` 等 | 手动注册六类对象（与注解扫描等价） |
| `loadGameObjects(File)` | 单文件或目录递归 `*.yml` |
| `loadGameObjects(String yaml, String desc)` | YAML 字符串 |
| `start()` / `stop()` | 启动（存量快照 + 事件桥 + 主循环）/ 停止（卸载全部组件、注销监听；不触发 despawn） |
| `spawn(定义名, Position)` | 现场生成（本定义组件必挂 + 其他匹配定义并集） |
| `spawnBound(定义名, 产物)` | 自备产物入口：调用方完成重 IO 生产后直交引擎默认过程（despawn 照定义配置生效） |
| `refresh(EcsEntity)` | 手动重评该实体全部 selector（只补挂不卸载） |
| `registerEntity(Entity/BlockEntity)` | 手动把世界对象纳入跟踪 |
| `createVirtualEntity()` | 创建游离的纯虚拟实体 |
| `query(Class<?>...)` | 倒排索引查询（同时挂有全部指定组件） |
| `entities()` / `getEntity(long)` / `definitions()` | 快照访问 |

### [`EcsEntity`](entity/EcsEntity.java)（gameobject 对游戏内容引用的代理）

| 方法 | 说明 |
|------|------|
| `binding()` | 取代理引用 — **无参自动泛型识别**：`Level lvl = e.binding();` 按赋值目标强转；类型不符抛 `ClassCastException`；虚拟返回 null 不抛（销毁后仍可读） |
| `isVirtual()` | 是否纯虚拟 gameobject（代理引用为空） |
| `get(Class<T>)` / `has(Class<?>)` | 取/测已挂组件（按组件类精确匹配）；实体销毁后抛 `IllegalStateException` |
| `find(Class<T>)` | Optional 风格取已挂组件 |
| `mount(Class, params)` / `unmount(String id)` | 实例级挂载/卸载兄弟组件（与定义挂载共用去重与回滚语义，可在回调内调用） |
| `valid()` | 实体是否有效（destroy 后 false；`onUnmount` 回调期间仍有效，兄弟查询可用） |
| `hasTag` / `addTag` / `removeTag` / `tags()` | 标签（销毁后仍可读） |
| `destroy()` | 销毁（逆序 onUnmount → despawn 回调（若有配置）→ 移出引擎） |

---

## 七、内置注册名与保留字

| 类别 | 注册名 | 参数 | 说明 |
|------|--------|------|------|
| 选择器 | `tag` | `tag` | 按标签匹配 |
| 选择器 | `entity-class` | `type` | 按 Nukkit 实体类 isInstance；简单名在 `cn.nukkit.entity.*` 下解析 |
| 选择器 | `blockentity-class` | `type` | 按方块实体类 isInstance（"所有箱子"开箱即用）；简单名在 `cn.nukkit.blockentity.*` 下解析 |
| 生成器 | `virtual` | — | 返回 null（纯虚拟） |
| 生成器 | `entity` | `networkId` 或 `type`，可选 `nameTag` | `Entity.createEntity` 生成并 `spawnToAll` |
| 生成器 | `block` | `blockId`，可选 `meta` | 放置方块并返回产生的 BlockEntity |
| 解析器 | `entity-events` | — | `EntityEvent.getEntity()`（声明父类型，宽匹配全部实体事件） |
| 解析器 | `block-interact` | — | 点击方块 → 位置 → 方块实体 |

> 内置名为保留字，自定义同名注册被拒绝。`class` 是 Java 关键字不能作字段名，类型选择器参数用 `type`。

---

## 八、测试

| 测试类 | 覆盖 |
|--------|------|
| [`EngineMountTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/engine/EngineMountTest.java) | 存量回溯 / 后放识别 / 定义后注册回溯 / refresh / 并集去重 / 回滚 / 逆序卸载 / query 交集 / spawn / 系统调度 |
| [`EventBridgeTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/event/EventBridgeTest.java) | 类型级/宿主级/组件级各验一例 / 内联守卫边界 / ignoreCancelled / 注册期校验 / 异常隔离 / 宽匹配 / 去重 / 无解析器告警 |
| [`EventOrderTruncateTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/event/EventOrderTruncateTest.java) | 组内 order 排序 / 平级注册序契约 / 组件截断（同实体粒度、平级仍执行、他宿主不受影响）/ 系统截断（事件级，组件层不分发）/ 异常不截断 / 与取消正交 / 默认值向后兼容 |
| [`SystemEventTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/event/SystemEventTest.java) | 系统订阅分发（无解析器）/ 内联守卫 / ignoreCancelled / 先系统后组件顺序 / bindEngine |
| [`GameObjectLoaderTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/gameobject/GameObjectLoaderTest.java) | 三来源加载 / 全部 fail-fast 校验（含 despawn 节点）/ 冲突检测 |
| [`ParamBinderTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/registry/ParamBinderTest.java) | 字段注入 / 类型强转 / 类名解析 |
| [`BuiltinSelectorTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/selector/BuiltinSelectorTest.java) | 内置选择器与保留字 |
| [`UnifiedBindingTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/entity/UnifiedBindingTest.java) | 代理绑定：无参泛型强转 / CCE / 虚拟 / 任意对象幂等 enterBound / 销毁后可读 |
| [`DespawnTest`](../../../../../../../test/java/io/github/JiangHu/jframe/ecs/engine/DespawnTest.java) | despawn 触发时序 / 双来源（destroy + 世界侧）/ stop 不触发 / 异常隔离 / spawnBound |

```bash
mvn -pl jframe_ecs test
```

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
