# 箱子界面框架 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，目的是让你 100% 理解每个类的内部实现、设计决策、
> 线程安全模型，并能在修改代码时知道**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个框架，请看 [README.md](README.md)。

---

## 目录

- [1. 设计哲学与核心问题](#1-设计哲学与核心问题)
- [2. 架构总览](#2-架构总览)
- [3. 渲染管线（完整数据流）](#3-渲染管线完整数据流)
- [4. 类逐一剖析](#4-类逐一剖析)
- [5. Nukkit 桥接层（VirtualInventory + FakeBlockHelper）](#5-nukkit-桥接层)
- [6. 事件处理与交易拦截](#6-事件处理与交易拦截)
- [7. 坐标系统与布局](#7-坐标系统与布局)
- [8. 线程安全分析](#8-线程安全分析)
- [9. 生命周期管理](#9-生命周期管理)
- [10. 扩展点](#10-扩展点)
- [11. 已知限制与陷阱](#11-已知限制与陷阱)
- [12. 修改指南（改代码前必读）](#12-修改指南改代码前必读)

---

## 1. 设计哲学与核心问题

### 1.1 与传统 Nukkit 箱子界面的对比

Nukkit 原生的箱子界面开发是**过程式**的：手动 `setItem(i, ...)` 填充格子，在 `InventoryTransactionEvent` 里手动判断 `slot == N` 执行对应逻辑。

```java
// 传统写法：逻辑与位置强耦合
inv.setItem(0, swordItem);
inv.setItem(1, armorItem);

@EventHandler
public void onTransaction(InventoryTransactionEvent event) {
    int slot = action.getSlot();
    if (slot == 0) { buySword(player); }      // 0 是什么？要翻代码才知道
    else if (slot == 1) { buyArmor(player); }
    else { event.setCancelled(true); }        // 其他格子全部锁定
}
```

本框架是**声明式 + 组件化**的：用组件树描述界面结构，每个组件自治地管理自己的外观和事件。

| 维度 | 传统 Nukkit | 本框架 |
|------|------------|--------|
| 编程范式 | 过程式（`setItem` + slot 判断） | 声明式（组件树 + 回调） |
| 外观管理 | 手动 `Item.get()` 构造 | `SlotAppearance` Builder |
| 事件路由 | `if (slot == N)` 硬编码 | 组件树按 `SlotData.owner()` 自动分发 |
| 交易拦截 | 手动 `setCancelled` | 按 `SlotType` 自动拦截 |
| 布局 | 手动计算坐标 | `Layout` 管理器自动排列 |
| 复用性 | 每个界面从零写 | 组件可组合、可嵌套 |

### 1.2 核心设计思路

**"格子类型决定行为"** —— 每个格子有一个 [`SlotType`](model/SlotType.java)，框架据此自动决定是否拦截交易、是否触发回调。开发者只需声明格子类型，无需手写拦截逻辑。

**"组件树自渲染"** —— 每个组件在 `onRender()` 中通过 `RenderContext` 声明自己负责哪些格子。框架递归遍历组件树，自动完成坐标转换和格子写入。

**"外观与逻辑解耦"** —— [`SlotAppearance`](model/SlotAppearance.java) 把「物品长什么样」抽象成纯数据对象，组件逻辑只管「这个格子是什么类型、点击做什么」，不关心显示细节。

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户代码层                                    │
│                                                                     │
│  InventoryView.buildRoot() → 返回组件树                              │
│  Component (Button/StorageBox/Panel/...)                            │
│  SlotAppearance (Builder 模式)                                      │
│     ↓ inventoryAPI.openView(player, view)                          │
├─────────────────────────────────────────────────────────────────────┤
│                         渲染引擎层                                    │
│                                                                     │
│  InventoryView                                                       │
│  ├── slots: SlotData[] (格子数据数组)                               │
│  ├── render(): 重置 → 渲染组件树 → syncToInventory()                │
│  ├── syncToInventory(): appearance/defaultAppearance → Item         │
│  └── repaint(): render() + sendContents()                           │
│                                                                     │
│  RenderContext (坐标转换：相对 → 绝对)                               │
│     ↓ setSlot(absRow, absCol, appearance, defaultAppearance, type) │
├─────────────────────────────────────────────────────────────────────┤
│                         事件路由层                                    │
│                                                                     │
│  InventoryManager (implements Listener)                             │
│  ├── views: ConcurrentHashMap<Player, InventoryView>               │
│  ├── onTransaction(): 按 SlotType 拦截/放行，分发回调               │
│  └── onClose(): 清理视图资源                                         │
│     ↓ handleClick / handleStore                                     │
├─────────────────────────────────────────────────────────────────────┤
│                         Nukkit 桥接层                                │
│                                                                     │
│  VirtualInventory (extends ContainerInventory)                       │
│  ├── onOpen(): 放置真实方块 + super.onOpen() 发包                   │
│  └── onClose(): super.onClose() 发包 + 恢复方块                     │
│                                                                     │
│  FakeBlockHelper (放置/恢复临时真实方块)                             │
└─────────────────────────────────────────────────────────────────────┘
```

### 包结构

```
io.github.JiangHu.jframe.inventory
├── InventoryAPI              ← 公开门面（openView/closeView/getView）
├── config/
│   └── InventorySpringConfig ← Spring 配置（@ImportResource）
├── manager/
│   └── InventoryManager      ← 事件监听 + 视图管理
├── component/
│   ├── InventoryComponent    ← 组件基类（组件树核心）
│   ├── Button                ← 按钮组件
│   ├── StorageBox            ← 存储格组件（含默认占位物品）
│   ├── Filler                ← 填充组件
│   ├── IconLabel             ← 图标标签组件
│   └── Panel                 ← 容器面板
├── layout/
│   ├── Layout                ← 布局管理器接口
│   ├── ManualLayout          ← 手动布局（默认）
│   ├── BorderLayout          ← 边框布局
│   └── GridLayout            ← 网格布局
├── model/
│   ├── SlotAppearance        ← 格子外观（Builder 模式）
│   ├── SlotType              ← 格子类型枚举
│   └── event/
│       ├── ClickEvent        ← 点击事件（record）
│       └── StoreEvent        ← 存取事件（record）
└── view/
    ├── InventoryView         ← 视图基类（渲染管线核心）
    ├── RenderContext         ← 渲染上下文（坐标转换）
    ├── SlotData              ← 格子数据（record，内部模型）
    ├── VirtualInventory      ← Nukkit 库存桥接
    └── FakeBlockHelper       ← 假方块管理
```

---

## 3. 渲染管线（完整数据流）

### 3.1 打开视图时的渲染流程

```
inventoryAPI.openView(player, view)
  └→ InventoryManager.openView()
       ├→ view.bindPlugin(plugin)       // 注入插件实例
       └→ view.open(player)
            ├→ new VirtualInventory(...)  // 创建 Nukkit 库存
            ├→ render()                   // ★ 渲染管线
            │    ├→ 1. Arrays.fill(slots, SlotData.empty())  // 重置所有格子
            │    ├→ 2. root = buildRoot()  // 用户构建组件树
            │    ├→ 3. root.mountTree(this) // 挂载（触发 onMount）
            │    ├→ 4. root.render(ctx)    // 递归渲染组件树
            │    │    └→ InventoryComponent.render()
            │    │         ├→ ctx.translate(row, col, this)  // 偏移坐标
            │    │         ├→ onRender(ctx)                   // 子类填充格子
            │    │         │    └→ ctx.slot(r, c, appearance, type)
            │    │         │         └→ view.setSlot(absRow, absCol, ...)
            │    │         │              └→ slots[index] = new SlotData(...)
            │    │         └→ children.sort(by layer).render(ctx)  // 子组件
            │    └→ 5. syncToInventory()  // SlotData[] → Nukkit Item
            │         └→ for each slot:
            │              if appearance != null → inventory.setItem(toItem())
            │              else if defaultAppearance != null && slot empty → 占位物品
            │              else → 保留玩家物品
            ├→ 6. placeFakeBlock()  // 第一阶段：立即放置假方块
            │    └→ FakeBlockHelper.create()  // UpdateBlockPacket 即时发送
            └→ 7. 延迟 OPEN_DELAY_TICKS → player.addWindow(inventory)  // 第二阶段
                 └→ VirtualInventory.onOpen()
                      └→ super.onOpen()  // 发送 ContainerOpenPacket（方块已就位）
```

### 3.2 增量更新（repaint）

```
view.repaint()  // 或 component.repaint()
  ├→ render()           // 重新渲染整个组件树
  └→ sendContents()     // 发送库存内容给玩家
```

> **注意**：`repaint()` 是全量重渲染，不是增量 diff。对于大多数箱子界面（≤54 格），性能完全足够。

### 3.3 SlotData 的三种状态

[`SlotData`](view/SlotData.java) 是渲染结果的内部表示，每个格子有三种状态：

| `appearance` | `defaultAppearance` | 行为 |
|---|---|---|
| 非 null | — | 框架管理外观（BUTTON/DISPLAY/LOCKED），直接覆盖格子 |
| null | 非 null | STORAGE 格子：空槽时显示占位物品，有物品时保留玩家物品 |
| null | null | STORAGE 格子：完全由玩家管理，框架不干预 |

---

## 4. 类逐一剖析

### 4.1 InventoryView — 视图基类

[`InventoryView`](view/InventoryView.java) 是框架的核心，管理整个渲染管线。

**关键字段：**
- `SlotData[] slots` — 格子数据数组（`rows * 9` 个元素），渲染结果的唯一来源
- `InventoryComponent root` — 根组件（首次渲染时由 `buildRoot()` 创建）
- `VirtualInventory inventory` — Nukkit 库存桥接
- `boolean cleanedUp` — 幂等标记，防止重复清理

**核心方法：**
- [`render()`](view/InventoryView.java:145) — 渲染管线入口（重置 → 渲染 → 同步）
- [`syncToInventory()`](view/InventoryView.java:168) — SlotData → Nukkit Item 的转换逻辑
- [`open(Player)`](view/InventoryView.java:305) — 打开视图（分离式两阶段策略）
- [`close()`](view/InventoryView.java:345) — 主动关闭（先清理再 removeWindow）
- [`cleanup()`](view/InventoryView.java:364) — 幂等清理

**设计决策：为什么 `syncToInventory` 要检查 `current.isNull()`？**

STORAGE 格子的 `defaultAppearance`（占位物品）只在格子为空时显示。如果玩家已经放入了物品，框架不能覆盖它。所以渲染时需要检查 `inventory.getItem(i)` 是否为空：

```java
} else if (data.defaultAppearance() != null) {
    Item current = inventory.getItem(i);
    if (current == null || current.isNull()) {
        inventory.setItem(i, data.defaultAppearance().toItem(), false);
    }
}
```

### 4.2 InventoryComponent — 组件基类

[`InventoryComponent`](component/InventoryComponent.java) 实现了组件树的核心机制。

**几何属性**（public 字段，布局管理器直接修改）：
- `row, col` — 在父容器中的相对位置
- `width, height` — 占据的格子数
- `layer` — 图层（越大越在上层，子组件按 layer 排序后渲染）

**渲染机制**（[`render()`](component/InventoryComponent.java:123) 是 final 方法）：
1. 检查 `visible`
2. `ctx.translate(row, col, this)` — 偏移坐标到当前组件位置
3. `onRender(ctx)` — 子类填充格子
4. 子组件按 `layer` 排序后递归 `render(ctx)`

> **为什么 `render()` 是 final？** 防止子类破坏渲染管线（坐标偏移 + 子组件递归）。子类只能通过重写 `onRender()` 来定制渲染。

**组件树导航**（[`findByName`](component/InventoryComponent.java:304) / [`findByType`](component/InventoryComponent.java:328)）：
- 从根组件开始深度优先搜索
- `root()` 向上遍历到最顶层
- 支持按名称和类型查找

### 4.3 RenderContext — 渲染上下文

[`RenderContext`](view/RenderContext.java) 负责**坐标转换**：将组件内的相对坐标转为箱子的绝对格子序号。

**坐标系统：**
- 箱子是 9 列 × N 行的网格
- 组件使用相对坐标（相对于父容器偏移）
- 绝对格子序号：`absIndex = absRow * 9 + absCol`

**`translate()` 的作用：**

每次进入子组件时，`render()` 调用 `ctx.translate(row, col, this)` 创建一个新的偏移上下文。这样子组件在 `onRender()` 中用相对坐标 `(0, 0)` 就能正确定位到自己在箱子中的绝对位置。

```
根组件 baseRow=0, baseCol=0
  └→ 子组件 A (row=1, col=2) → translate(1, 2) → baseRow=1, baseCol=2
       └→ 子组件 B (row=0, col=0) → translate(0, 0) → baseRow=1, baseCol=2
            └→ ctx.slot(0, 0, ...) → absRow=1, absCol=2 → index=11
```

### 4.4 SlotAppearance — 格子外观

[`SlotAppearance`](model/SlotAppearance.java) 是不可变对象（所有字段 final），使用 Builder 模式构建。

**为什么用 Builder 而非构造器？** 外观有 6 个字段，其中 4 个可选。构造器参数太多可读性差，Builder 支持链式调用且只设置需要的字段。

**`toItem()` 的实现：** 将外观数据转换为 Nukkit `Item` 对象。`glowing` 通过添加效率附魔（等级 1）实现光效，不影响实际游戏性。

### 4.5 SlotData — 格子数据（内部模型）

[`SlotData`](view/SlotData.java) 是 package-private 的 record，仅框架内部使用。

```java
record SlotData(
    SlotAppearance appearance,         // 框架管理的外观（null = 玩家管理）
    SlotAppearance defaultAppearance,  // STORAGE 空槽占位物品
    SlotType type,                     // 格子类型
    InventoryComponent owner           // 事件回调目标
)
```

`owner` 字段是事件分发的关键：当玩家点击/存取某个格子时，框架通过 `slots[i].owner()` 找到负责该格子的组件，调用其 `onClick`/`onStore` 回调。

---

## 5. Nukkit 桥接层

### 5.1 为什么需要"假方块"？

基岩版客户端打开箱子界面时，需要一个**真实的方块坐标**作为容器锚点。`ContainerOpenPacket` 中包含方块坐标，客户端据此渲染界面。

本框架通过 [`FakeBlockHelper`](view/FakeBlockHelper.java) 在玩家附近放置一个临时的真实箱子方块，界面关闭后恢复原方块。

### 5.2 VirtualInventory

[`VirtualInventory`](view/VirtualInventory.java) 继承 `ContainerInventory`，复用 Nukkit 原生的 `ContainerOpenPacket`/`ContainerClosePacket` 发包逻辑。

**为什么继承 ContainerInventory 而非 BaseInventory？**

早期实现继承 `BaseInventory` 并手动构造 `ContainerOpenPacket`，但手动发包容易遗漏 Nukkit 内部状态同步（如 viewer 集合、windowId 映射）。`ContainerInventory` 的 `onOpen`/`onClose` 已正确处理这些细节。

**打开流程（分离式两阶段）：**
1. **第一阶段** `placeFakeBlock()` — 放置真实方块（含方块实体标题），`UpdateBlockPacket` 即时发送
2. **第二阶段**（延迟 `OPEN_DELAY_TICKS`）`onOpen()` → `super.onOpen()` — 同步发送 `ContainerOpenPacket` + `sendContents`

> 如果未调用 `placeFakeBlock()` 而直接 `addWindow`，`onOpen` 会回退到旧模式（在 onOpen 内放置方块），保持向后兼容。

**关闭流程：**
1. `super.onClose()` — 同步发送 `ContainerClosePacket`
2. `FakeBlockHelper.remove()` — 恢复原方块

### 5.3 分离式两阶段打开策略

[`InventoryView.open()`](view/InventoryView.java:305) 采用**分离式两阶段策略**：

| 阶段 | 时机 | 操作 | 发送的数据包 |
|------|------|------|-------------|
| 第一阶段 | 立即 | `placeFakeBlock()` 放置真实方块 | `UpdateBlockPacket`（`direct=true` 即时发送） |
| 第二阶段 | 延迟 `OPEN_DELAY_TICKS`（默认 5 tick） | `player.addWindow()` 注册窗口 | `ContainerOpenPacket` + `sendContents` |

**为什么需要两阶段？**

网易版基岩客户端收到 `ContainerOpenPacket` 时会校验本地世界中是否存在对应的容器方块。如果方块不存在（或客户端尚未处理方块更新包），客户端**静默拒绝**打开界面。

分离式策略使 `UpdateBlockPacket`（第一阶段）**提前于** `ContainerOpenPacket`（第二阶段）发送，确保客户端有充足时间处理方块更新，方块校验通过。相比旧方案（10 tick 统一延迟），总延迟从 500ms 减半至 250ms，且更可靠。

**延迟可配置：** 通过 `InventoryView.OPEN_DELAY_TICKS = N` 调整（默认 5 tick）。
- 过短（< 3 tick）：客户端可能尚未处理方块更新，校验失败
- 过长（> 10 tick）：玩家感知明显延迟

### 5.4 方块残留保护

如果玩家异常下线导致关闭流程未执行，`FakeBlockHelper.remove()` 会利用 `create()` 时保存的 `Level` 引用恢复原方块。`InventoryManager.onClose()` 也会在 `InventoryCloseEvent` 时触发清理。

---

## 6. 事件处理与交易拦截

### 6.1 InventoryManager 的交易拦截规则

[`InventoryManager.onTransaction()`](manager/InventoryManager.java:134) 遍历交易操作，按 `SlotType` 分类处理：

| 格子类型 | 交易行为 | 回调 |
|---------|---------|------|
| `BUTTON` | 取消交易 | `handleClick()` → `onClick()` |
| `DISPLAY` | 取消交易 | 无 |
| `LOCKED` | 取消交易 | 无 |
| `STORAGE` | 放行交易 | `handleStore()` → `onStore()` |

**混合交易处理：** 如果一笔交易同时包含 STORAGE 和非 STORAGE 操作（如拖拽），整个交易被取消（因为 Nukkit 不支持部分执行）。

### 6.2 事件分发链路

```
Nukkit InventoryTransactionEvent
  └→ InventoryManager.onTransaction()
       ├→ 按 SlotType 分类 actions
       ├→ hasNonStorage? → event.setCancelled(true)
       ├→ BUTTON actions → view.handleClick(slot, player, item)
       │    └→ slotOwner(slot).dispatchClick() → component.onClick(ClickEvent)
       └→ STORAGE actions (未取消时) → view.handleStore(slot, player, source, target)
            └→ slotOwner(slot).dispatchStore() → component.onStore(StoreEvent)
```

### 6.3 StoreEvent 的语义

[`StoreEvent`](model/event/StoreEvent.java) 包含操作前后的物品快照：

- `isDeposit()` — `sourceItem` 为空且 `targetItem` 非空（放入）
- `isWithdraw()` — `sourceItem` 非空且 `targetItem` 为空（取出）

> **注意**：同种物品数量变化（如 32→64）不满足上述条件，`isDeposit()` 和 `isWithdraw()` 都返回 false。如需精确感知数量变化，直接比较 `sourceItem.getCount()` 和 `targetItem.getCount()`。

---

## 7. 坐标系统与布局

### 7.1 坐标系统

箱子界面是 **9 列 × N 行**（N = 1~6）的网格。格子序号从左到右、从上到下编号：

```
行 0:  0  1  2  3  4  5  6  7  8
行 1:  9 10 11 12 13 14 15 16 17
行 2: 18 19 20 21 22 23 24 25 26
```

绝对序号计算：`index = row * 9 + col`

### 7.2 布局管理器

布局管理器在 `Panel.onRender()` 中被调用（每次渲染都会重新排列）：

```java
// Panel.onRender()
layout.arrange(this);  // 布局管理器设置子组件的 row/col/width/height
```

| 布局 | 排列方式 |
|------|---------|
| [`ManualLayout`](layout/ManualLayout.java) | 空操作，子组件位置由 `add(child, row, col)` 指定 |
| [`BorderLayout`](layout/BorderLayout.java) | 五区域：NORTH/SOUTH 占满宽度，EAST/WEST 占 1 列，CENTER 占剩余 |
| [`GridLayout`](layout/GridLayout.java) | 均分 `rows × cols` 网格，按添加顺序填入 |

### 7.3 图层（layer）

子组件按 `layer` 从低到高排序后渲染。`layer` 大的组件后渲染，会覆盖 `layer` 小的组件（如果位置重叠）。

> **注意**：当前实现中，后渲染的组件会覆盖先渲染组件的 `SlotData`（因为 `slots[index]` 被覆写）。如果需要真正的图层叠加（如半透明覆盖），需要扩展 `SlotData` 为列表结构。

---

## 8. 线程安全分析

### 8.1 线程模型

Nukkit 的主线程（Server Thread）处理所有游戏事件。本框架的所有操作都在主线程上：

- `InventoryTransactionEvent` / `InventoryCloseEvent` — 主线程
- `openView` / `closeView` — 通常在命令处理或事件回调中调用（主线程）
- `repaint()` — 主线程

### 8.2 并发安全

[`InventoryManager.views`](manager/InventoryManager.java:47) 使用 `ConcurrentHashMap`，因为：
- 视图可能在事件回调中打开/关闭（主线程）
- `closeAll()` 可能在 `onDisable()` 中调用（可能非主线程）

`InventoryView` 本身**不是线程安全**的，但因为它只在主线程上被访问（每个玩家同时只有一个视图），所以不需要额外同步。

### 8.3 延迟任务的线程安全

`open()` 中的延迟任务（第二阶段）通过 `Server.getScheduler().scheduleDelayedTask()` 调度，在主线程执行。延迟期间玩家可能下线或视图被关闭，所以任务内部检查 `player.isOnline()` 和 `cleanedUp`。如果视图在延迟期间被 `cleanup()`，待执行任务会被取消（`pendingOpenTask.cancel()`），已放置的假方块也会被手动移除（`removeFakeBlock()`）。

---

## 9. 生命周期管理

### 9.1 视图生命周期

```
new ShopView()           // 构造（设置行数、标题）
  ↓ inventoryAPI.openView()
bindPlugin(plugin)       // 注入插件实例
  ↓
open(player)             // 创建库存 + 渲染
  ↓
placeFakeBlock()         // 第一阶段：放置假方块（UpdateBlockPacket 即时发送）
  ↓ 延迟 OPEN_DELAY_TICKS（默认 5 tick）
addWindow(inventory)     // 第二阶段：弹出界面（ContainerOpenPacket）
  ↓
onOpen()                 // 生命周期回调（窗口已注册）
  ↓ 玩家关闭箱子 或 closeView()
cleanup()                // 清理（onClose + unmountTree + 置 null）
  ↓
（对象可被 GC 回收）
```

### 9.2 组件生命周期

```
new Button(...)          // 构造
  ↓ panel.add(button)
mountTree(view)          // 挂载到视图（递归，触发 onMount）
  ↓ 每次渲染
onRender(ctx)            // 填充格子
  ↓ 点击/存取
onClick/onStore          // 事件回调
  ↓ 视图关闭
unmountTree()            // 卸载（递归，触发 onUnmount）
```

### 9.3 幂等清理

[`cleanup()`](view/InventoryView.java:364) 使用 `cleanedUp` 标记保证幂等：

- `close()` 先调用 `cleanup()`，再调用 `removeWindow()`
- `removeWindow()` 会触发 `InventoryCloseEvent` → `onClose()` → `cleanup()`
- 第二次 `cleanup()` 检查 `cleanedUp` 直接返回，避免重复执行 `onClose()`

---

## 10. 扩展点

### 10.1 自定义组件

继承 [`InventoryComponent`](component/InventoryComponent.java)，重写 `onRender()`：

```java
public class ProgressBar extends InventoryComponent {
    private double progress;  // 0.0 ~ 1.0

    public ProgressBar(int width) {
        this.width = width;
        this.height = 1;
    }

    public ProgressBar progress(double p) {
        this.progress = Math.max(0, Math.min(1, p));
        return this;
    }

    @Override
    protected void onRender(RenderContext ctx) {
        int filled = (int) (width * progress);
        for (int c = 0; c < width; c++) {
            SlotAppearance color = c < filled
                    ? SlotAppearance.builder().type(Item.LIME_STAINED_GLASS_PANE).build()
                    : SlotAppearance.builder().type(Item.GRAY_STAINED_GLASS_PANE).build();
            ctx.slot(0, c, color, SlotType.DISPLAY);
        }
    }
}
```

### 10.2 自定义布局

实现 [`Layout`](layout/Layout.java) 接口：

```java
public class FlowLayout implements Layout {
    @Override
    public void arrange(Panel container) {
        int col = 0;
        for (InventoryComponent child : container.children()) {
            child.row = 0;
            child.col = col;
            col += child.width;
        }
    }
}
```

### 10.3 自定义 SlotType 行为

如需新的格子类型（如"只读但可查看详情"），修改 [`SlotType`](model/SlotType.java) 枚举和 [`InventoryManager.onTransaction()`](manager/InventoryManager.java:134) 的 switch 逻辑。

---

## 11. 已知限制与陷阱

### 11.1 行数限制

`InventoryView` 构造器限制 `rows` 为 1~6。超过 6 行需要扩展 `VirtualInventory` 支持更大的库存类型。

### 11.2 图层覆盖是覆写而非叠加

当两个组件的格子位置重叠时，`layer` 大的组件会**完全覆盖**小的组件（`SlotData` 被覆写）。不支持半透明叠加。

### 11.3 repaint 是全量渲染

`repaint()` 重新渲染整个组件树（所有格子）。对于频繁更新的场景（如实时倒计时），建议降低 `repaint()` 频率或只更新必要的格子。

### 11.4 分离式两阶段打开延迟

`open()` 采用分离式两阶段策略：第一阶段立即放置假方块（发送 `UpdateBlockPacket`），第二阶段延迟 `OPEN_DELAY_TICKS`（默认 5 tick = 0.25 秒）后注册窗口（发送 `ContainerOpenPacket`）。如果需要调整延迟，设置 `InventoryView.OPEN_DELAY_TICKS = N`（不建议低于 3 tick，可能导致网易客户端方块校验失败）。

### 11.5 StoreEvent 的 isDeposit/isWithdraw 语义

同种物品的数量变化（32→64）不满足 `isDeposit()` 或 `isWithdraw()`。如需精确感知，直接比较 count。

---

## 12. 修改指南（改代码前必读）

### 12.1 添加新组件

1. 继承 [`InventoryComponent`](component/InventoryComponent.java)
2. 重写 `onRender(RenderContext ctx)`，用 `ctx.slot()` 填充格子
3. 如需响应交互，重写 `onClick()` 或 `onStore()`
4. 在 `README.md` 的「内置组件」章节添加文档

### 12.2 修改渲染逻辑

渲染逻辑分布在三个层次，修改时注意影响范围：

| 修改位置 | 影响范围 |
|---------|---------|
| `RenderContext.slot()` | 所有组件的格子写入 |
| `InventoryView.setSlot()` | SlotData 的构造 |
| `InventoryView.syncToInventory()` | SlotData → Nukkit Item 的转换 |

### 12.3 修改交易拦截

交易拦截逻辑在 [`InventoryManager.onTransaction()`](manager/InventoryManager.java:134)。修改时注意：
- 混合交易（STORAGE + 非 STORAGE）必须整体取消
- BUTTON 回调在取消交易后触发（玩家拿不走物品）
- STORAGE 回调只在交易未被取消时触发

### 12.4 修改 Nukkit 桥接

[`VirtualInventory`](view/VirtualInventory.java) 和 [`FakeBlockHelper`](view/FakeBlockHelper.java) 是与 Nukkit 内部机制强耦合的代码。修改前确保理解：
- `ContainerInventory.onOpen/onClose` 的发包逻辑
- `FakeBlockMenu` 作为 `InventoryHolder` 和 `Position` 的双重角色
- 方块残留保护机制（`originalBlocks` + `Level` 引用）
