# 事件系统 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，目的是让你 100% 理解每个类的内部实现、设计决策、线程安全模型，
> 并能在修改代码时知道**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个框架，请看 [README.md](README.md)。

---

## 目录

- [1. 设计哲学与核心问题](#1-设计哲学与核心问题)
- [2. 四层架构总览](#2-四层架构总览)
- [3. 运行时数据流（完整调用链）](#3-运行时数据流完整调用链)
- [4. 类逐一剖析](#4-类逐一剖析)
  - [4.1 EventService（L1 引擎）](#41-eventservicel1-引擎)
  - [4.2 EventConsumer（L1 函数式接口）](#42-eventconsumerl1-函数式接口)
  - [4.3 NukkitEvent（L4 注解）](#43-nukkiteventl4-注解)
  - [4.4 EventKeyExtractor（L2 接口）](#44-eventkeyextractorl2-接口)
  - [4.5 KeyExtractorRegistry（L2 注册表）](#45-keyextractorregistryl2-注册表)
  - [4.6 AnnotatedHandler（L3 处理器封装）](#46-annotatedhandlerl3-处理器封装)
  - [4.7 ObjectEventRouter（L3 核心路由）](#47-objecteventrouterl3-核心路由)
  - [4.8 EventBeanPostProcessor（L4 Spring 桥接）](#48-eventbeanpostprocessorl4-spring-桥接)
- [5. 线程安全分析](#5-线程安全分析)
- [6. 生命周期管理](#6-生命周期管理)
- [7. 扩展点](#7-扩展点)
- [8. 已知限制与陷阱](#8-已知限制与陷阱)
- [9. 修改指南（改代码前必读）](#9-修改指南改代码前必读)
- [10. 性能特征](#10-性能特征)

---

## 1. 设计哲学与核心问题

### 1.1 旧方案为什么慢？

最初的设计是一个"catch-all"事件监听器：用一个 `@EventHandler` 监听所有 `Event`，
然后遍历所有已注册的 `EventConsumer`，逐个判断类型是否匹配。

**致命问题：**
```
服务器每秒触发数千个事件（移动、物理、网络包……）
    ↓
每个事件都进入 catch-all 监听器
    ↓
遍历所有 consumer（哪怕只有 1 个匹配）
    ↓
99% 的工作是浪费的
```

### 1.2 新方案的核心思路

**"让 Nukkit 自己做类型过滤"** —— 利用 Nukkit 原生的 `PluginManager.registerEvent()` API，
按事件类型精准注册。没人监听的事件类型，Nukkit 根本不会调用任何代码。

**"让 Key 提取器做身份过滤"** —— 对象级处理器不需要遍历所有处理器找匹配的，
而是通过 Key（如 Player 对象）直接在 HashMap 中 O(1) 查找。

### 1.3 为什么是四层？

| 问题 | 解决层 | 理由 |
|------|--------|------|
| "没人监听的事件也会触发代码" | L1 EventService | 按类型注册到 Nukkit，类型过滤交给 Nukkit |
| "怎么知道这个事件是谁的？" | L2 KeyExtractorRegistry | 从事件中提取身份 Key |
| "怎么把事件送到正确的对象？" | L3 ObjectEventRouter | 按 Key 路由 |
| "用户怎么用最简单的方式写代码？" | L4 @NukkitEvent | 注解 + Spring 自动扫描 |

分层的好处：**每一层都可以独立修改、替换、测试**，不影响其他层。

---

## 2. 四层架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         L4 用户代码层                                │
│                                                                     │
│  用户写 @NukkitEvent 方法                                          │
│     ↓                                                               │
│  EventBeanPostProcessor 扫描 Spring Bean                           │
│     ↓ registerGlobal(bean)                                          │
│  或 ObjectEventRouter.register(wrapper, key)  手动注册对象级        │
├─────────────────────────────────────────────────────────────────────┤
│                         L3 对象路由层                                │
│                                                                     │
│  ObjectEventRouter                                                  │
│  ├── globalHandlers: 事件类型 → [AnnotatedHandler]                 │
│  ├── keyedHandlers:  事件类型 → (Key → [AnnotatedHandler])         │
│  └── dispatch(): 先全局，再按 Key 路由                              │
│     ↓ subscribe(eventType, priority, lambda)                        │
├─────────────────────────────────────────────────────────────────────┤
│                         L2 身份提取层                                │
│                                                                     │
│  KeyExtractorRegistry                                               │
│  ├── extractors: 事件基类 → EventKeyExtractor                      │
│  ├── cache: 具体事件类 → 解析后的提取器（含继承链查找）             │
│  └── findExtractor(): 沿继承链查找 + 缓存                           │
├─────────────────────────────────────────────────────────────────────┤
│                         L1 事件引擎层                                │
│                                                                     │
│  EventService (implements Listener)                                 │
│  ├── consumers: 事件类型 → (优先级 → [EventConsumer])              │
│  ├── registered: 已注册的 (类型+优先级) 集合                        │
│  ├── pendingRegistrations: 延迟注册队列                             │
│  └── doRegister(): 调用 Nukkit PluginManager.registerEvent()        │
│     ↓                                                               │
│  Nukkit 原生事件系统（按类型精准触发）                               │
└─────────────────────────────────────────────────────────────────────┘
```

### 依赖关系图

```
EventBeanPostProcessor ──→ ObjectEventRouter ──→ EventService
                              │       │
                              │       └──→ Nukkit PluginManager
                              │
                              └──→ KeyExtractorRegistry
                                      │
                                      └──→ EventKeyExtractor (接口)
```

> **关键：** 依赖是单向的。L1 不知道 L2/L3/L4 的存在。L3 通过 `subscribe()` 的 lambda 回调桥接 L1。

---

## 3. 运行时数据流（完整调用链）

### 3.1 启动阶段（Spring 容器初始化）

```
Spring 容器启动
    │
    ├── 创建 KeyExtractorRegistry Bean
    │     └── 构造函数中 registerDefaults()
    │         ├── 反射注册 PlayerEvent → getPlayer()
    │         ├── 反射注册 BlockEvent → getBlock()
    │         ├── 反射注册 EntityEvent → getEntity()
    │         └── 反射注册 InventoryEvent → getInventory()
    │
    ├── 创建 EventService Bean
    │     └── plugin == null，所有 subscribe 会暂存到 pendingRegistrations
    │
    ├── 创建 ObjectEventRouter Bean
    │     └── 构造注入 KeyExtractorRegistry + EventService
    │
    ├── 创建 EventBeanPostProcessor Bean
    │     └── 构造注入 ObjectEventRouter
    │
    └── 遍历所有 Bean → postProcessAfterInitialization()
          └── 对每个含 @NukkitEvent 的 Bean:
              ├── hasNukkitEventMethods() 快速检查
              └── objectEventRouter.registerGlobal(bean)
                  ├── scanHandlers(bean) 扫描所有 @NukkitEvent 方法
                  │     └── 为每个方法创建 AnnotatedHandler
                  │         ├── 解析事件类型（注解 value 或参数推断）
                  │         ├── 解析 SpEL condition（注册时解析一次）
                  │         └── 查找 filter 方法（注册时反射一次）
                  ├── 加入 globalHandlers
                  └── ensureSubscribed(eventType, priority)
                      └── eventService.subscribe(eventType, priority, lambda)
                          ├── 加入 consumers map
                          └── ensureRegistered()
                              └── plugin == null → 暂存到 pendingRegistrations
```

### 3.2 插件启用阶段（onEnable）

```
插件主类 onEnable()
    └── eventService.setPlugin(this)
        ├── 设置 this.plugin
        └── 遍历 pendingRegistrations
            └── doRegister(eventType, priority)
                └── Server.getInstance().getPluginManager().registerEvent(
                        eventType, this(EventService实例), priority,
                        (listener, event) -> dispatch(eventType, priority, event),
                        plugin, false)
```

> **此刻起**，Nukkit 开始按事件类型精准触发 EventService.dispatch()。

### 3.3 事件触发阶段（运行时热路径）

```
Nukkit 触发 PlayerMoveEvent（玩家A移动）
    │
    ↓ Nukkit 内部按事件类型查找已注册的处理器
    │
    ↓ 找到 EventService 为 (PlayerMoveEvent, NORMAL) 注册的 lambda
    │
    EventService.dispatch(PlayerMoveEvent.class, NORMAL, event)
    │
    ├── consumers.get(PlayerMoveEvent.class) → EnumMap
    ├── EnumMap.get(NORMAL) → List<EventConsumer>
    └── 遍历 list:
        └── consumer.handleEvent(event)
            │
            ↓ 这个 consumer 是 ObjectEventRouter.ensureSubscribed() 注册的 lambda:
            │
            ObjectEventRouter.dispatch(PlayerMoveEvent.class, NORMAL, event)
            │
            ├── ① 全局处理器
            │   ├── globalHandlers.get(PlayerMoveEvent.class) → [handlers]
            │   └── 遍历，priority 匹配的调用 handler.handle(event)
            │       └── AnnotatedHandler.handle(event)
            │           ├── passesCondition(event)  ← filter 方法 或 SpEL
            │           │   ├── filterMethod != null → filterMethod.invoke(target, event)
            │           │   └── conditionExpression != null → SpEL 求值
            │           └── method.invoke(target, event)  ← 反射调用用户方法
            │
            └── ② 对象级处理器（按 Key 路由）
                ├── keyExtractorRegistry.findExtractor(PlayerMoveEvent.class)
                │   └── 沿继承链: PlayerMoveEvent → PlayerEvent → 找到 getPlayer() 提取器
                ├── extractor.extract(event) → PlayerA 对象
                ├── keyedHandlers.get(PlayerMoveEvent.class) → Map<Key, handlers>
                ├── Map.get(PlayerA) → [handlers]  ← O(1) 查找！
                └── 遍历，priority 匹配的调用 handler.handle(event)
```

### 3.4 对象级注册阶段（玩家上线）

```
玩家A上线 → PlayerJoinEvent
    └── 用户的 @NukkitEvent onJoin 方法
        ├── new PlayerWrapper(playerA, router)
        └── router.register(wrapper, playerA)
            ├── scanHandlers(wrapper) → [AnnotatedHandler(onMove), AnnotatedHandler(onQuit)]
            ├── wrapperToHandlers.put(wrapper, handlers)  ← 反向索引
            └── 对每个 handler:
                ├── keyedHandlers[handler.eventType][playerA].add(handler)
                └── ensureSubscribed(eventType, priority)
                    └── 如果该 (类型+优先级) 还没订阅 EventService → subscribe()
```

### 3.5 对象级注销阶段（玩家下线）

```
玩家A下线 → 用户调用 router.unregister(wrapper)
    └── wrapperToHandlers.remove(wrapper) → [handlers]
        └── 对每个 handler:
            ├── removeHandlerFromGlobal(handler)  ← 从 globalHandlers 移除
            └── removeHandlerFromKeyed(handler)   ← 从 keyedHandlers 所有 Key 下移除
```

---

## 4. 类逐一剖析

### 4.1 EventService（L1 引擎）

**文件：** [`EventService.java`](EventService.java)
**角色：** 对接 Nukkit 底层事件系统，按事件类型精准注册和分发。
**Spring 注解：** `@Component`，实现 `cn.nukkit.event.Listener`

#### 字段详解

| 字段 | 类型 | 线程安全 | 用途 |
|------|------|----------|------|
| `consumers` | `ConcurrentHashMap<Class<? extends Event>, EnumMap<EventPriority, List<EventConsumer<?>>>>` | 外层 CHM 保证 | 消费者注册表。注意：**内层 EnumMap 不是线程安全的**，但通过 `computeIfAbsent` 的原子性保证创建安全；内层 List 用 `CopyOnWriteArrayList` 保证遍历安全 |
| `registered` | `Set<String>`（`ConcurrentHashMap.newKeySet()`） | 线程安全 | 已向 Nukkit 注册的 `"类名@优先级"` 字符串集合，防止重复注册 |
| `pendingRegistrations` | `List<Registration>` | **非线程安全** | 延迟注册队列。仅在 Spring 初始化阶段（单线程）使用，`setPlugin()` 后清空，之后不再使用 |
| `plugin` | `Plugin` | volatile 语义由 `@Getter` 生成 | 关联的插件实例，用于 Nukkit 注册 |

#### 方法详解

| 方法 | 可见性 | 调用时机 | 核心逻辑 |
|------|--------|----------|----------|
| `subscribe(type, consumer)` | public | 运行时 | 委托给三参数版本，默认 NORMAL 优先级 |
| `subscribe(type, priority, consumer)` | public | 运行时 | `computeIfAbsent` 链式创建 EnumMap → List，add consumer，然后 `ensureRegistered` |
| `unsubscribe(type, priority, consumer)` | public | 运行时 | 从指定优先级的 List 中 remove |
| `unsubscribe(type, consumer)` | public | 运行时 | 遍历所有优先级的 List，逐个 remove |
| `unsubscribeAll(consumer)` | public | 运行时 | 遍历所有事件类型、所有优先级，逐个 remove |
| `setPlugin(plugin)` | public | `onEnable()` | 设置 plugin，刷新 pendingRegistrations |
| `ensureRegistered(type, priority)` | private | subscribe 时 | 检查 `registered` 集合，未注册则 `doRegister` 或暂存 |
| `doRegister(type, priority)` | private | ensureRegistered 或 setPlugin | 调用 `PluginManager.registerEvent()`，注册一个 lambda 到 Nukkit |
| `dispatch(type, priority, event)` | private | Nukkit 回调 | 从 consumers 中查找对应 List，遍历调用，异常隔离 |

#### 关键设计决策

**① 为什么用 `EnumMap<EventPriority, List>` 而不是直接 `Map<EventPriority, List>`？**
`EnumMap` 用数组实现，查找是 O(1) 且无 hash 开销，比 HashMap 快。EventPriority 只有 6 个值，非常适合 EnumMap。

**② 为什么内层 List 用 `CopyOnWriteArrayList` 而不是 `synchronizedList`？**
事件分发（读/遍历）远多于订阅/注销（写）。COW 在读时无锁，写时复制数组。适合"读多写少"的场景。

**③ 为什么 `dispatch` 的参数要传 `eventType` 和 `priority`？**
因为注册时是用 `(eventType, priority)` 组合注册的 lambda：`(listener, event) -> dispatch(eventType, priority, event)`。
Nukkit 回调时只传 event，但 lambda 闭包捕获了 eventType 和 priority，所以 dispatch 知道该查哪个 List。

**④ 为什么 `dispatch` 中捕获异常而不是让它传播？**
Nukkit 的事件分发在一个循环中调用多个处理器。如果一个处理器抛异常，不应影响其他处理器和 Nukkit 自身的事件管线。

#### 修改注意事项

- **修改 `consumers` 的结构时**：注意 `computeIfAbsent` 的原子性。不要在 `computeIfAbsent` 外部做"先 get 再 put"的操作，会有竞态条件。
- **修改 `doRegister` 时**：`registerEvent` 的最后一个参数 `false` 表示"不忽略取消的事件"。如果改为 `true`，被取消的事件不会触发。
- **修改 `dispatch` 时**：保持异常隔离逻辑。如果去掉 try-catch，一个处理器异常会中断整个分发链。

---

### 4.2 EventConsumer（L1 函数式接口）

**文件：** [`EventConsumer.java`](EventConsumer.java)
**角色：** 定义事件处理逻辑的函数式接口，可作 lambda 使用。

```java
@FunctionalInterface
public interface EventConsumer<T extends Event> {
    boolean handleEvent(T event);
}
```

#### 返回值语义

| 返回值 | 含义 | 影响 |
|--------|------|------|
| `false` | 非独占 | EventService 继续调用同优先级的其他 consumer |
| `true` | 独占 | EventService 停止向同优先级的后续 consumer 分发 |

> **注意：** "独占"只影响**同一优先级内**的 consumer。不同优先级的处理器由 Nukkit 分别调用，不受影响。

#### 修改注意事项

- 这个接口被 ObjectEventRouter 的 `ensureSubscribed` 用 lambda 实现，固定返回 `false`（非独占）。
  如果想让 ObjectEventRouter 支持独占语义，需要修改 `ensureSubscribed` 中的 lambda。

---

### 4.3 NukkitEvent（L4 注解）

**文件：** [`annotation/NukkitEvent.java`](annotation/NukkitEvent.java)
**角色：** 用户标注在方法上，声明"这个方法处理什么事件"。

#### 属性详解

| 属性 | 类型 | 默认值 | 语义 |
|------|------|--------|------|
| `value` | `Class<? extends Event>` | `Event.class` | 事件类型。`Event.class` 是哨兵值，表示"自动从方法参数推断" |
| `condition` | `String` | `""` | SpEL 表达式。空字符串表示无条件 |
| `filter` | `String` | `""` | 类中筛选方法的方法名。空字符串表示无 filter |
| `priority` | `EventPriority` | `NORMAL` | 事件优先级 |

#### 关键设计决策

**① 为什么 `value` 默认是 `Event.class` 而不是用 `null`？**
Java 注解的属性不能是 `null`。`Event.class` 作为哨兵值，在 `AnnotatedHandler` 构造函数中判断 `if (type == Event.class)` 来触发自动推断。

**② 为什么 `condition` 和 `filter` 互斥？**
两者都是条件过滤，同时指定会产生歧义。在 `AnnotatedHandler` 构造函数中检查并抛出 `IllegalArgumentException`。

**③ 为什么注解不能包含方法体？**
Java 语言限制：注解属性只能是编译时常量（基本类型、String、Class、枚举、注解、以上的一维数组）。不能是 lambda 或代码块。这就是为什么复杂条件用 `filter` 引用外部方法，而不是直接写在注解里。

#### 修改注意事项

- **添加新属性时**：必须提供默认值（Java 注解要求）。同时需要更新 `AnnotatedHandler` 构造函数来解析新属性。
- **修改 `value` 的哨兵值时**：需要同步修改 `AnnotatedHandler` 中的 `if (type == Event.class)` 判断。

---

### 4.4 EventKeyExtractor（L2 接口）

**文件：** [`routing/EventKeyExtractor.java`](routing/EventKeyExtractor.java)
**角色：** 从事件中提取"身份 Key"的函数式接口。

```java
@FunctionalInterface
public interface EventKeyExtractor<T extends Event> {
    Object extract(T event);
}
```

#### 契约

- 返回非 null：该事件有身份 Key，用于对象级路由
- 返回 null：该事件没有身份 Key（全局事件），跳过对象级路由
- 实现应尽量轻量（通常是一个 getter 调用），因为每次事件触发都会调用

#### 修改注意事项

- 提取器的返回值会被用作 `ConcurrentHashMap` 的 Key，因此**必须正确实现 `equals()` 和 `hashCode()`**。
  Nukkit 的 `Player`、`Block` 等对象已正确实现，自定义 Key 类型需注意。

---

### 4.5 KeyExtractorRegistry（L2 注册表）

**文件：** [`routing/KeyExtractorRegistry.java`](routing/KeyExtractorRegistry.java)
**角色：** 维护"事件类型 → 提取器"映射，支持继承链查找和缓存。
**Spring 注解：** `@Component`

#### 字段详解

| 字段 | 类型 | 用途 |
|------|------|------|
| `extractors` | `ConcurrentHashMap<Class<? extends Event>, EventKeyExtractor<?>>` | 直接映射表，存储精确注册的类型（如 `PlayerEvent.class`） |
| `cache` | `ConcurrentHashMap<Class<? extends Event>, EventKeyExtractor<?>>` | 查找缓存，存储继承链查找结果（如 `PlayerMoveEvent.class → PlayerEvent 的提取器`） |

#### 方法详解

| 方法 | 可见性 | 核心逻辑 |
|------|--------|----------|
| `register(type, extractor)` | public | 放入 `extractors`，**清空 `cache`**（因为新注册可能改变查找结果） |
| `findExtractor(eventClass)` | public | `cache.computeIfAbsent(eventClass, this::resolveExtractor)` |
| `resolveExtractor(eventClass)` | private | 沿继承链 `getSuperclass()` 向上查找，找到第一个匹配的提取器 |
| `registerDefaults()` | private | 构造函数调用，通过反射注册 4 个预置提取器 |
| `registerByReflection(className, methodName)` | private | `Class.forName` + `getMethod`，创建反射提取器，失败静默跳过 |

#### 关键设计决策

**① 为什么用反射注册预置提取器（`registerByReflection`）而不是直接写死？**
为了兼容不同 Nukkit 版本。如果某个 Nukkit 版本没有 `PlayerEvent` 类，`Class.forName` 会抛 `ClassNotFoundException`，被 catch 后静默跳过，不会导致整个框架启动失败。

**② 为什么 `register` 要清空整个 `cache`？**
因为新注册的提取器可能匹配到之前缓存为 null 的事件类型。例如：先查找 `MyEvent`（无提取器，缓存 null），然后注册 `MyEvent` 的提取器，如果不清缓存，`MyEvent` 仍然返回 null。
> **⚠️ 这是一个已知的性能取舍：** 在运行时频繁调用 `register()` 会导致缓存反复失效。建议在启动阶段一次性注册所有提取器。

**③ 为什么 `cache` 可以缓存 null？**
`ConcurrentHashMap.computeIfAbsent` 不允许 null value。但 `resolveExtractor` 可能返回 null（无提取器）。
实际上，如果 `resolveExtractor` 返回 null，`computeIfAbsent` 不会放入缓存（CHM 不允许 null value）。
这意味着**没有提取器的事件类型每次都会重新查找继承链**。
> **⚠️ 已知限制：** 如果某个事件类型确实没有提取器，每次事件触发都会执行继承链遍历。对于高频事件（如 PlayerMoveEvent），这可能有性能影响。但实际中，PlayerMoveEvent 继承自 PlayerEvent，第一次查找就会命中并缓存提取器（非 null），所以不是问题。

#### 修改注意事项

- **添加新的预置提取器时**：在 `registerDefaults()` 中添加 `registerByReflection("全限定类名", "getter方法名")`。
- **如果需要缓存 null 结果**：考虑用一个哨兵对象（如 `NO_EXTRACTOR`）代替 null，这样可以被 CHM 缓存。

---

### 4.6 AnnotatedHandler（L3 处理器封装）

**文件：** [`routing/AnnotatedHandler.java`](routing/AnnotatedHandler.java)
**角色：** 不可变的、线程安全的事件处理器封装。在注册时完成所有"重活"（反射、SpEL 解析），运行时只做轻量操作。

#### 字段详解

| 字段 | 类型 | 可变性 | 用途 |
|------|------|--------|------|
| `SPEL_PARSER` | `SpelExpressionParser`（static final） | 不可变 | 全局共享的 SpEL 解析器（线程安全） |
| `target` | `Object` | final | 目标对象（Spring Bean 或包装类实例） |
| `method` | `Method` | final | 要调用的方法（构造时已 `setAccessible(true)`） |
| `eventType` | `Class<? extends Event>` | final | 解析后的事件类型 |
| `conditionExpression` | `Expression` | final | 预编译的 SpEL 条件，null 表示无 SpEL 条件 |
| `filterMethod` | `Method` | final | filter 方法（已 `setAccessible(true)`），null 表示无 filter |
| `priority` | `EventPriority` | final | 事件优先级 |

#### 构造函数逻辑（注册时执行一次）

```
AnnotatedHandler(target, method, annotation)
    │
    ├── ① 设置 method.setAccessible(true)  ← 避免每次反射的访问检查
    │
    ├── ② 解析事件类型
    │   ├── annotation.value() != Event.class → 用注解指定的类型
    │   └── annotation.value() == Event.class → resolveEventTypeFromParameter(method)
    │       └── 取方法第一个参数类型，必须是 Event 子类
    │
    ├── ③ 检查 condition 和 filter 互斥
    │   └── 两者都非空 → throw IllegalArgumentException
    │
    └── ④ 解析条件
        ├── condition 非空 → SpEL parseExpression(condition)，filterMethod = null
        ├── filter 非空 → resolveFilterMethod(target.getClass(), filter, eventType)
        │   └── 沿类层次查找：方法名匹配 + 单参数兼容 + 返回 boolean
        └── 都空 → conditionExpression = null, filterMethod = null
```

#### 运行时方法

| 方法 | 调用频率 | 核心逻辑 |
|------|----------|----------|
| `handle(event)` | 每次事件触发 | ① `passesCondition` → ② `method.invoke` |
| `passesCondition(event)` | 每次事件触发 | filter 方法优先（直接 Java 调用），其次 SpEL 求值 |
| `getEventType()` | 注册时 | 返回事件类型 |
| `getPriority()` | 注册时/分发时 | 返回优先级 |

#### 关键设计决策

**① 为什么 SpEL 表达式在构造时解析而不是每次求值时解析？**
SpEL 解析（`parseExpression`）比求值（`getValue`）贵得多。解析一次，求值无数次。

**② 为什么 filter 方法优先于 SpEL？**
filter 是直接 Java 方法调用（反射 invoke），比 SpEL 求值快。两者互斥所以不会同时存在。

**③ 为什么 `passesCondition` 中 filter 异常返回 false 而不是抛出？**
如果 filter 方法有 bug（如 NPE），不应该导致整个事件管线崩溃。返回 false = "条件不满足，跳过此处理器"，是安全的降级。

**④ 为什么 `handle` 方法把异常包装成 RuntimeException 抛出而不是吞掉？**
与 EventService.dispatch 的异常处理配合。EventService.dispatch 会 catch 这个异常并记录日志。如果在这里吞掉，用户永远不知道自己的处理器出错了。

#### 修改注意事项

- **修改 `passesCondition` 时**：注意 filter 和 SpEL 的优先级关系。如果需要同时支持两者，需要定义合并语义（AND? OR?）。
- **修改 SpEL 上下文变量时**：目前有 `#event` 和 `#target`。如果添加新变量，在 `passesCondition` 的 `ctx.setVariable` 处添加。
- **修改 `resolveFilterMethod` 时**：注意参数兼容性检查 `params[0].isAssignableFrom(eventType)`。这允许 filter 方法接收事件类型的父类（如 filter 接收 `PlayerEvent`，处理 `PlayerMoveEvent`）。

---

### 4.7 ObjectEventRouter（L3 核心路由）

**文件：** [`routing/ObjectEventRouter.java`](routing/ObjectEventRouter.java)
**角色：** 核心路由引擎，管理全局和对象级处理器，按 Key 路由事件。
**Spring 注解：** `@Component`

#### 字段详解

| 字段 | 类型 | 用途 |
|------|------|------|
| `keyExtractorRegistry` | `KeyExtractorRegistry` | 注入，用于提取事件身份 Key（全局提取器） |
| `eventService` | `EventService` | 注入，用于向 L1 注册订阅 |
| `globalHandlers` | `ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<AnnotatedHandler>>` | 全局处理器：事件类型 → 处理器列表 |
| `keyedHandlers` | `ConcurrentHashMap<Class<? extends Event>, ConcurrentHashMap<Object, CopyOnWriteArrayList<AnnotatedHandler>>>` | 对象级处理器（全局提取器）：事件类型 → (Key → 处理器列表) |
| `customKeyedHandlers` | `ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<CustomRoutingEntry>>` | 对象级处理器（自定义提取器）：事件类型 → 自定义路由条目列表 |
| `wrapperToHandlers` | `ConcurrentHashMap<Object, List<AnnotatedHandler>>` | 反向索引：包装类实例 → 其所有处理器（用于按实例注销） |
| `subscribedCombos` | `Set<String>`（CHM newKeySet） | 已向 EventService 订阅的 `"类名@优先级"` 集合 |

> **`CustomRoutingEntry` record：** `(AnnotatedHandler handler, Object key, EventKeyExtractor<?> extractor)`
> 封装了使用自定义提取器的路由条目。每个条目独立存储自己的提取器，因为不同 Wrapper 可能对同一事件类型使用不同的提取器。

#### 方法详解

| 方法 | 可见性 | 调用时机 | 核心逻辑 |
|------|--------|----------|----------|
| `registerGlobal(bean)` | public | EventBeanPostProcessor 自动调用 | scanHandlers → 加入 globalHandlers → ensureSubscribed |
| `register(wrapper, key)` | public | 用户手动调用（玩家上线等） | scanHandlers → 加入 wrapperToHandlers + keyedHandlers → ensureSubscribed |
| `register(wrapper, key, RoutingSpec)` | public | 用户手动调用（自定义路由） | scanHandlers → 按 spec 分流：有自定义提取器的加入 customKeyedHandlers，其余加入 keyedHandlers → ensureSubscribed |
| `unregister(wrapper)` | public | 用户手动调用（玩家下线等） | 从 wrapperToHandlers 取出 handlers → 从 global、keyed 和 customKeyed 中移除 |
| `unregisterByKey(key)` | public | 用户手动调用 | 遍历 keyedHandlers 和 customKeyedHandlers 所有事件类型，移除该 Key 下的所有处理器 |
| `unregisterAll()` | public | 插件禁用时 | 清空所有集合（含 customKeyedHandlers） |
| `dispatch(type, priority, event)` | public（由 lambda 回调） | 每次事件触发 | ① 全局处理器 ② 全局提取器 Key 匹配 → 对象级处理器 ③ 自定义提取器 Key 匹配 → 自定义路由处理器 |
| `scanHandlers(target)` | private | 注册时 | 遍历类层次，为每个 @NukkitEvent 方法创建 AnnotatedHandler |
| `ensureSubscribed(type, priority)` | private | 注册时 | 向 EventService 注册 lambda（每个 类型+优先级 只注册一次） |
| `removeHandlerFromGlobal(handler)` | private | 注销时 | 从 globalHandlers 对应列表中 remove |
| `removeHandlerFromKeyed(handler)` | private | 注销时 | 遍历 keyedHandlers 对应事件类型的所有 Key，逐个 remove |
| `removeHandlerFromCustom(handler)` | private | 注销时 | 从 customKeyedHandlers 对应事件类型的列表中移除匹配的 CustomRoutingEntry |

#### 关键设计决策

**① 为什么 `dispatch` 中要检查 `handler.getPriority() == priority`？**
因为 globalHandlers 和 keyedHandlers 不按优先级分组（与 EventService 的 consumers 不同）。
所有优先级的处理器都在同一个列表中，dispatch 时需要过滤出当前优先级的。
> **⚠️ 设计取舍：** 这样做简化了数据结构（不需要 EnumMap 嵌套），但每次 dispatch 需要遍历所有优先级的处理器做过滤。如果同一事件类型有很多不同优先级的处理器，可以考虑改为按优先级分组。

**② 为什么 `ensureSubscribed` 中 lambda 固定返回 `false`？**
ObjectEventRouter 的 dispatch 自己管理处理器的调用顺序和优先级过滤。它不希望 EventService 因为"独占"而停止分发（因为 EventService 的 consumer 列表中可能只有 ObjectEventRouter 这一个 consumer）。

**③ 为什么需要 `wrapperToHandlers` 反向索引？**
`unregister(wrapper)` 需要知道这个 wrapper 注册了哪些处理器，才能从 globalHandlers 和 keyedHandlers 中移除。没有反向索引的话，需要遍历所有集合查找，性能差。

**④ 为什么 `scanHandlers` 要遍历类层次？**
支持继承：父类的 @NukkitEvent 方法也应该被注册。遍历 `getDeclaredMethods()` + `getSuperclass()` 直到 Object。

**⑤ 为什么 `customKeyedHandlers` 用独立的 `CopyOnWriteArrayList<CustomRoutingEntry>` 而不是复用 `keyedHandlers`？**
全局提取器（`keyedHandlers`）的 Key 是在 dispatch 时统一提取一次，然后 O(1) 查找。
但自定义提取器（`RoutingSpec`）的每个条目有**不同的提取器**，无法统一提取一次 Key。
因此 `customKeyedHandlers` 存储的是 `CustomRoutingEntry(handler, key, extractor)` 三元组，
dispatch 时对每个条目独立调用其提取器。代价是 O(n) 遍历（n = 该事件类型的自定义路由条目数），
但自定义路由的使用场景通常条目数很少（几把剑、几个区域），性能影响可忽略。

**⑥ 为什么 `register(wrapper, key, RoutingSpec)` 要按 spec 分流处理器？**
一个 Wrapper 可能有多个 `@NukkitEvent` 方法，监听不同的事件类型。
其中一些事件类型在 RoutingSpec 中有自定义提取器，另一些没有。
分流逻辑：有自定义提取器的 → `customKeyedHandlers`，没有的 → `keyedHandlers`（用全局提取器）。
这样既支持自定义路由，又不影响需要全局提取器的事件类型。

#### 修改注意事项

- **修改 `dispatch` 时**：注意全局处理器和对象级处理器的调用顺序。如果需要改变顺序（如对象级优先），调整代码块顺序。
- **修改 `register` 时**：注意 `wrapperToHandlers` 和 `keyedHandlers` 的一致性。两者必须同步更新，否则 `unregister` 会出错。
- **修改 `ensureSubscribed` 时**：注意 `subscribedCombos` 的去重逻辑。如果去掉去重，同一个 (类型+优先级) 会向 EventService 注册多个 lambda，导致 dispatch 被多次调用。

---

### 4.8 EventBeanPostProcessor（L4 Spring 桥接）

**文件：** [`spring/EventBeanPostProcessor.java`](spring/EventBeanPostProcessor.java)
**角色：** Spring BeanPostProcessor，自动扫描 Bean 中的 @NukkitEvent 方法并注册为全局处理器。
**Spring 注解：** `@Component`，实现 `BeanPostProcessor`

#### 方法详解

| 方法 | 调用时机 | 核心逻辑 |
|------|----------|----------|
| `postProcessAfterInitialization(bean, beanName)` | 每个 Bean 初始化后 | 检查是否有 @NukkitEvent 方法 → 有则 `registerGlobal` |
| `hasNukkitEventMethods(clazz)` | postProcess 中 | 遍历类层次，找到第一个 @NukkitEvent 方法就返回 true（快速短路） |

#### 关键设计决策

**① 为什么用 `postProcessAfterInitialization` 而不是 `postProcessBeforeInitialization`？**
在 `After` 阶段，Bean 的 `@Autowired` 属性已经注入完毕。如果处理方法依赖注入的属性，此时才能安全调用。

**② 为什么只处理全局 Bean，不处理动态创建的对象？**
Spring 只管理单例 Bean 的生命周期。动态创建的包装类实例（如 `new PlayerWrapper()`）不在 Spring 容器中，BeanPostProcessor 看不到它们。用户需要手动调用 `router.register(wrapper, key)`。

**③ 为什么 `hasNukkitEventMethods` 要遍历类层次？**
因为 @NukkitEvent 方法可能在父类中。但注意：只检查 `getDeclaredMethods()`（当前类声明的方法），不检查继承的方法——除非父类自己也有 @NukkitEvent 注解。

#### 修改注意事项

- **性能注意**：`postProcessAfterInitialization` 会对**每一个** Bean 调用（包括 Spring 内部的 Bean）。`hasNukkitEventMethods` 的快速短路很重要——大部分 Bean 没有 @NukkitEvent 方法，第一次反射检查就返回 false。
- **如果需要支持原型（prototype）Bean**：当前只处理单例。原型 Bean 每次注入都创建新实例，但 BeanPostProcessor 只在创建时调用一次。

---

## 5. 线程安全分析

### 5.1 并发访问场景

| 操作 | 线程 | 频率 |
|------|------|------|
| `dispatch`（事件分发） | Nukkit 主线程 | 极高（每秒数千次） |
| `register`（对象级注册） | 任意线程（通常是主线程的事件处理器中） | 低（玩家上线时） |
| `unregister`（对象级注销） | 任意线程 | 低（玩家下线时） |
| `subscribe`（EventService） | 任意线程 | 极低（启动时） |

### 5.2 各组件的线程安全策略

#### EventService

| 字段 | 策略 | 分析 |
|------|------|------|
| `consumers`（外层 CHM） | `ConcurrentHashMap` | 线程安全 |
| `consumers`（内层 EnumMap） | `computeIfAbsent` 原子创建 | 创建后不再替换，只读 + 内层 List 的写操作 |
| `consumers`（内层 List） | `CopyOnWriteArrayList` | 读无锁，写时复制 |
| `registered` | `ConcurrentHashMap.newKeySet()` | 线程安全 |
| `pendingRegistrations` | 普通 `ArrayList` | **非线程安全**，但仅在 Spring 初始化（单线程）使用 |

> **⚠️ 潜在风险：** `pendingRegistrations` 是普通 ArrayList。如果在 Spring 初始化阶段有多个线程同时调用 `subscribe`（理论上不太可能，因为 Spring 初始化通常是单线程的），会有问题。如果担心，可以改为 `CopyOnWriteArrayList`。

#### ObjectEventRouter

| 字段 | 策略 | 分析 |
|------|------|------|
| `globalHandlers` | CHM + COW List | 线程安全 |
| `keyedHandlers` | CHM + CHM + COW List | 三层嵌套，全部线程安全 |
| `wrapperToHandlers` | CHM + COW List | 线程安全 |
| `subscribedCombos` | CHM newKeySet | 线程安全 |

#### KeyExtractorRegistry

| 字段 | 策略 | 分析 |
|------|------|------|
| `extractors` | CHM | 线程安全 |
| `cache` | CHM | 线程安全，但 `register` 时清空 cache 有"读写同时"的窗口（不影响正确性，只影响性能） |

### 5.3 CopyOnWriteArrayList 的取舍

`CopyOnWriteArrayList` 在**写**时会复制整个数组。如果：
- 一个事件类型有 100 个处理器
- 每秒有 10 次注册/注销

那么每秒会复制 100 元素的数组 10 次 = 1000 次数组拷贝。

**实际影响：** 对于 Minecraft 服务器，处理器数量通常不超过几十个，注册/注销频率低（玩家上下线），所以 COW 的写开销可以忽略。读（事件分发）完全无锁，这才是热路径。

---

## 6. 生命周期管理

### 6.1 组件生命周期

```
Spring 容器启动
    │
    ├── KeyExtractorRegistry 创建 → registerDefaults()
    ├── EventService 创建（plugin == null）
    ├── ObjectEventRouter 创建（注入上述两个）
    ├── EventBeanPostProcessor 创建（注入 ObjectEventRouter）
    │
    ├── Bean 后处理 → registerGlobal() → subscribe() → 暂存到 pendingRegistrations
    │
    ↓
插件 onEnable()
    │
    ├── setPlugin(this) → 刷新 pendingRegistrations → Nukkit 注册完成
    │
    ↓
运行阶段
    │
    ├── 事件触发 → dispatch → 路由到处理器
    ├── 玩家上线 → register(wrapper, key)
    ├── 玩家下线 → unregister(wrapper)
    │
    ↓
插件 onDisable()
    │
    └── （建议）unregisterAll() 清理所有处理器
```

### 6.2 延迟注册机制（Lazy Registration）

**问题：** Spring 容器初始化时，`EventBeanPostProcessor` 会扫描 Bean 并调用 `subscribe()`。但此时 `plugin` 还没设置（`onEnable()` 还没执行），无法向 Nukkit 注册。

**解决方案：**
```
subscribe() 被调用
    └── ensureRegistered()
        ├── plugin != null → 立即 doRegister()
        └── plugin == null → 暂存到 pendingRegistrations

setPlugin() 被调用
    └── 遍历 pendingRegistrations → 逐个 doRegister()
    └── 清空 pendingRegistrations
```

> **⚠️ 注意：** `setPlugin()` 只能调用一次（在 `onEnable()` 中）。如果在运行时再次调用，`pendingRegistrations` 已清空，不会有副作用，但 `registered` 集合会阻止重复注册。

### 6.3 对象级处理器的生命周期（用户负责）

框架**不自动管理**对象级处理器的生命周期。用户必须：
1. 对象创建时：`router.register(wrapper, key)`
2. 对象销毁时：`router.unregister(wrapper)`

**如果忘记 unregister：** 包装类实例会被 `keyedHandlers` 和 `wrapperToHandlers` 引用，GC 无法回收 → **内存泄漏**。

**推荐模式：** 在包装类内部监听退出事件自动清理（参见 `PlayerWrapper.onSelfQuit()`）。

---

## 7. 扩展点

### 7.1 添加新的身份 Key 提取器

```java
@Component
public class MyExtractorConfig {
    public MyExtractorConfig(KeyExtractorRegistry registry) {
        registry.register(MyCustomEvent.class, event -> event.getOwner());
    }
}
```

**注意事项：**
- 在 Spring 初始化阶段注册（构造函数），避免运行时注册导致缓存失效
- 提取的 Key 对象必须正确实现 `equals()` 和 `hashCode()`

### 7.2 添加新的 @NukkitEvent 属性

1. 在 `NukkitEvent` 注解中添加属性（必须有默认值）
2. 在 `AnnotatedHandler` 构造函数中解析新属性
3. 在 `AnnotatedHandler.handle()` 或 `passesCondition()` 中使用新属性
4. 更新 README.md 和本文档

### 7.3 替换 SpEL 条件引擎

如果不想用 SpEL，可以修改 `AnnotatedHandler`：
- 将 `conditionExpression` 字段改为自定义的条件接口
- 在构造函数中用自定义解析器替代 `SPEL_PARSER.parseExpression`
- 在 `passesCondition` 中用自定义求值器替代 `conditionExpression.getValue`

### 7.4 添加事件拦截/修改能力

当前 `dispatch` 只是单向传递事件。如果需要：
- **事件取消**：在 `AnnotatedHandler.handle` 后检查 `event.isCancelled()` 并停止后续分发
- **事件变换**：在全局处理器和对象级处理器之间修改事件对象

### 7.5 支持异步事件处理

当前所有处理在 Nukkit 主线程同步执行。如果需要异步：
- 在 `AnnotatedHandler.handle` 中将 `method.invoke` 提交到线程池
- **⚠️ 危险**：Nukkit 的大部分 API 不是线程安全的，异步操作可能导致 ConcurrentModificationException

---

## 8. 已知限制与陷阱

### 8.1 `KeyExtractorRegistry.cache` 不缓存 null 结果

`ConcurrentHashMap` 不允许 null value。`resolveExtractor` 返回 null 时，`computeIfAbsent` 不会缓存。
这意味着没有提取器的事件类型每次都会重新遍历继承链。

**影响：** 对于有提取器的事件类型（PlayerEvent 子类等），第一次查找后缓存非 null 提取器，后续命中缓存。对于确实没有提取器的事件类型，每次查找都遍历继承链——但这类事件通常不会触发对象级路由（`dispatch` 中 extractor == null 直接 return）。

### 8.2 `ObjectEventRouter.dispatch` 遍历所有优先级的处理器

globalHandlers 和 keyedHandlers 不按优先级分组，dispatch 时遍历整个列表做 `handler.getPriority() == priority` 过滤。

**影响：** 如果同一事件类型注册了大量不同优先级的处理器，每次 dispatch 都会遍历所有。可以考虑改为 `EnumMap<EventPriority, List<AnnotatedHandler>>` 结构。

### 8.3 `registerByReflection` 中的提取器异常被吞掉

```java
extractors.put(eventClass, event -> {
    try {
        return getter.invoke(event);
    } catch (Exception e) {
        return null;  // ← 异常被吞，返回 null
    }
});
```

如果 getter 方法抛异常（如事件对象状态异常），提取器静默返回 null，该事件不会被路由到对象级处理器。

**影响：** 用户可能困惑"为什么我的对象级处理器没被调用"。如果需要调试，可以在这里加日志。

### 8.4 `pendingRegistrations` 非线程安全

普通 `ArrayList`，如果在 Spring 初始化阶段有多线程并发 `subscribe`，可能出问题。实际上 Spring 初始化通常是单线程的，所以问题不大。

### 8.5 一个事件类型只能有一个提取器

`extractors` 是 `Map<Class, EventKeyExtractor>`，同一事件类型只能注册一个提取器。如果需要多个 Key（如 Player + Location），需要自定义提取器返回组合 Key。

### 8.6 filter 方法和 SpEL 条件不能同时使用

这是设计决策（互斥），在 `AnnotatedHandler` 构造函数中检查。如果需要两者组合，修改构造函数逻辑。

---

## 9. 修改指南（改代码前必读）

### 9.1 如果你要修改 EventService

| 你想做什么 | 改哪里 | 注意什么 |
|------------|--------|----------|
| 添加新的事件分发策略 | `dispatch()` | 保持异常隔离，否则一个处理器异常会中断所有 |
| 改变注册方式 | `doRegister()` | `registerEvent` 最后一个参数 `false` = 不忽略已取消事件 |
| 支持运行时动态优先级 | `consumers` 结构 | 需要从 `EnumMap<EventPriority, List>` 改为更灵活的结构 |
| 添加事件统计/监控 | `dispatch()` | 在遍历前后添加计数器，注意不要影响热路径性能 |

### 9.2 如果你要修改 ObjectEventRouter

| 你想做什么 | 改哪里 | 注意什么 |
|------------|--------|----------|
| 改变全局/对象级处理器的调用顺序 | `dispatch()` 中 ① 和 ② 的顺序 | 当前是先全局再对象级 |
| 按优先级分组处理器 | `globalHandlers` 和 `keyedHandlers` 的结构 | 改为 `EnumMap<EventPriority, ...>` 嵌套，dispatch 中就不需要 priority 过滤了 |
| 添加事件拦截 | `dispatch()` | 在全局处理器后检查 `event.isCancelled()` |
| 支持一个 wrapper 绑定多个 Key | `register()` 和 `keyedHandlers` 结构 | 需要改为 `Map<Wrapper, Set<Key>>` |

### 9.3 如果你要修改 AnnotatedHandler

| 你想做什么 | 改哪里 | 注意什么 |
|------------|--------|----------|
| 添加新的条件变量 | `passesCondition()` 中 `ctx.setVariable` | 在 NukkitEvent 的 condition 文档中说明 |
| 支持多个 filter 方法 | 构造函数 + `passesCondition` | 需要定义合并语义（AND/OR） |
| 改变异常处理策略 | `handle()` | 当前包装成 RuntimeException 抛出，由 EventService.dispatch catch |
| 支持异步执行 | `handle()` | 注意 Nukkit API 的线程安全限制 |

### 9.4 如果你要修改 KeyExtractorRegistry

| 你想做什么 | 改哪里 | 注意什么 |
|------------|--------|----------|
| 添加预置提取器 | `registerDefaults()` | 用 `registerByReflection` 保证版本兼容 |
| 缓存 null 结果 | `resolveExtractor` + `cache` | 用哨兵对象 `NO_EXTRACTOR` 代替 null |
| 支持多级缓存 | `cache` 结构 | 按事件包名分组缓存，减少冲突 |

### 9.5 如果你要修改 NukkitEvent 注解

| 你想做什么 | 改哪里 | 注意什么 |
|------------|--------|----------|
| 添加新属性 | `NukkitEvent` + `AnnotatedHandler` 构造函数 | 注解属性必须有默认值 |
| 修改哨兵值 | `value()` 默认值 + `AnnotatedHandler` 判断 | 保持两者一致 |
| 改变 condition/filter 互斥规则 | `AnnotatedHandler` 构造函数 | 定义新的合并语义 |

---

## 10. 性能特征

### 10.1 热路径性能（每次事件触发）

| 步骤 | 时间复杂度 | 实际开销 |
|------|-----------|----------|
| Nukkit 按类型查找注册的处理器 | O(1) | Nukkit 内部 HashMap |
| EventService.dispatch: 查找 consumer List | O(1) | CHM get + EnumMap get |
| EventService.dispatch: 遍历 consumer | O(n) | n = 该类型+优先级的 consumer 数（通常 1-2 个） |
| ObjectEventRouter.dispatch: 全局处理器遍历 | O(n) | n = 该事件类型的全局处理器数 |
| ObjectEventRouter.dispatch: Key 提取 | O(1) | 缓存命中时直接反射调用 getter |
| ObjectEventRouter.dispatch: Key 查找 | O(1) | CHM get |
| ObjectEventRouter.dispatch: 对象级处理器遍历 | O(n) | n = 该 Key 的处理器数（通常 1-5 个） |
| AnnotatedHandler.handle: 条件检查 | O(1) | filter 方法调用 或 SpEL 求值 |
| AnnotatedHandler.handle: 方法调用 | O(1) | 反射 invoke（已 setAccessible） |

**总结：** 每次事件触发的总开销是 O(n)，n 是匹配的处理器数量。没有"全量扫描"的 O(N) 操作。

### 10.2 冷路径性能（注册/注销时）

| 操作 | 开销 |
|------|------|
| `scanHandlers` | 反射扫描类层次的所有方法（一次性） |
| `AnnotatedHandler` 构造 | SpEL 解析 / filter 方法查找（一次性） |
| `register` | COW List 复制（写时复制） |
| `ensureSubscribed` | CHM put + 可能的 Nukkit registerEvent |

### 10.3 内存开销

| 数据结构 | 估算 |
|----------|------|
| 每个 AnnotatedHandler | ~100 bytes（对象头 + Method + Expression + 引用） |
| globalHandlers 每个条目 | ~50 bytes（CHM Node + COW List 引用） |
| keyedHandlers 每个条目 | ~80 bytes（两层 CHM Node + COW List 引用） |
| 100 个玩家的对象级处理器 | ~100 × 80 = 8 KB（可忽略） |

### 10.4 与旧方案对比

| 指标 | 旧方案（catch-all） | 新方案 |
|------|---------------------|--------|
| 无监听器时的事件开销 | O(N) 遍历所有 consumer | **O(0)** Nukkit 不触发 |
| 有监听器时的查找 | O(N) 遍历判断类型 | O(1) 按类型/Key 直接查找 |
| 线程安全 | ❌ 无保证 | ✅ CHM + COW |
| 内存 | 低（一个 List） | 略高（多层 Map），但可忽略 |

---

## 附录：快速定位指南

| 我想... | 看这个文件 | 看这个方法/字段 |
|---------|-----------|----------------|
| 理解事件怎么从 Nukkit 到达用户代码 | EventService.java | `doRegister()` + `dispatch()` |
| 理解事件怎么路由到正确的玩家 | ObjectEventRouter.java | `dispatch()` 的 ② 部分 |
| 理解 @NukkitEvent 怎么被发现的 | EventBeanPostProcessor.java | `postProcessAfterInitialization()` |
| 理解 SpEL 条件怎么工作的 | AnnotatedHandler.java | `passesCondition()` |
| 理解 filter 方法怎么被查找的 | AnnotatedHandler.java | `resolveFilterMethod()` |
| 理解事件类型怎么自动推断的 | AnnotatedHandler.java | `resolveEventTypeFromParameter()` |
| 理解 Key 怎么从事件中提取的 | KeyExtractorRegistry.java | `findExtractor()` + `resolveExtractor()` |
| 理解延迟注册怎么工作的 | EventService.java | `ensureRegistered()` + `setPlugin()` |
| 添加新的事件身份提取器 | KeyExtractorRegistry.java | `register()` 或 `registerDefaults()` |
| 修改事件分发顺序 | ObjectEventRouter.java | `dispatch()` |
