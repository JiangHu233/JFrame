# 事件系统 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，目的是让你 100% 理解每个类的内部实现、设计决策、线程安全模型，
> 并能在修改代码时知道**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个框架，请看 [README.md](README.md)。

---

## 目录

- [1. 设计哲学与核心问题](#1-设计哲学与核心问题)
- [2. 架构总览](#2-架构总览)
- [3. 运行时数据流（完整调用链）](#3-运行时数据流完整调用链)
- [4. 类逐一剖析](#4-类逐一剖析)
- [5. 线程安全分析](#5-线程安全分析)
- [6. 生命周期管理](#6-生命周期管理)
- [7. 扩展点](#7-扩展点)
- [8. 已知限制与陷阱](#8-已知限制与陷阱)
- [9. 修改指南（改代码前必读）](#9-修改指南改代码前必读)

---

## 1. 设计哲学与核心问题

### 1.1 旧方案的问题

旧版使用单一 `@NukkitEvent` 注解 + `ObjectEventRouter` + `RoutingSpec`，存在以下问题：

1. **身份提取耦合在注册时**：`register(wrapper, key)` 需要调用方手动传入身份 Key，与事件提取逻辑割裂
2. **实例创建不可控**：框架无法自动创建实例，必须手动 `new` + `register`
3. **独占语义局限**：`EventConsumer` 的返回值只能控制**同一优先级内**的独占，无法跨优先级
4. **RoutingSpec 冗余**：每个实例都要传一个 `RoutingSpec`，重复且易错

### 1.2 新方案的核心思路

**"让包装类自己声明身份提取方法"** —— 用 `@KeyExtractor` 标记 static 方法，框架在分发时自动调用。

**"让包装类自己控制实例创建"** —— 用 `@InstanceProvider` 标记 static 工厂方法，或让框架用默认缓存。

**"跨优先级独占"** —— `EventService` 固定 LOWEST 注册，所有事件在一次 `dispatch` 中完成优先级排序和独占判断。

### 1.3 为什么是 4 个注解？

| 注解 | 职责 | 为什么独立 |
|------|------|-----------|
| [`@EventRoute`](annotation/EventRoute.java) | 匹配：事件类型 + 条件 | 匹配逻辑与执行逻辑正交 |
| [`@EventHandler`](annotation/EventHandler.java) | 执行：优先级 + 独占 | 优先级是执行层概念，不是匹配层 |
| [`@KeyExtractor`](annotation/KeyExtractor.java) | 身份提取 | 必须 static，与实例方法分离 |
| [`@InstanceProvider`](annotation/InstanceProvider.java) | 实例创建 | 必须 static，控制实例生命周期 |

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户代码层                                    │
│                                                                     │
│  @EventHandler + @EventRoute (实例方法)                            │
│  @KeyExtractor (static 方法)                                       │
│  @InstanceProvider (static 方法)                                   │
│     ↓                                                               │
│  EventBeanPostProcessor 扫描 Spring Bean → register(bean)          │
│  或 HandlerRegistry.register(Class) 手动注册对象级                  │
├─────────────────────────────────────────────────────────────────────┤
│                         路由引擎层                                    │
│                                                                     │
│  HandlerRegistry                                                    │
│  ├── registry: 事件类型 → [WrapperRegistration]                   │
│  ├── dispatch(): 身份匹配 → 实例创建 → 优先级排序 → 独占分发       │
│  ├── WrapperRegistration: 类级元数据（提取器/工厂/模板）            │
│  └── HandlerTemplate: 不可变的方法封装（条件/优先级/独占）          │
│     ↓ subscribe(eventType, lambda)                                  │
├─────────────────────────────────────────────────────────────────────┤
│                         身份提取层                                    │
│                                                                     │
│  KeyExtractorRegistry                                               │
│  ├── extractors: 事件基类 → Extractor                              │
│  ├── cache: 具体事件类 → 解析后的提取器（含继承链查找）             │
│  └── NO_EXTRACTOR: 哨兵，缓存"无提取器"结果                        │
├─────────────────────────────────────────────────────────────────────┤
│                         事件引擎层                                    │
│                                                                     │
│  EventService (implements Listener)                                 │
│  ├── consumers: 事件类型 → [EventConsumer]                        │
│  ├── 固定 LOWEST 优先级注册                                         │
│  └── dispatch(): 转发给 HandlerRegistry                             │
│     ↓                                                               │
│  Nukkit 原生事件系统（按类型精准触发）                               │
└─────────────────────────────────────────────────────────────────────┘
```

### 依赖关系图

```
EventBeanPostProcessor ──→ HandlerRegistry ──→ EventService
                               │       │              │
                               │       │              └──→ Nukkit PluginManager
                               │       │
                               │       └──→ KeyExtractorRegistry
                               │
                               └──→ HandlerTemplate (不可变)
```

> **关键：** 依赖是单向的。L1 不知道 L2/L3 的存在。L3 通过 `ensureSubscribed()` 的 lambda 回调桥接 L1。

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
    ├── 创建 HandlerRegistry Bean
    │     └── 构造注入 EventService + KeyExtractorRegistry
    │
    ├── 创建 EventBeanPostProcessor Bean
    │     └── 构造注入 HandlerRegistry
    │
    └── 遍历所有 Bean → postProcessAfterInitialization()
          └── 对每个含 @EventHandler 的 Bean:
              ├── hasEventHandlerMethods() 快速检查
              └── handlerRegistry.register(bean)
                  ├── scanHandlers() 扫描所有 @EventHandler 方法
                  │     └── 为每个方法创建 HandlerTemplate
                  │         ├── 解析事件类型（@EventRoute value 或参数推断）
                  │         ├── 解析 SpEL condition（注册时解析一次）
                  │         └── 查找 filter 方法（注册时反射一次）
                  ├── 创建 WrapperRegistration（isGlobal=true）
                  └── addToRegistry()
                      └── ensureSubscribed(eventType)
                          └── eventService.subscribe(eventType, lambda)
                              ├── 加入 consumers map
                              └── ensureRegistered()
                                  └── plugin == null → 暂存到 pendingRegistrations
```

### 3.2 对象级注册阶段（手动调用 register(Class)）

```
用户调用 handlerRegistry.register(PlayerWrapper.class)
    │
    ├── scanExtractors() → 扫描 @KeyExtractor static 方法
    │     └── 按事件类型分组
    │
    ├── scanInstanceProvider() → 扫描 @InstanceProvider static 方法
    │     └── 无则使用默认缓存
    │
    ├── scanHandlers() → 扫描 @EventHandler + @EventRoute 方法
    │     └── 创建 HandlerTemplate 列表
    │
    ├── 创建 WrapperRegistration（isGlobal=false）
    │     └── 包含 extractors / instanceProvider / defaultCache / handlers
    │
    └── addToRegistry()
        └── 对每个事件类型 ensureSubscribed()
```

### 3.3 插件启用阶段（onEnable）

```
插件主类 onEnable()
    └── eventService.setPlugin(this)
        ├── 设置 this.plugin
        └── 遍历 pendingRegistrations
            └── doRegister(eventType)
                └── PluginManager.registerEvent(
                        eventType, EventService实例, LOWEST,
                        (listener, event) -> dispatch(eventType, event),
                        plugin, false)
```

### 3.4 事件触发阶段（运行时热路径）

```
Nukkit 触发 PlayerMoveEvent（玩家A移动）
    │
    ↓ Nukkit 内部按事件类型查找已注册的处理器
    │
    ↓ 找到 EventService 为 PlayerMoveEvent 注册的 lambda
    │
    EventService.dispatch(PlayerMoveEvent.class, event)
    │
    ├── consumers.get(PlayerMoveEvent.class) → List<EventConsumer>
    └── 遍历 list:
        └── consumer.handleEvent(event)  ← 返回值被忽略
            │
            ↓ 这个 consumer 是 HandlerRegistry.ensureSubscribed() 注册的 lambda:
            │
            HandlerRegistry.dispatch(PlayerMoveEvent.class, event)
            │
            ├── ① 预提取全局 Key（只提取一次）
            │   ├── globalExtractorRegistry.findExtractor(PlayerMoveEvent.class)
            │   │   └── 沿继承链: PlayerMoveEvent → PlayerEvent → 找到 getPlayer() 提取器
            │   └── extractor.extract(event) → PlayerA 对象
            │
            ├── ② 遍历每个 WrapperRegistration：
            │   ├── 全局处理器 → 直接使用单例实例
            │   ├── 有自定义 @KeyExtractor → 逐个调用（static），获取实例
            │   └── 无自定义 @KeyExtractor → 用全局 Key 获取实例
            │       └── getOrCreateInstance(reg, playerA)
            │           ├── 有 @InstanceProvider → 调用工厂方法
            │           └── 无 → 默认缓存 get/put（构造函数创建）
            │
            ├── ③ 收集所有匹配的 (instance, HandlerTemplate) 对
            │   └── 用 IdentityHashMap 去重（同一实例只处理一次）
            │
            ├── ④ 按优先级降序排序（HIGHEST → LOWEST）
            │
            └── ⑤ 分组分发 + 跨优先级独占
                ├── 遍历排序后的列表
                ├── 进入新优先级组时，检查上一组是否声明独占
                │   └── 是 → break，停止所有低优先级
                └── 调用 template.handle(instance, event)
                    ├── passesCondition(instance, event)
                    │   ├── filterMethod != null → filterMethod.invoke(instance, event)
                    │   └── conditionExpression != null → SpEL 求值
                    ├── method.invoke(instance, event)  ← 反射调用用户方法
                    └── 返回 exclusive 标记
```

---

## 4. 类逐一剖析

### 4.1 EventService（L1 引擎）

**文件：** [`EventService.java`](EventService.java)
**角色：** 对接 Nukkit 底层事件系统，按事件类型精准注册和分发。
**Spring 注解：** `@Component`，实现 `cn.nukkit.event.Listener`

#### 关键设计：固定 LOWEST 优先级

```java
public static final EventPriority REGISTER_PRIORITY = EventPriority.LOWEST;
```

**为什么固定 LOWEST？** Nukkit 按优先级从 LOWEST 到 MONITOR 分层调用。如果本类在不同优先级注册，Nukkit 会分多次调用 `dispatch`，导致 `HandlerRegistry` 无法在一次调用中完成跨优先级的独占判断。固定 LOWEST 后，所有事件只触发一次 `dispatch`，`HandlerRegistry` 内部按 `@EventHandler` 的 priority 排序并处理独占。

#### 字段详解

| 字段 | 类型 | 线程安全 | 用途 |
|------|------|----------|------|
| `consumers` | `ConcurrentHashMap<Class<? extends Event>, List<EventConsumer<?>>>` | 线程安全 | 消费者注册表。内层 List 用 `CopyOnWriteArrayList` 保证遍历安全 |
| `registered` | `Set<Class<? extends Event>>`（`ConcurrentHashMap.newKeySet()`） | 线程安全 | 已向 Nukkit 注册的事件类型集合，防止重复注册 |
| `pendingRegistrations` | `List<Class<? extends Event>>` | **非线程安全** | 延迟注册队列。仅在 Spring 初始化阶段使用 |
| `plugin` | `Plugin` | volatile 语义 | 关联的插件实例 |

#### 方法详解

| 方法 | 可见性 | 核心逻辑 |
|------|--------|----------|
| `subscribe(type, consumer)` | public | `computeIfAbsent` 创建 List，add consumer，`ensureRegistered` |
| `unsubscribe(type, consumer)` | public | 从 List 中 remove |
| `setPlugin(plugin)` | public | 设置 plugin，刷新 pendingRegistrations |
| `ensureRegistered(type)` | private | 检查 `registered`，未注册则 `doRegister` 或暂存 |
| `doRegister(type)` | private | 调用 `PluginManager.registerEvent()`，固定 LOWEST |
| `dispatch(type, event)` | private | 遍历 consumers，调用 `handleEvent`，**返回值被忽略** |

#### 修改注意事项

- **dispatch 中返回值被忽略**：独占逻辑完全由 `HandlerRegistry` 管理。如果修改 `dispatch` 让它处理返回值，会与 `HandlerRegistry` 的独占逻辑冲突。
- **不要添加 priority 参数**：优先级维度已移到 `HandlerRegistry` 内部管理。

---

### 4.2 EventConsumer（L1 函数式接口）

**文件：** [`EventConsumer.java`](EventConsumer.java)

```java
@FunctionalInterface
public interface EventConsumer<T extends Event> {
    boolean handleEvent(T event);
}
```

> **注意：** 返回值在 `EventService.dispatch` 中**被忽略**。独占语义由 `HandlerRegistry` 内部通过 `HandlerTemplate.handle()` 的返回值管理。此接口保留返回值仅为向后兼容和潜在的直接使用场景。

---

### 4.3 @EventRoute（匹配层注解）

**文件：** [`annotation/EventRoute.java`](annotation/EventRoute.java)

| 属性 | 类型 | 默认值 | 语义 |
|------|------|--------|------|
| `value` | `Class<? extends Event>` | `Event.class` | 事件类型。`Event.class` = 哨兵，触发自动推断 |
| `condition` | `String` | `""` | SpEL 表达式。空 = 无条件 |
| `filter` | `String` | `""` | 类中筛选方法名。空 = 无 filter |

**关键决策：**
- `value` 默认 `Event.class` 而非 `null`：Java 注解属性不能是 `null`
- `condition` 和 `filter` 互斥：在 `HandlerTemplate` 构造函数中检查

---

### 4.4 @EventHandler（执行层注解）

**文件：** [`annotation/EventHandler.java`](annotation/EventHandler.java)

| 属性 | 类型 | 默认值 | 语义 |
|------|------|--------|------|
| `priority` | `EventPriority` | `NORMAL` | 执行优先级 |
| `exclusive` | `boolean` | `false` | 跨优先级独占 |

**独占语义详解：**
- `exclusive = true` 的处理器**执行后**（通过条件检查），所有**更低优先级**的处理器都不会执行
- 同一优先级内的其他处理器仍会执行（同级顺序不明确）
- 独占检查发生在**优先级组切换时**，不是每次调用后

---

### 4.5 @KeyExtractor（身份提取标记）

**文件：** [`annotation/KeyExtractor.java`](annotation/KeyExtractor.java)

空注解（标记注解），无属性。

**规则：**
- 必须 `static`（提取时实例尚未创建）
- 签名：`static IdentityType extract(EventType event)`
- 事件类型从方法第一个参数推断
- 同一事件类型可有多个 `@KeyExtractor`（OR 语义）

---

### 4.6 @InstanceProvider（实例工厂标记）

**文件：** [`annotation/InstanceProvider.java`](annotation/InstanceProvider.java)

空注解（标记注解），无属性。

**规则：**
- 必须 `static`
- 签名：`static WrapperType create(IdentityType identity)`
- 参数类型与 `@KeyExtractor` 返回类型一致
- 返回 `null` = 不路由
- 无此注解时使用默认缓存

---

### 4.7 KeyExtractorRegistry（L2 注册表）

**文件：** [`routing/KeyExtractorRegistry.java`](routing/KeyExtractorRegistry.java)
**Spring 注解：** `@Component`

#### NO_EXTRACTOR 哨兵

```java
private static final Extractor NO_EXTRACTOR = event -> null;
```

**为什么需要哨兵？** `ConcurrentHashMap.computeIfAbsent` 不允许 null value。如果 `resolveExtractor` 返回 null（无提取器），不缓存会导致每次事件都重新遍历继承链。用 `NO_EXTRACTOR` 哨兵代替 null，可以被缓存。

`findExtractor` 返回时检查：`return extractor == NO_EXTRACTOR ? null : extractor;`

#### 字段详解

| 字段 | 类型 | 用途 |
|------|------|------|
| `extractors` | `ConcurrentHashMap<Class<? extends Event>, Extractor>` | 直接映射表（精确注册的类型） |
| `cache` | `ConcurrentHashMap<Class<? extends Event>, Extractor>` | 查找缓存（继承链查找结果，含 NO_EXTRACTOR） |

#### 方法详解

| 方法 | 核心逻辑 |
|------|----------|
| `register(type, extractor)` | 放入 `extractors`，**清空 `cache`** |
| `findExtractor(eventClass)` | `cache.computeIfAbsent(eventClass, this::resolveExtractor)`，返回时过滤 NO_EXTRACTOR |
| `resolveExtractor(eventClass)` | 沿继承链 `getSuperclass()` 查找，找到返回提取器，否则返回 NO_EXTRACTOR |
| `registerDefaults()` | 构造函数调用，反射注册 4 个预置提取器 |
| `registerByReflection(className, methodName)` | `Class.forName` + `getMethod`，失败静默跳过 |

---

### 4.8 HandlerTemplate（L3 处理器封装）

**文件：** [`routing/HandlerTemplate.java`](routing/HandlerTemplate.java)
**角色：** 不可变的、线程安全的事件处理器封装。

#### 与旧版 AnnotatedHandler 的关键区别

| 特性 | 旧版 AnnotatedHandler | 新版 HandlerTemplate |
|------|----------------------|---------------------|
| target 存储 | 构造时传入，固定 | **不存储**，handle 时传入 |
| 注解来源 | 单一 `@NukkitEvent` | `@EventRoute` + `@EventHandler` |
| handle 返回值 | void | `boolean`（true = 声明独占） |
| 共享性 | 一个实例一个 Handler | 同一模板可被多个实例共享 |

#### 字段详解

| 字段 | 类型 | 可变性 | 用途 |
|------|------|--------|------|
| `SPEL_PARSER` | `SpelExpressionParser`（static final） | 不可变 | 全局共享的 SpEL 解析器 |
| `declaringClass` | `Class<?>` | final | 声明此方法的类（用于 filter 查找） |
| `method` | `Method` | final | 要调用的方法（已 `setAccessible`） |
| `eventType` | `Class<? extends Event>` | final | 解析后的事件类型 |
| `conditionExpression` | `Expression` | final | 预编译 SpEL，null = 无 SpEL |
| `filterMethod` | `Method` | final | filter 方法，null = 无 filter |
| `priority` | `EventPriority` | final | 优先级 |
| `exclusive` | `boolean` | final | 跨优先级独占标记 |

#### handle 方法逻辑

```java
public boolean handle(Object target, Event event) {
    if (!passesCondition(target, event)) return false;  // 条件不满足，未执行
    method.invoke(target, event);                        // 执行
    return exclusive;                                    // 返回独占标记
}
```

**返回值语义：**
- `false` = 未执行（条件不满足）或执行了但不独占
- `true` = 执行了且声明独占

---

### 4.9 HandlerRegistry（L3 核心路由）

**文件：** [`routing/HandlerRegistry.java`](routing/HandlerRegistry.java)
**角色：** 统一管理全局/对象处理器的注册、身份匹配、优先级分发。

#### WrapperRegistration 记录

```java
record WrapperRegistration(
    Class<?> wrapperClass,
    boolean isGlobal,                    // true = Spring Bean 单例
    Object singletonInstance,            // 全局处理器的固定实例
    Method instanceProvider,             // @InstanceProvider 方法
    ConcurrentHashMap<Object, Object> defaultCache,  // 默认缓存
    Map<Class<? extends Event>, List<Method>> extractors,  // @KeyExtractor 方法
    Map<Class<? extends Event>, List<HandlerTemplate>> handlers  // 处理器模板
) {}
```

#### 核心存储

| 字段 | 类型 | 用途 |
|------|------|------|
| `registry` | `ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<WrapperRegistration>>` | 事件类型 → 注册信息列表 |
| `classToReg` | `ConcurrentHashMap<Class<?>, WrapperRegistration>` | 类 → 注册信息（反向索引，用于注销） |
| `subscribed` | `Set<Class<? extends Event>>` | 已订阅的事件类型 |
| `GLOBAL` | `Object`（static final） | 全局身份常量 |

#### dispatch 方法核心逻辑

```
dispatch(eventType, event)
    │
    ├── ① 预提取全局 Key（只提取一次）
    │   └── 所有用全局提取器的 Registration 共享同一个 globalKey
    │
    ├── ② 遍历每个 WrapperRegistration：
    │   ├── isGlobal → 直接使用 singletonInstance
    │   └── 对象处理器 → collectObjectHandlers()
    │       ├── 有自定义 @KeyExtractor → 逐个调用，获取实例
    │       └── 无 → 用 globalKey 获取实例
    │
    ├── ③ 收集匹配的 (instance, template) 对
    │   └── IdentityHashMap 去重（同一实例只处理一次）
    │
    ├── ④ 按优先级降序排序
    │   └── Comparator.reverseOrder()，HIGHEST 在前
    │
    └── ⑤ 分组分发 + 跨优先级独占
        ├── currentGroup 跟踪当前优先级组
        ├── exclusiveClaimed 标记上一组是否声明独占
        ├── 进入新组时：if (exclusiveClaimed) break;
        └── 调用 template.handle(instance, event)
```

#### 跨优先级独占实现

```java
EventPriority currentGroup = null;
boolean exclusiveClaimed = false;

for (BoundHandler bh : matched) {
    EventPriority handlerPriority = bh.template().getPriority();

    // 检查是否进入新的优先级组
    if (currentGroup != null && handlerPriority != currentGroup) {
        if (exclusiveClaimed) {
            break; // 上一组有独占声明，停止所有低优先级
        }
        currentGroup = handlerPriority;
        exclusiveClaimed = false;
    }
    if (currentGroup == null) {
        currentGroup = handlerPriority;
    }

    if (bh.template().handle(bh.instance(), event)) {
        exclusiveClaimed = true;
    }
}
```

**关键点：**
- 独占检查只在**优先级组切换时**发生，不是每次调用后
- 同一优先级内的所有处理器都会执行（即使某个声明了 exclusive）
- 只有当进入**更低优先级组**时，才检查上一组是否独占

#### 实例创建逻辑

```
getOrCreateInstance(reg, identity)
    │
    ├── 有 @InstanceProvider → 调用工厂方法
    │   └── instanceProvider.invoke(null, identity)
    │
    └── 无 → 默认缓存
        ├── cache.get(identity) → 命中则返回
        └── 未命中 → constructViaConstructor()
            ├── 查找第一个参数兼容 identity 类型的构造函数
            ├── 额外参数：HandlerRegistry 类型自动注入 this
            └── cache.put(identity, instance)
```

---

### 4.10 EventBeanPostProcessor（Spring 桥接）

**文件：** [`spring/EventBeanPostProcessor.java`](spring/EventBeanPostProcessor.java)
**角色：** 自动扫描 Spring Bean 中的 `@EventHandler` 方法并注册为全局处理器。

**工作流程：**
1. Spring 创建任何 Bean 后调用 `postProcessAfterInitialization`
2. `hasEventHandlerMethods()` 快速检查（沿继承链扫描）
3. 若有 `@EventHandler` 方法 → `handlerRegistry.register(bean)`

**注意：** 仅扫描 Spring 单例 Bean。动态创建的包装类需手动调用 `register(Class)`。

---

## 5. 线程安全分析

### 5.1 并发数据结构选择

| 数据结构 | 用途 | 理由 |
|----------|------|------|
| `ConcurrentHashMap` | 所有注册表 | 高并发读写 |
| `CopyOnWriteArrayList` | 事件类型 → Registration 列表 | 读多写少（注册少，分发多） |
| `IdentityHashMap`（via `Collections.newSetFromMap`） | dispatch 去重 | 按对象身份（==）去重，非 equals |

### 5.2 dispatch 的线程安全

`dispatch` 方法本身**无锁**，依赖：
- `registry` 的 `CopyOnWriteArrayList` 保证遍历安全
- `HandlerTemplate` 不可变
- `WrapperRegistration` 是 record（不可变）
- `defaultCache` 是 `ConcurrentHashMap`

**潜在竞态：** 两个线程同时处理同一玩家的首次事件，可能同时进入 `getOrCreateInstance` 的 `cache.put`。由于 `ConcurrentHashMap.put` 是原子的，最终只会有一个实例被缓存，但可能创建了两个实例（一个被 GC）。对于无副作用的构造函数，这是可接受的。

### 5.3 非线程安全的部分

- `pendingRegistrations`（EventService）：仅在 Spring 初始化阶段（单线程）使用
- `scanHandlers` 等扫描方法：仅在注册时调用，注册通常在启动阶段

---

## 6. 生命周期管理

### 6.1 全局处理器（Spring Bean）

```
Spring 容器创建 Bean
    ↓
EventBeanPostProcessor 扫描 @EventHandler
    ↓
HandlerRegistry.register(bean) → 创建 WrapperRegistration
    ↓
Bean 销毁时 → 需手动 unregister(bean.getClass())
```

> **注意：** 当前实现没有自动注销 Spring Bean 的机制。如果 Bean 被销毁（罕见），需手动调用 `unregister`。

### 6.2 对象处理器（默认缓存）

```
register(Class) → 创建 WrapperRegistration（含 defaultCache）
    ↓
首次事件 → getOrCreateInstance → 构造函数创建 → cache.put
    ↓
后续事件 → cache.get（O(1)）
    ↓
对象销毁（如玩家下线）→ evict(Class, identity) → cache.remove
    ↓
unregister(Class) → 清空整个 defaultCache
```

### 6.3 对象处理器（@InstanceProvider 工厂）

```
register(Class) → 创建 WrapperRegistration（instanceProvider != null）
    ↓
每次事件 → instanceProvider.invoke(null, identity)
    ↓
工厂方法自行管理实例缓存（如静态 ConcurrentHashMap）
    ↓
unregister(Class) → 不清理工厂的缓存（需工厂自行清理）
```

---

## 7. 扩展点

### 7.1 添加新的全局提取器

```java
@Component
public class MyConfig {
    public MyConfig(KeyExtractorRegistry registry) {
        registry.register(MyCustomEvent.class, event -> event.getOwner());
    }
}
```

### 7.2 自定义实例创建策略

通过 `@InstanceProvider` 实现：
- **对接外部缓存**：`return ExternalManager.getWrapper(identity);`
- **一次性实例**：`return new OneTimeHandler(identity);`
- **条件创建**：`return shouldCreate ? new Wrapper(identity) : null;`

### 7.3 扩展构造函数注入

当前 `constructViaConstructor` 只注入 `HandlerRegistry` 类型参数。如需注入其他依赖：

```java
// 在 constructViaConstructor 中添加：
for (int i = 1; i < args.length; i++) {
    Class<?> paramType = matched.getParameterTypes()[i];
    if (paramType == HandlerRegistry.class) {
        args[i] = this;
    } else if (paramType == SomeService.class) {
        args[i] = springContext.getBean(SomeService.class);
    }
}
```

---

## 8. 已知限制与陷阱

### 8.1 同级处理器顺序不明确

同一优先级内的多个处理器，执行顺序取决于 `CopyOnWriteArrayList` 的迭代顺序（即注册顺序）。但注册顺序受 Spring Bean 初始化顺序影响，**不保证确定性**。

**解决方案：** 如需明确顺序，使用不同优先级。

### 8.2 默认缓存的竞态

两个线程同时处理同一玩家的首次事件，可能创建两个实例。最终 `cache.put` 只保留一个，另一个被 GC。

**影响：** 对于有副作用的构造函数（如注册到外部系统），可能导致重复注册。

**解决方案：** 使用 `@InstanceProvider` 自行管理缓存（如 `computeIfAbsent`）。

### 8.3 全局处理器不应有 @KeyExtractor

`register(Object bean)` 会检查并打印警告。全局处理器的身份恒为 `GLOBAL`，`@KeyExtractor` 方法会被忽略。

### 8.4 unregister 不清理工厂缓存

`unregister(Class)` 只清理 `HandlerRegistry` 内部的 `defaultCache`。如果使用了 `@InstanceProvider`，工厂方法维护的静态缓存需自行清理。

### 8.5 MONITOR 优先级的特殊性

按 Nukkit 惯例，`MONITOR` 用于"只观察不修改"。但在本框架中，`MONITOR` 的优先级值最高（在 `EventPriority` 枚举中），会**最先执行**。如果 `MONITOR` 处理器声明 `exclusive = true`，会阻止所有其他处理器。

**建议：** 不要给 `MONITOR` 处理器设置 `exclusive = true`。

---

## 9. 修改指南（改代码前必读）

### 9.1 修改注解

- **添加新属性**：必须提供默认值（Java 注解要求）。同步更新 `HandlerTemplate` 构造函数。
- **修改 `@EventRoute` 的哨兵值**：同步修改 `HandlerTemplate` 中的 `if (type == Event.class)` 判断。
- **修改 `@EventHandler` 的 priority/exclusive**：同步修改 `HandlerRegistry.dispatch` 的排序和独占逻辑。

### 9.2 修改 HandlerRegistry.dispatch

- **修改排序逻辑**：注意 `Comparator.reverseOrder()` 的方向。HIGHEST 必须在前。
- **修改独占逻辑**：注意独占检查只在**优先级组切换时**发生。如果改为每次调用后检查，同级处理器也会被独占。
- **修改去重逻辑**：`IdentityHashMap` 按对象身份去重。如果改为 `equals` 去重，可能导致同一逻辑对象被多次处理。

### 9.3 修改实例创建

- **修改 `constructViaConstructor`**：注意构造函数查找逻辑（第一个参数兼容 identity）。如果改为精确匹配，可能找不到构造函数。
- **修改 `getOrCreateInstance`**：注意 `@InstanceProvider` 和默认缓存的优先级。

### 9.4 修改 KeyExtractorRegistry

- **修改 `register`**：注意清空 `cache`。如果不清空，新注册的提取器不会生效。
- **修改 `findExtractor`**：注意 NO_EXTRACTOR 哨兵的过滤。如果不过滤，调用方会收到一个返回 null 的提取器。

### 9.5 修改 EventService

- **不要添加 priority 参数**：优先级维度已移到 HandlerRegistry。
- **不要修改 dispatch 的返回值处理**：返回值被忽略是设计决策，独占由 HandlerRegistry 管理。
- **修改 REGISTER_PRIORITY**：如果改为非 LOWEST，可能导致 Nukkit 在其他优先级层还有处理器时，本框架的 dispatch 被延迟调用。
