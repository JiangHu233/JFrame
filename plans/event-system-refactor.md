# 事件系统重构架构设计 v3（最终版）

> 从零重写的完整架构设计。核心变化：4 注解体系、工厂模式实例管理、跨优先级独占、多槽位身份提取。

---

## 一、设计目标

| # | 需求 | 解决方案 |
|---|------|----------|
| ❶ | 身份标识由包装类自声明 | `@KeyExtractor`（static 方法，从事件提取身份） |
| ❷ | 实例创建/缓存由包装类控制 | `@InstanceProvider`（static 工厂方法，可对接外部缓存） |
| ❸ | 多种身份提取（含多槽位） | 同一事件类型可有多个 `@KeyExtractor`，OR 语义 |
| ❹ | 两层注解架构 | `@EventRoute`（匹配层）+ `@EventHandler`（执行层） |
| ❺ | 跨优先级独占 | 按优先级降序分组，exclusive 则 break 所有低优先级 |
| ❻ | 身份可为任意类型 | 推荐公共 record，框架用 `equals()` 匹配 |
| ❼ | 全局/对象统一 | 单例（无 `@KeyExtractor`，GLOBAL 身份）vs 多例（有 `@KeyExtractor`） |
| ❽ | 允许完全推倒重构 | 删除旧文件，从零重写 |

---

## 二、四注解体系

### 2.1 总览

```
@EventRoute       匹配层：哪些事件？什么条件？
                  ├── value       事件类型（可选，默认自动推断）
                  ├── condition   SpEL 条件（可选）
                  └── filter      方法引用（可选）

@EventHandler     执行层：什么优先级？是否独占？
                  ├── priority    优先级（默认 NORMAL）
                  └── exclusive   独占标记（默认 false）

@KeyExtractor     身份层：从事件提取什么身份？
                  └── static 方法，纯函数，无优先级无条件

@InstanceProvider 工厂层：从身份获取/创建实例？
                  └── static 方法（可选，默认=构造函数+内部缓存）
```

### 2.2 `@EventRoute` — 匹配筛选注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventRoute {
    Class<? extends Event> value() default Event.class;  // 事件类型
    String condition() default "";                        // SpEL 条件
    String filter() default "";                           // filter 方法名
}
```

### 2.3 `@EventHandler` — 执行控制注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {
    EventPriority priority() default EventPriority.NORMAL;  // 执行优先级
    boolean exclusive() default false;                       // 跨优先级独占
}
```

### 2.4 `@KeyExtractor` — 身份提取标记

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeyExtractor {}
```

**规则：**
- **必须为 static 方法**
- 参数 = 事件类型，返回值 = 身份标识
- 同一事件类型可有多个（多槽位，OR 语义）
- 无优先级、无条件 — 纯粹的身份提取

### 2.5 `@InstanceProvider` — 实例工厂标记

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InstanceProvider {}
```

**规则：**
- **必须为 static 方法**
- 参数 = 身份标识，返回值 = 包装类实例（或 null = 跳过）
- **可选**：无此注解时框架使用默认缓存（构造函数 + ConcurrentHashMap）
- 分发时调用，决定实例的获取/创建/复用/丢弃

---

## 三、实例生命周期模型

### 3.1 三种实例策略

```java
// 策略1：默认缓存（无 @InstanceProvider）
// 框架用构造函数创建，缓存到内部 ConcurrentHashMap
public class PlayerWrapper {
    public PlayerWrapper(Player player) { ... }
}

// 策略2：对接外部缓存容器
public class PlayerWrapper {
    @InstanceProvider
    public static PlayerWrapper getOrCreate(Player player) {
        return ExternalManager.getWrapper(player);
    }
}

// 策略3：一次性实例
public class OneTimeHandler {
    @InstanceProvider
    public static OneTimeHandler create(Player player) {
        return new OneTimeHandler(player);  // 用完即弃
    }
}
```

### 3.2 单例 vs 多例

| 类型 | 标志 | 身份 | 实例数 | 注册方式 |
|------|------|------|--------|----------|
| 全局处理器 | 无 `@KeyExtractor` | `GLOBAL` 常量 | 1（Spring Bean） | `register(bean)` |
| 对象处理器 | 有 `@KeyExtractor` | 从事件提取 | N（按身份创建） | `register(Class)` |

### 3.3 注册 API

```java
// 全局处理器：Spring Bean 自动注册
// EventBeanPostProcessor 扫描 @EventHandler 方法，调用 register(bean)
handlerRegistry.register(bean);

// 对象处理器：注册类（实例在分发时按需创建）
handlerRegistry.register(PlayerWrapper.class);

// 注销
handlerRegistry.unregister(PlayerWrapper.class);     // 注销整个类
handlerRegistry.evict(PlayerWrapper.class, playerA);  // 从默认缓存驱逐特定身份
```

---

## 四、分发流程

### 4.1 完整调用链

```
Nukkit 触发事件
    ↓
EventService.dispatch(eventType, event)  [L1]
    ↓
HandlerRegistry.dispatch(eventType, event)  [L3]
    │
    ├── ① 获取该事件类型的所有 WrapperRegistration
    │
    ├── ② 预提取全局 Key（KeyExtractorRegistry，只提取一次）
    │
    ├── ③ 遍历每个 Registration：
    │   ├── 全局处理器（identity == GLOBAL）→ 直接收集 handler
    │   ├── 有自定义 @KeyExtractor → 逐个调用（static），OR 语义匹配
    │   │   ├── 提取 Key → 调用 @InstanceProvider 获取实例
    │   │   └── 实例非 null → 收集 handler 绑定到实例
    │   └── 无自定义 @KeyExtractor → 用全局 Key
    │       ├── 调用 @InstanceProvider 或默认缓存获取实例
    │       └── 实例非 null → 收集 handler
    │
    ├── ④ 按优先级降序排序（HIGHEST → LOWEST）
    │
    └── ⑤ 分组分发 + 跨优先级独占检查
        ├── 同优先级组内：逐个执行 handler
        ├── 任一 handler exclusive=true → 标记本组独占
        └── 进入下一优先级组前：若上组独占 → BREAK
```

### 4.2 跨优先级独占图解

```
事件到达，4 个匹配的 handler：
  A: priority=HIGH, exclusive=true
  B: priority=HIGH, exclusive=false
  C: priority=NORMAL
  D: priority=LOW

  ┌─ HIGH 组 ──────────────┐
  │  A 执行 → exclusive=true │
  │  B 执行                 │
  │  本组有独占 → BREAK     │
  └────────────────────────┘
  ✗ NORMAL：跳过    ✗ LOW：跳过
```

---

## 五、类设计

### 5.1 文件结构

```
jframe_event/src/main/java/io/github/JiangHu/jframe/event/
├── annotation/
│   ├── EventRoute.java          # 匹配筛选注解
│   ├── EventHandler.java        # 执行控制注解（priority + exclusive）
│   ├── KeyExtractor.java        # 身份提取标记（static 方法）
│   └── InstanceProvider.java    # 实例工厂标记（static 方法）
├── routing/
│   ├── HandlerTemplate.java     # 处理器模板（未绑定实例，dispatch 时绑定）
│   ├── HandlerRegistry.java     # ★ 核心路由引擎
│   └── KeyExtractorRegistry.java # 全局默认提取器（内部使用）
├── spring/
│   └── EventBeanPostProcessor.java
├── EventService.java            # L1 Nukkit 桥接
└── EventConsumer.java           # 函数式接口
```

### 5.2 删除清单

| 文件 | 原因 |
|------|------|
| `annotation/NukkitEvent.java` | 拆分为 `@EventRoute` + `@EventHandler` |
| `routing/RoutingSpec.java` | 被 `@KeyExtractor` 取代 |
| `routing/EventKeyExtractor.java` | 内化到 HandlerRegistry |
| `routing/AnnotatedHandler.java` | 重写为 `HandlerTemplate` |

### 5.3 `HandlerTemplate` — 处理器模板

```java
public class HandlerTemplate {
    private final Class<?> wrapperClass;
    private final Method method;              // 处理方法
    private final Class<? extends Event> eventType;
    private final Expression conditionExp;    // 预编译 SpEL
    private final Method filterMethod;        // filter 方法
    private final EventPriority priority;
    private final boolean exclusive;

    /**
     * 在给定实例上处理事件。
     * @return true = 声明独占
     */
    public boolean handle(Object target, Event event) {
        if (!passesCondition(target, event)) return false;
        method.invoke(target, event);
        return exclusive;
    }
}
```

### 5.4 `HandlerRegistry` — 核心路由引擎

#### 数据结构

```java
@Component
public class HandlerRegistry {

    private static final Object GLOBAL = new Object();

    /** 类注册信息 */
    record WrapperRegistration(
        Class<?> wrapperClass,
        boolean isGlobal,                                    // true = Spring Bean 单例
        Object singletonInstance,                            // 全局处理器的固定实例（null = 对象处理器）
        Method instanceProvider,                             // @InstanceProvider（null = 默认缓存）
        ConcurrentHashMap<Object, Object> defaultCache,      // 默认缓存（instanceProvider == null 时使用）
        Map<Class<? extends Event>, List<Method>> extractors,// @KeyExtractor（按事件类型）
        Map<Class<? extends Event>, List<HandlerTemplate>> handlers // @EventHandler（按事件类型）
    ) {}

    /** 核心存储：事件类型 → [WrapperRegistration] */
    private final Map<Class<? extends Event>, CopyOnWriteArrayList<WrapperRegistration>> registry = new ConcurrentHashMap<>();

    /** 类 → Registration 反向索引 */
    private final Map<Class<?>, WrapperRegistration> classToReg = new ConcurrentHashMap<>();
}
```

#### 关键方法

```java
// 注册 Spring Bean（全局处理器）
public void register(Object bean);

// 注册包装类（对象处理器）
public <T> void register(Class<T> wrapperClass);

// 注销整个类
public void unregister(Class<?> wrapperClass);

// 从默认缓存驱逐特定身份
public void evict(Class<?> wrapperClass, Object identity);

// 内部分发
void dispatch(Class<? extends Event> eventType, Event event);
```

### 5.5 `EventService` — 简化

每个事件类型只注册一次（固定 LOWEST），不再按 type+priority 分别注册。

### 5.6 `KeyExtractorRegistry` — 保留简化

预置 PlayerEvent/BlockEvent/EntityEvent/InventoryEvent 提取器，加 NO_EXTRACTOR 哨兵。

---

## 六、完整使用示例

### 6.1 全局处理器

```java
@Component
public class ChatLogger {
    @EventRoute
    @EventHandler
    public void onChat(PlayerChatEvent event) { ... }

    @EventRoute(condition = "#event.player.gamemode == 1")
    @EventHandler(priority = EventPriority.HIGH, exclusive = true)
    public void onCreativeChat(PlayerChatEvent event) { ... }
}
```

### 6.2 对象处理器 — 默认缓存

```java
public class PlayerWrapper {
    private final Player player;

    public PlayerWrapper(Player player) { this.player = player; }

    @KeyExtractor
    public static Player extract(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    @EventRoute
    @EventHandler
    public void onMove(PlayerMoveEvent event) { ... }
}

// 注册类，框架在分发时自动创建+缓存实例
handlerRegistry.register(PlayerWrapper.class);
```

### 6.3 对象处理器 — 外部缓存

```java
public class PlayerWrapper {
    private final Player player;

    public PlayerWrapper(Player player) { this.player = player; }

    @KeyExtractor
    public static Player extract(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    @InstanceProvider
    public static PlayerWrapper getOrCreate(Player player) {
        return PlayerManager.getWrapper(player);  // 外部缓存
    }

    @EventRoute
    @EventHandler
    public void onMove(PlayerMoveEvent event) { ... }
}
```

### 6.4 多槽位（攻击者/受害者）

```java
public class CombatantWrapper {
    private final Entity entity;

    public CombatantWrapper(Entity entity) { this.entity = entity; }

    @KeyExtractor  // 槽位1：作为攻击者
    public static Entity asDamager(EntityDamageByEntityEvent event) {
        return event.getDamager();
    }

    @KeyExtractor  // 槽位2：作为受害者
    public static Entity asVictim(EntityDamageByEntityEvent event) {
        return event.getEntity();
    }

    @EventRoute
    @EventHandler
    public void onCombat(EntityDamageByEntityEvent event) { ... }
}
```

### 6.5 复合身份 record

```java
public class RegionWrapper {
    public record RegionKey(String world, int chunkX, int chunkZ) {}

    @KeyExtractor
    public static RegionKey extract(BlockBreakEvent event) {
        Position pos = event.getBlock();
        return new RegionKey(pos.level.getName(), pos.getFloorX() >> 4, pos.getFloorZ() >> 4);
    }

    @InstanceProvider
    public static RegionWrapper getOrCreate(RegionKey key) {
        return regionCache.get(key);  // 不存在返回 null → 跳过
    }

    @EventRoute
    @EventHandler
    public void onBreak(BlockBreakEvent event) { ... }
}
```

---

## 七、实现步骤

### 阶段一：注解层
1. 创建 `@EventRoute`（value, condition, filter）
2. 创建 `@EventHandler`（priority, exclusive）
3. 创建 `@KeyExtractor`（空标记）
4. 创建 `@InstanceProvider`（空标记）

### 阶段二：核心引擎
5. 创建 `HandlerTemplate`（未绑定处理器模板，handle(target, event) 返回 boolean）
6. 创建 `HandlerRegistry`（类注册 + 工厂调用 + 多槽位 + 跨优先级独占）
7. 简化 `KeyExtractorRegistry`（加 NO_EXTRACTOR 哨兵）

### 阶段三：集成层
8. 简化 `EventService`（每类型只注册一次）
9. 更新 `EventBeanPostProcessor`（扫描 `@EventHandler`）

### 阶段四：清理 + 示例
10. 删除旧文件（NukkitEvent, RoutingSpec, EventKeyExtractor, AnnotatedHandler, ObjectEventRouter）
11. 重写全部 test 示例
12. 修正包名 `core.event` → `event`
13. 更新文档

---

## 八、线程安全

| 组件 | 策略 |
|------|------|
| HandlerRegistry 外层 Map | ConcurrentHashMap |
| 内层 List | CopyOnWriteArrayList |
| 默认缓存 | ConcurrentHashMap |
| HandlerTemplate | 不可变 |
| 分发 | 仅 Nukkit 主线程 |

---

## 九、已知限制

1. 框架固定 LOWEST 注册到 Nukkit，丢失跨插件优先级交互
2. `@KeyExtractor` 必须 static（实例在提取时尚未创建）
3. `@InstanceProvider` 必须 static（同上）
4. 同优先级内 handler 顺序不明确
5. 外部缓存的实例清理由用户自行管理
