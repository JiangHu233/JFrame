# 事件系统 (Event System)

> 一个基于 Nukkit + Spring 的高性能、面向对象的事件路由框架。

---

## 📖 这个框架是干什么的？

简单来说：**让服务器里的各种事件（玩家移动、聊天、放方块等）能自动找到正确的处理代码去执行，而且不会拖慢服务器。**

### 举个例子理解

想象一个场景：服务器上有 100 个玩家在线，你想给每个玩家单独记录"走了多少步"。

| 做法 | 问题 |
|------|------|
| ❌ 传统做法：写一个监听器，所有玩家的移动事件都发给你，你自己判断"这个事件是哪个玩家的" | 100 个玩家每次移动都要经过你的代码，即使你只关心其中 1 个 |
| ✅ 本框架做法：给每个玩家创建一个"包装类"，框架自动只把**那个玩家**的事件发给对应的包装类 | 每个包装类只收到自己的事件，干净利落 |

---

## 🏗️ 整体架构（四层）

```
┌─────────────────────────────────────────────────────────┐
│  L4 用户代码层（你写的代码）                              │
│  @NukkitEvent 注解 + 包装类 / Spring Bean               │
│  ↑ 你只需要关心这一层                                     │
├─────────────────────────────────────────────────────────┤
│  L3 对象路由层                                            │
│  ObjectEventRouter —— 把事件分发给正确的对象              │
├─────────────────────────────────────────────────────────┤
│  L2 身份提取层                                            │
│  KeyExtractorRegistry —— 从事件里提取"这是谁的"           │
├─────────────────────────────────────────────────────────┤
│  L1 事件引擎层                                            │
│  EventService —— 对接 Nukkit 底层，按事件类型精准注册     │
└─────────────────────────────────────────────────────────┘
```

### 各层职责一句话总结

| 层 | 类 | 一句话职责 |
|----|----|-----------|
| L1 | `EventService` | 只向 Nukkit 注册**真正有人监听**的事件类型，没人听的类型完全不触发 |
| L2 | `KeyExtractorRegistry` | 从事件里提取"身份"（这个事件是哪个玩家/方块的） |
| L3 | `ObjectEventRouter` | 根据身份，把事件精准送到对应的包装类手里 |
| L4 | `@NukkitEvent` | 你在方法上贴的注解，声明"我要处理什么事件" |

---

## 🚀 快速上手

### 前置：初始化框架

在你的插件主类 `onEnable()` 中设置 Plugin 实例：

```java
@Override
public void onEnable() {
    // 启动 Spring 容器（你的框架已有这部分）
    ApplicationContext ctx = ...; 
    
    // 告诉 EventService 当前插件实例（必须！）
    EventService eventService = ctx.getBean(EventService.class);
    eventService.setPlugin(this);
}
```

> **为什么要这一步？** 因为 Nukkit 注册事件需要知道"是哪个插件注册的"。框架在 Spring 启动时可能还没拿到 Plugin，所以先暂存，等这里一次性刷新。

---

### 用法一：全局监听器（最简单）

**适用场景：** 监听所有玩家的某个事件（比如全服聊天记录、禁止某种行为）。

```java
import org.springframework.stereotype.Component;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;

@Component  // ← 关键！让 Spring 扫描到这个类
public class ChatLogger {

    // 不填 value，框架自动从参数类型推断事件类型为 PlayerChatEvent
    @NukkitEvent
    public void onChat(PlayerChatEvent event) {
        System.out.println(event.getPlayer().getName() + " 说了: " + event.getMessage());
    }
}
```

**就这样！** 不需要手动注册，Spring 启动时 `EventBeanPostProcessor` 会自动扫描所有 `@Component` 类里的 `@NukkitEvent` 方法并注册。

> **💡 事件类型自动推断：** 不填 `value` 时，框架会读取方法的第一个参数类型作为事件类型。
> 所以 `@NukkitEvent` + `public void onChat(PlayerChatEvent event)` 等价于 `@NukkitEvent(PlayerChatEvent.class)`。

---

### 用法二：带条件过滤（SpEL 表达式）

**适用场景：** 只在满足特定条件时才处理事件。

```java
@Component
public class CreativeOnlyListener {

    // 只在玩家是创造模式时才处理
    @NukkitEvent(value = PlayerEvent.class, 
                 condition = "#event.player.gamemode == 1")
    public void onCreativePlayer(PlayerEvent event) {
        // 只有创造模式玩家的事件才会进来
        event.getPlayer().sendMessage("你是创造模式！");
    }

    // 只在右键点击方块时处理
    @NukkitEvent(value = PlayerInteractEvent.class,
                 condition = "#event.action.name() == 'RIGHT_CLICK_BLOCK'")
    public void onRightClickBlock(PlayerInteractEvent event) {
        // 只有右键方块才会进来，左键空气等不会触发
    }
}
```

**SpEL 表达式说明：**
- `#event` 代表事件对象本身
- `#target` 代表处理器对象本身（即 `this`），可以调用处理器类的方法
- 可以调用事件的任何 getter：`#event.player`、`#event.message`
- 可以调用方法：`#event.action.name()`、`#target.someMethod()`
- 支持比较运算：`==`、`!=`、`>`、`<`、`and`、`or`
- 表达式在**注册时解析一次并缓存**，运行时反复求值，性能开销极小

---

### 用法 2.5：复杂条件过滤（filter 方法引用）⭐

**适用场景：** 条件逻辑太复杂，SpEL 表达式写不下或不好维护时。

> **为什么需要这个？** Java 注解不能写方法体（语言限制），所以复杂判断不能直接写在注解里。
> 但你可以把复杂逻辑写在一个 Java 方法中，然后在注解里用 `filter` 属性引用它。

```java
@Component
public class ChatFilter {

    // filter = "isNotSpam" 引用下面的 isNotSpam 方法
    // 只有 isNotSpam(event) 返回 true 时，onNormalChat 才会执行
    @NukkitEvent(filter = "isNotSpam")
    public void onNormalChat(PlayerChatEvent event) {
        System.out.println("正常消息: " + event.getMessage());
    }

    /**
     * 筛选方法：复杂判断逻辑写在 Java 方法里。
     * 要求：参数类型和事件方法一致，返回 boolean。
     * 可以是 private，框架通过反射调用。
     */
    private boolean isNotSpam(PlayerChatEvent event) {
        String msg = event.getMessage();
        if (msg.length() < 2) return false;           // 太短
        if (isAllSameChar(msg)) return false;          // 全是重复字符
        if (containsBannedWord(msg)) return false;     // 包含违禁词
        return true;
    }

    private boolean isAllSameChar(String msg) {
        char first = msg.charAt(0);
        for (char c : msg.toCharArray()) {
            if (c != first) return false;
        }
        return true;
    }

    private boolean containsBannedWord(String msg) {
        // ... 实际项目中可能查数据库
        return false;
    }
}
```

**`filter` vs `condition` 对比：**

| | `condition`（SpEL） | `filter`（方法引用） |
|---|---|---|
| 写法 | `condition = "#event.x > 5"` | `filter = "myFilterMethod"` |
| 适合 | 简单的属性比较 | 复杂的多条件逻辑 |
| IDE 补全 | ❌ 字符串，无补全 | ✅ 正常 Java 方法，有补全 |
| 编译检查 | ❌ 运行时才发现错误 | ✅ 编译时就能发现错误 |
| 能访问 this | 通过 `#target` | ✅ 直接访问实例字段 |
| 性能 | SpEL 求值（略慢） | 直接方法调用（最快） |

> **⚠️ 注意：** `condition` 和 `filter` 不能同时使用，只能选一个。

---

### 用法三：对象级监听器（每个玩家独立处理）⭐ 核心功能

**适用场景：** 每个玩家/方块/实体需要独立的处理逻辑和数据（比如每个玩家单独的技能冷却、任务进度）。

#### 第一步：定义包装类（注意：不加 `@Component`！）

```java
public class PlayerWrapper {

    private final Player player;
    private final ObjectEventRouter router;

    public PlayerWrapper(Player player, ObjectEventRouter router) {
        this.player = player;
        this.router = router;
    }

    // 只会收到【这个玩家】的移动事件，其他玩家的一概不收
    @NukkitEvent(PlayerMoveEvent.class)
    public void onMove(PlayerMoveEvent event) {
        player.sendMessage("你走到了 " + event.getTo());
    }

    // 玩家退出时自动清理
    @NukkitEvent(PlayerQuitEvent.class)
    public void onQuit(PlayerQuitEvent event) {
        router.unregister(this);  // 注销自己的所有监听
    }
}
```

#### 第二步：在玩家上线时创建并注册

```java
@Component
public class JoinHandler {

    private final ObjectEventRouter router;

    public JoinHandler(ObjectEventRouter router) {
        this.router = router;
    }

    @NukkitEvent(PlayerJoinEvent.class)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // 创建这个玩家的专属包装类
        PlayerWrapper wrapper = new PlayerWrapper(player, router);
        
        // 注册！第二个参数是"身份Key"——框架用它来匹配事件
        // PlayerMoveEvent 会自动提取 getPlayer()，和这里的 player 对比
        router.register(wrapper, player);
    }
}
```

#### 工作原理图解

```
玩家A移动 → PlayerMoveEvent → 框架提取 getPlayer() = 玩家A
                                    ↓
                          查找 Key=玩家A 的处理器
                                    ↓
                          找到 玩家A 的 PlayerWrapper.onMove() ✅

玩家B移动 → PlayerMoveEvent → 框架提取 getPlayer() = 玩家B
                                    ↓
                          查找 Key=玩家B 的处理器
                                    ↓
                          找到 玩家B 的 PlayerWrapper.onMove() ✅
                          （不会触发玩家A的处理器！）
```

---

### 用法四：自定义路由维度（RoutingSpec）⭐ 高级

**适用场景：** 默认的提取器按"玩家"路由，但你想按"物品"、"区域"等其他维度路由。

> **为什么需要这个？** 默认情况下，`PlayerInteractEvent` 按 `Player` 路由（全局提取器提取 `getPlayer()`）。
> 但如果你想监听"某把特定的剑被右键使用"，你需要按 `Item` 路由，而不是按 `Player`。
> `RoutingSpec` 让你为**每个 Wrapper 实例**指定自定义的 Key 提取器。

```java
// 场景：监听一把附魔剑的右键使用
public class MagicSwordWrapper {

    private final Item sword;
    private final ObjectEventRouter router;

    public static MagicSwordWrapper create(Item sword, ObjectEventRouter router) {
        MagicSwordWrapper wrapper = new MagicSwordWrapper(sword, router);

        // 关键：用 RoutingSpec 指定"按物品路由"，而不是默认的"按玩家路由"
        RoutingSpec spec = RoutingSpec.create()
                .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);

        // 注册时传入 spec，框架会用 spec 中的提取器代替全局提取器
        router.register(wrapper, sword, spec);
        return wrapper;
    }

    // 只在玩家拿着【这把剑】右键方块时触发
    @NukkitEvent(filter = "isRightClickBlock")
    public void onUse(PlayerInteractEvent event) {
        // 释放剑的附魔技能...
    }

    private boolean isRightClickBlock(PlayerInteractEvent event) {
        return event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK;
    }
}
```

**RoutingSpec vs 全局提取器：**

| | 全局提取器（默认） | RoutingSpec（自定义） |
|---|---|---|
| 适用 | 按 Player/Block/Entity/Inventory 路由 | 按其他维度路由（Item、区域、自定义） |
| 配置 | 无需额外配置 | 注册时传入 `RoutingSpec` |
| 粒度 | 每种事件类型一个全局提取器 | 每个 Wrapper 实例可以不同 |
| 示例 | 玩家A的事件 → 玩家A的 Wrapper | 剑X的右键事件 → 剑X的 Wrapper |

> **💡 何时用 RoutingSpec？** 当你要路由的对象不是事件自带的"主体"（Player/Block/Entity/Inventory），
> 而是事件中的其他属性（如手中的物品、所在区域、自定义标识）时，就需要 RoutingSpec。

---

### 用法五：高级 Wrapper 示例集 🌟

以下三个示例展示了框架在不同场景下的完整用法，涵盖全局提取器、自定义路由、复杂过滤等。

#### 示例 1：箱子第 N 格 Wrapper（全局提取器 + 复杂 filter）

**场景：** 监听箱子中第 3 个格子，当钻石被放入时触发。

**路由方式：** 使用全局提取器（`InventoryEvent` → `getInventory()`），Key = 箱子 Inventory。
格子级别的精细过滤在 filter 方法中实现。

```java
// 注册：Key = 箱子 Inventory（全局提取器自动提取）
ChestSlotWrapper slot3 = ChestSlotWrapper.create(chestInventory, 3, router);

// ChestSlotWrapper 内部：
@NukkitEvent(filter = "isDiamondPlacedInSlot")
public void onDiamondPlaced(InventoryTransactionEvent event) { ... }

// filter 方法遍历交易动作，检查是否有钻石放入第 3 格
private boolean isDiamondPlacedInSlot(InventoryTransactionEvent event) {
    for (InventoryAction action : event.getTransaction().getActions()) {
        if (!(action instanceof SlotChangeAction slotAction)) continue;
        if (slotAction.getInventory() != inventory || slotAction.getSlot() != slot) continue;
        if (slotAction.getTargetItem().getId() == Item.DIAMOND) return true;
    }
    return false;
}
```

> 📄 完整代码见 [`ChestSlotWrapper.java`](../../../../../../test/java/example/ChestSlotWrapper.java)

---

#### 示例 2：NBT 附魔剑 Wrapper（RoutingSpec 按物品路由）

**场景：** 监听一把带有特定 NBT 标签的剑，当玩家拿着它右键方块时触发技能。

**路由方式：** 使用 RoutingSpec 将 `PlayerInteractEvent` 按 `Item` 路由（而非默认的 `Player`）。

```java
// 注册：用 RoutingSpec 指定"按物品路由"
RoutingSpec spec = RoutingSpec.create()
        .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);
router.register(wrapper, sword, spec);

// MagicSwordWrapper 内部：
@NukkitEvent(filter = "isRightClickBlock")
public void onUse(PlayerInteractEvent event) {
    // 只有拿着【这把剑】右键方块才会触发
    player.sendMessage("你释放了 " + swordName + " 的力量！");
}
```

> 📄 完整代码见 [`MagicSwordWrapper.java`](../../../../../../test/java/example/MagicSwordWrapper.java)

---

#### 示例 3：PVP 区域 Wrapper（RoutingSpec 按区域路由）

**场景：** 定义一个坐标区域，监听该区域内玩家之间的攻击行为。

**路由方式：** 使用 RoutingSpec 将 `EntityDamageByEntityEvent` 按区域 ID 路由。
提取器内部判断受害者坐标是否在区域内，是则返回区域 ID，否则返回 null（不路由）。

```java
// 注册：用 RoutingSpec 指定"按区域路由"
RoutingSpec spec = RoutingSpec.create()
        .extract(EntityDamageByEntityEvent.class, event -> {
            Position pos = event.getEntity().getPosition();
            return wrapper.isInRegion(pos) ? wrapper.regionId : null;
        });
router.register(wrapper, regionId, spec);

// PvpRegionWrapper 内部：
@NukkitEvent(filter = "isPlayerVsPlayer")
public void onPvp(EntityDamageByEntityEvent event) {
    // 只有区域内、玩家打玩家 才会触发
    Player attacker = (Player) event.getDamager();
    Player victim = (Player) event.getEntity();
    System.out.println(attacker.getName() + " 在区域内攻击了 " + victim.getName());
}
```

> 📄 完整代码见 [`PvpRegionWrapper.java`](../../../../../../test/java/example/PvpRegionWrapper.java)

---

#### 三个示例对比总结

| 示例 | 事件类型 | 路由方式 | Key | 精细过滤 |
|------|---------|---------|-----|---------|
| 箱子第N格 | `InventoryTransactionEvent` | 全局提取器 | Inventory | filter: 检查格子+钻石 |
| NBT附魔剑 | `PlayerInteractEvent` | **RoutingSpec** | Item | filter: 检查右键方块 |
| PVP区域 | `EntityDamageByEntityEvent` | **RoutingSpec** | 区域ID(String) | filter: 检查玩家打玩家 |

---

## 📚 所有 API 详解

### `@NukkitEvent` 注解

贴在方法上，声明这个方法处理什么事件。

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `value` | `Class<? extends Event>` | ❌ 否 | 自动推断 | 事件类型。**不填时自动从方法第一个参数推断** |
| `condition` | `String` (SpEL) | ❌ 否 | `""` | SpEL 过滤条件，满足才执行 |
| `filter` | `String` | ❌ 否 | `""` | 引用类中的筛选方法名（与 `condition` 二选一） |
| `priority` | `EventPriority` | ❌ 否 | `NORMAL` | 事件优先级 |

> **💡 推荐不填 `value`**：让框架自动从方法参数推断事件类型，减少重复代码，IDE 也能帮你检查参数类型。

**优先级顺序**（从先到后执行）：
```
LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR
```
> `MONITOR` 通常用于"只观察不修改"的场景（如日志统计）。

---

### `EventService`（L1 引擎）

底层事件分发中心。通常你不需要直接用它（用 `@NukkitEvent` 注解就行），但了解它有助于理解原理。

| 方法 | 说明 |
|------|------|
| `subscribe(事件类型, consumer)` | 订阅某类事件（默认 NORMAL 优先级） |
| `subscribe(事件类型, 优先级, consumer)` | 订阅并指定优先级 |
| `unsubscribe(事件类型, consumer)` | 注销某个订阅 |
| `setPlugin(plugin)` | **必须调用**，设置插件实例 |

**`EventConsumer` 返回值含义：**
- `return false` → 非独占，允许其他处理器继续处理
- `return true` → 独占，停止同优先级的后续处理器

---

### `ObjectEventRouter`（L3 路由）

对象级事件路由的核心。

| 方法 | 说明 |
|------|------|
| `registerGlobal(bean)` | 注册全局处理器（通常由框架自动调用） |
| `register(wrapper, key)` | 注册对象级处理器，绑定到指定 Key（使用全局提取器） |
| `register(wrapper, key, RoutingSpec)` | 注册对象级处理器，使用**自定义提取器**（按非默认维度路由） |
| `unregister(wrapper)` | 按包装类实例注销其所有处理器 |
| `unregisterByKey(key)` | 按 Key 注销所有绑定到该 Key 的处理器 |
| `unregisterAll()` | 注销所有处理器 |

---

### `KeyExtractorRegistry`（L2 身份提取）

框架预置了以下事件的"身份提取器"：

| 事件基类 | 提取方法 | 提取出的 Key |
|----------|----------|-------------|
| `PlayerEvent` | `getPlayer()` | `Player` 对象 |
| `BlockEvent` | `getBlock()` | `Block` 对象 |
| `EntityEvent` | `getEntity()` | `Entity` 对象 |
| `InventoryEvent` | `getInventory()` | `Inventory` 对象 |

**类层次查找：** 你注册 `PlayerMoveEvent`，框架会自动沿继承链找到 `PlayerEvent` 的提取器。

**自定义提取器：**
```java
@Component
public class MyConfig {
    public MyConfig(KeyExtractorRegistry registry) {
        // 为自定义事件注册提取器
        registry.register(MyCustomEvent.class, event -> event.getOwner());
    }
}
```

---

## ⚡ 性能说明

### 为什么不会拖慢服务器？

| 设计点 | 效果 |
|--------|------|
| **按需注册** | 没人监听的事件类型，Nukkit 完全不触发任何代码 |
| **类型精准** | Nukkit 原生按事件类型过滤，不会"全量扫描" |
| **预编译 SpEL** | 条件表达式在注册时解析一次，运行时直接求值 |
| **方法预设置** | 反射方法 `setAccessible(true)` 只做一次 |
| **并发安全** | `ConcurrentHashMap` + `CopyOnWriteArrayList`，无锁读 |
| **Key 缓存** | 身份提取器的继承链查找结果会被缓存 |

### 对比旧方案

| | 旧方案（catch-all） | 新方案 |
|---|---|---|
| 无监听器时 | 每个事件都触发 | **零开销** |
| 分发方式 | 遍历所有 consumer 判断 | 直接按类型查找 |
| 线程安全 | ❌ 无保证 | ✅ 并发安全 |

---

## 📁 文件结构

```
event/
├── README.md                          ← 你正在看的这个文件
├── EventService.java                  ← L1：事件引擎（对接 Nukkit）
├── EventConsumer.java                 ← L1：消费者函数式接口
├── annotation/
│   └── NukkitEvent.java               ← L4：@NukkitEvent 注解
├── routing/
│   ├── EventKeyExtractor.java         ← L2：身份提取器接口
│   ├── KeyExtractorRegistry.java      ← L2：提取器注册表（含预置）
│   ├── RoutingSpec.java               ← L2：自定义路由规格（per-wrapper 提取器）
│   ├── AnnotatedHandler.java          ← L3：注解方法封装（含 SpEL）
│   └── ObjectEventRouter.java         ← L3：对象路由核心
├── spring/
│   └── EventBeanPostProcessor.java    ← L4：Spring 自动扫描注册
└── deprecated/
    └── EventService.java              ← 旧版（已弃用）
```

---

## ❓ 常见问题

**Q: 全局监听器和对象级监听器有什么区别？**

| | 全局监听器 | 对象级监听器 |
|---|---|---|
| 注册方式 | `@Component` 自动注册 | `router.register(wrapper, key)` 手动注册 |
| 接收范围 | 该事件的**所有**实例 | 只接收与 Key **匹配**的事件 |
| 生命周期 | 跟随 Spring 容器 | 手动 `unregister()` 释放 |
| 典型场景 | 聊天记录、全局规则 | 每个玩家独立的数据/逻辑 |

**Q: 一个事件会同时触发全局和对象级处理器吗？**

会的。分发顺序是：先全局处理器，再对象级处理器（按 Key 匹配的）。

**Q: 忘记调用 `unregister()` 会怎样？**

会导致内存泄漏——包装类实例无法被 GC 回收。所以务必在玩家下线/对象销毁时调用 `unregister()`。推荐像示例那样在 `PlayerQuitEvent` 中自动清理。

**Q: SpEL 条件表达式写错了会怎样？**

会在**注册时**抛出异常（表达式解析阶段），不会等到运行时才发现。框架会记录错误日志并跳过该处理器。
