# 事件系统 (Event System)

> 基于 Nukkit + Spring 的高性能、面向对象的事件路由框架。
>
> 通过 **4 个注解** 声明事件处理器，框架自动完成类型过滤、身份提取、实例创建和优先级分发。
>
> **所有处理器都是基于身份标识的对象处理器**：每个包装类必须声明 `@KeyExtractor` 从事件中提取身份，框架据此路由到对应实例。

---

## 📖 这个框架是干什么的？

让服务器里的各种事件（玩家移动、聊天、放方块等）自动找到正确的处理代码去执行，并且：

- **没人监听的事件类型完全不触发**（Nukkit 原生按类型注册）
- **每个对象（玩家/方块/实体/物品）拥有独立实例**，只收到属于自己的事件
- **高优先级处理器可独占事件**，阻止低优先级处理器执行

### 核心概念：4 个注解

| 注解 | 作用 | 标注位置 | 必填 |
|------|------|----------|------|
| [`@EventHandler`](annotation/EventHandler.java) | 标记方法为事件处理器，声明**优先级**和**独占** | 实例方法 | ✅ |
| [`@EventRoute`](annotation/EventRoute.java) | 声明处理**什么事件**、在**什么条件**下处理 | 实例方法 | ✅（可与 @EventHandler 合并省略） |
| [`@KeyExtractor`](annotation/KeyExtractor.java) | 标记 **static 方法**为身份提取器（从事件提取身份标识） | static 方法 | ✅ |
| [`@InstanceProvider`](annotation/InstanceProvider.java) | 标记 **static 方法**为实例工厂（按需创建/查找实例） | static 方法 | ❌ |

> **为什么拆成 4 个注解？** 职责分离：`@EventRoute` 管"匹配"（事件类型+条件），`@EventHandler` 管"执行"（优先级+独占），`@KeyExtractor` 管"身份提取"，`@InstanceProvider` 管"实例创建"。每个注解只做一件事。
>
> **⚠️ `@KeyExtractor` 是必需的**：没有身份提取器，框架无法确定事件该路由给哪个实例，注册会被拒绝。

---

## 🏗️ 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  用户代码层                                               │
│  @EventHandler + @EventRoute + @KeyExtractor +          │
│  @InstanceProvider                                      │
├─────────────────────────────────────────────────────────┤
│  路由引擎层                                               │
│  HandlerRegistry —— 身份提取、实例创建、优先级分发、独占    │
├─────────────────────────────────────────────────────────┤
│  事件引擎层                                               │
│  EventService —— 对接 Nukkit，按事件类型精准注册          │
└─────────────────────────────────────────────────────────┘
```

### 各层职责

| 层 | 类 | 职责 |
|----|----|------|
| L1 | [`EventService`](EventService.java) | 向 Nukkit 注册真正有人监听的事件类型，固定 LOWEST 优先级 |
| L2 | [`HandlerRegistry`](routing/HandlerRegistry.java) | 身份提取、实例创建、按优先级排序、跨优先级独占分发 |
| L3 | 4 个注解 | 用户声明处理器、提取器、工厂 |

---

## 🚀 快速上手

### 前置：初始化框架

在插件主类 `onEnable()` 中设置 Plugin 实例：

```java
@Override
public void onEnable() {
    ApplicationContext ctx = ...;
    EventService eventService = ctx.getBean(EventService.class);
    eventService.bindPlugin(this);  // 必须！
}
```

---

### 用法一：基础对象处理器（每个玩家独立实例）

每个玩家/方块/实体需要独立的处理逻辑和数据。声明 `@KeyExtractor` 提取身份，框架自动创建并缓存实例。

```java
// 注意：不加 @Component！这是框架按需创建的对象
public class PlayerWrapper {

    private final Player player;
    private final EventService eventService;

    public PlayerWrapper(Player player, EventService eventService) {
        this.player = player;
        this.eventService = eventService;
    }

    // ① 身份提取器：从事件提取 Player（必需）
    @KeyExtractor
    public static Player extractPlayer(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    // 只会收到【这个玩家】的移动事件
    @EventHandler
    @EventRoute
    public void onMove(PlayerMoveEvent event) {
        player.sendMessage("你走到了 " + event.getTo());
    }

    // 玩家退出时清理缓存
    @EventHandler
    @EventRoute
    public void onSelfQuit(PlayerQuitEvent event) {
        eventService.evict(PlayerWrapper.class, player);
    }
}
```

**注册（只需一次）：**

```java
// 在 onEnable 中注册类
eventService.register(PlayerWrapper.class);

// 之后无需任何手动创建代码！
// 玩家A移动 → extractPlayer 提取 playerA → new PlayerWrapper(playerA, eventService) → 缓存 → 调用 onMove
// 玩家B移动 → extractPlayer 提取 playerB → new PlayerWrapper(playerB, eventService) → 缓存 → 调用 onMove
```

**工作原理：**
1. 框架注册 `PlayerWrapper.class` 时扫描所有 `@KeyExtractor` 和 `@EventHandler` 方法
2. 事件到达时，调用 `@KeyExtractor` 提取身份（如 Player）
3. 首次收到某身份时，框架通过构造函数创建实例并缓存（无 `@InstanceProvider` 时）
4. 后续事件从缓存获取实例（O(1)）
5. 对象销毁时调用 `evict()` 清理缓存

> **💡 简写：** 如果只写 `@EventHandler` 不写 `@EventRoute`，框架会用默认的 `@EventRoute`（自动推断事件类型、无条件）。

---

### 用法二：自定义工厂 @InstanceProvider

当实例不能直接通过构造函数创建（需从外部注册表查找、需额外参数）时，用 `@InstanceProvider`。

```java
public class MagicSwordWrapper {

    private static final Map<Item, MagicSwordWrapper> SWORDS = new ConcurrentHashMap<>();
    private final Item sword;

    public MagicSwordWrapper(Item sword) { this.sword = sword; }

    public static MagicSwordWrapper registerSword(Item sword) {
        MagicSwordWrapper w = new MagicSwordWrapper(sword);
        SWORDS.put(sword, w);
        return w;
    }

    // ① 提取器：从事件提取 Item（手中的剑）
    @KeyExtractor
    public static Item extractItem(PlayerInteractEvent event) {
        return event.getItem();
    }

    // ② 工厂：根据 Item 查找已注册的魔法剑
    @InstanceProvider
    public static MagicSwordWrapper findByItem(Item item) {
        return item == null ? null : SWORDS.get(item);
    }

    @EventHandler
    @EventRoute(filter = "isRightClickBlock")
    public void onUse(PlayerInteractEvent event) {
        System.out.println("触发魔法效果！");
    }

    private boolean isRightClickBlock(PlayerInteractEvent event) {
        return event.getAction().name().equals("RIGHT_CLICK_BLOCK");
    }
}
```

**注册：**
```java
eventService.register(MagicSwordWrapper.class);
MagicSwordWrapper.registerSword(magicSwordItem);
```

---

### 用法三：条件过滤（SpEL 表达式）

只在满足特定条件时处理事件。

```java
public class CreativeOnlyWrapper {

    @KeyExtractor
    public static Player extract(PlayerEvent event) {
        return event.getPlayer();
    }

    // SpEL 条件：只在创造模式时处理
    @EventHandler
    @EventRoute(condition = "#event.player.gamemode == 1")
    public void onCreative(PlayerEvent event) {
        event.getPlayer().sendMessage("你是创造模式！");
    }
}
```

**SpEL 表达式说明：**
- `#event` — 事件对象本身
- `#target` — 处理器对象（即 `this`）
- 可调用任何 getter：`#event.player`、`#event.message`
- 支持比较运算：`==`、`!=`、`>`、`<`、`and`、`or`
- 表达式在**注册时解析一次**并缓存，运行时反复求值，开销极小

---

### 用法四：复杂条件过滤（filter 方法引用）⭐

条件逻辑太复杂，SpEL 写不下时，用 `filter` 引用一个 Java 方法。

```java
public class ChatFilterWrapper {

    @KeyExtractor
    public static Player extract(PlayerChatEvent event) {
        return event.getPlayer();
    }

    @EventHandler
    @EventRoute(filter = "isNotSpam")
    public void onNormalChat(PlayerChatEvent event) {
        System.out.println("正常消息: " + event.getMessage());
    }

    // 筛选方法：参数兼容事件类型，返回 boolean，可以是 private
    private boolean isNotSpam(PlayerChatEvent event) {
        String msg = event.getMessage();
        if (msg.length() < 2) return false;
        return !isAllSameChar(msg);
    }

    private boolean isAllSameChar(String msg) { /* ... */ return false; }
}
```

**`condition` vs `filter` 对比：**

| | `condition`（SpEL） | `filter`（方法引用） |
|---|---|---|
| 适合 | 简单属性比较 | 复杂多条件逻辑 |
| IDE 补全 | ❌ | ✅ |
| 编译检查 | ❌ 运行时发现错误 | ✅ 编译时发现 |
| 性能 | SpEL 求值（略慢） | 直接方法调用（最快） |

> **⚠️ `condition` 和 `filter` 不能同时使用。**

---

### 用法五：优先级与独占 ⭐ 核心特性

高优先级处理器可以**独占**事件，阻止低优先级处理器执行。

```java
public class ChatGuardWrapper {

    @KeyExtractor
    public static Player extract(PlayerChatEvent event) {
        return event.getPlayer();
    }

    // HIGH 优先级 + 独占：执行后，NORMAL 及以下的处理器都不会执行
    @EventHandler(priority = EventPriority.HIGH, exclusive = true)
    @EventRoute
    public void onChatHigh(PlayerChatEvent event) {
        if (isBannedWord(event.getMessage())) {
            event.setCancelled(true);
            // exclusive = true → 低优先级处理器被跳过
        }
    }
}
```

**优先级顺序**（从先到后执行）：
```
HIGHEST → HIGH → NORMAL → LOW → LOWEST → MONITOR
```

**独占语义：**
- `exclusive = true` 的处理器**执行后**（通过条件检查），所有**更低优先级**的处理器都不会执行
- **同一优先级**内的多个处理器，执行顺序不明确（如需明确顺序请用不同优先级）
- 独占只影响比当前优先级更低的处理器，同级其他处理器仍会执行

---

### 用法六：多槽位身份提取（OR 语义）

一个事件可能包含多个同类型对象（如攻击者和受害者都是 Entity）。

```java
public class CombatantWrapper {

    // 槽位 1：作为攻击者
    @KeyExtractor
    public static Entity asDamager(EntityDamageByEntityEvent event) {
        return event.getDamager();
    }

    // 槽位 2：作为受害者
    @KeyExtractor
    public static Entity asVictim(EntityDamageByEntityEvent event) {
        return event.getEntity();
    }

    // 无论是作为攻击者还是受害者，都会触发（OR 语义，先匹配的先执行）
    @EventHandler
    @EventRoute
    public void onCombat(EntityDamageByEntityEvent event) { ... }
}
```

框架逐一尝试每个 `@KeyExtractor`，任一匹配即路由到对应实例。

---

### 💡 如何实现"全局单例"语义？

统一后所有处理器都是对象级（按身份路由）。如果某个处理器需要"所有事件路由到同一个实例"（类似经典 Listener 的单例监听），用 `@KeyExtractor` 返回恒定身份 + `@InstanceProvider` 返回固定单例：

```java
public class GlobalChatLogger {

    private static final GlobalChatLogger INSTANCE = new GlobalChatLogger();

    // 提取一个恒定身份（所有同类事件都映射到同一实例）
    @KeyExtractor
    public static Object extract(PlayerChatEvent event) {
        return Boolean.TRUE;
    }

    // 工厂始终返回同一个实例
    @InstanceProvider
    public static GlobalChatLogger get(Object identity) {
        return INSTANCE;
    }

    @EventHandler
    @EventRoute
    public void onChat(PlayerChatEvent event) {
        System.out.println(event.getPlayer().getName() + ": " + event.getMessage());
    }
}
```

---

## 📚 所有 API 详解

### `@EventHandler`（执行层）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `priority` | `EventPriority` | `NORMAL` | 执行优先级，HIGHEST → LOWEST 降序分发 |
| `exclusive` | `boolean` | `false` | 跨优先级独占：执行后停止所有低优先级处理器 |

---

### `@EventRoute`（匹配层）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `Class<? extends Event>` | 自动推断 | 事件类型，不填时从方法参数推断 |
| `condition` | `String` (SpEL) | `""` | SpEL 过滤条件，满足才执行 |
| `filter` | `String` | `""` | 引用类中的筛选方法名（与 condition 互斥） |

---

### `@KeyExtractor`（身份提取，必需）

标记一个 **static 方法**为身份提取器。

**规则：**
- 必须 `static`（提取身份时实例尚未创建）
- 签名：`static IdentityType extract(EventType event)`
- 返回值 = 身份标识；返回 `null` = 该事件不含此槽位，跳过
- 同一事件类型可有多个 `@KeyExtractor`（OR 语义）
- **每个包装类必须至少声明一个**，否则注册被拒绝

---

### `@InstanceProvider`（实例工厂）

标记一个 **static 方法**为实例工厂。

**规则：**
- 必须 `static`（工厂在实例创建之前调用）
- 签名：`static WrapperType create(IdentityType identity)`
- 参数类型与 `@KeyExtractor` 返回值一致
- 返回值 = 实例；返回 `null` = 不路由
- 无 `@InstanceProvider` 时，框架使用默认缓存（通过构造函数创建）

**何时需要 `@InstanceProvider`？**
- 实例需要额外参数（如箱子格子序号）
- 实例需要从外部注册表查找（如已注册的区域）
- 需要自定义缓存策略（如外部缓存、一次性实例、全局单例）

---

### `EventService`（用户入口）

| 方法 | 说明 |
|------|------|
| `register(Class<?> wrapperClass)` | 注册包装类为对象处理器（必须含 `@KeyExtractor`） |
| `unregister(Class<?> wrapperClass)` | 注销整个包装类 |
| `evict(Class<?> wrapperClass, Object identity)` | 从默认缓存驱逐特定身份的实例 |
| `bindPlugin(Plugin plugin)` | 绑定插件实例（框架自动调用） |

---

## 📂 完整示例

| 示例 | 场景 | 关键特性 |
|------|------|---------|
| [`MagicSwordWrapper`](../../../test/java/example/MagicSwordWrapper.java) | 特定物品右键触发 | 自定义 `@KeyExtractor` + `@InstanceProvider` 工厂 |
| [`ExampleItem`](../../../test/java/example/ExampleItem.java) | 自定义标识类型提取 | `@KeyExtractor` 返回自定义 `Identifier` 记录 |

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
