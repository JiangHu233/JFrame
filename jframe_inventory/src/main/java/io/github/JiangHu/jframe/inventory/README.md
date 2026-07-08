# 箱子界面框架 (Inventory GUI Framework)

> 基于 Nukkit-MOT + Spring 的**声明式箱子界面框架**，灵感来自 Java Swing。
>
> 用**组件树 + 布局管理器**构建箱子界面，告别手写 `setItem(i, ...)` 的过程式代码。
> 框架自动处理渲染、事件分发、交易拦截、增量更新。

---

## 📖 这个框架是干什么的？

在 Nukkit 中开发箱子界面（商店、背包、设置面板），传统写法是：

```java
// 传统过程式：手动管理每个格子
Inventory inv = new ChestInventory(...);
inv.setItem(0, swordItem);
inv.setItem(1, armorItem);
inv.setItem(2, glassPane);  // 填充
// 点击事件里手动判断 slot == 0 是购买、slot == 1 是装备...
```

格子一多就难以维护，逻辑与位置耦合，改一个按钮位置要翻遍事件处理代码。

**本框架**让你用组件化的方式构建界面：

```java
// 声明式：组件树描述界面结构
public class ShopView extends InventoryView {
    @Override
    protected InventoryComponent buildRoot() {
        Panel root = new Panel(9, 3);
        root.add(new Button(SlotAppearance.builder()
                .type(Item.DIAMOND_SWORD)
                .name("§b购买武器")
                .build()).onClick(e -> {
            e.player().sendMessage("购买成功！");
        }), 1, 1);
        return root;
    }
}
```

界面结构、点击逻辑、外观样式**各司其职**，改位置只改坐标，改外观只改 `SlotAppearance`。

---

## 🏗️ 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  用户代码层                                               │
│  InventoryView (buildRoot) + Component + SlotAppearance │
├─────────────────────────────────────────────────────────┤
│  渲染引擎层                                               │
│  InventoryView —— 组件树渲染 → SlotData[] → 同步到库存   │
│  RenderContext —— 相对坐标 → 绝对格子序号转换             │
├─────────────────────────────────────────────────────────┤
│  事件路由层                                               │
│  InventoryManager —— 监听 Nukkit 交易事件，按 SlotType   │
│                       拦截/放行，分发到对应组件回调        │
├─────────────────────────────────────────────────────────┤
│  Nukkit 桥接层                                            │
│  VirtualInventory —— ContainerInventory 子类             │
│  FakeBlockHelper —— 放置/恢复临时真实方块                  │
└─────────────────────────────────────────────────────────┘
```

### 核心概念一览

| 概念 | 类 | 说明 |
|------|------|------|
| 视图 | [`InventoryView`](view/InventoryView.java) | 一个箱子界面，管理渲染管线和生命周期 |
| 组件 | [`InventoryComponent`](component/InventoryComponent.java) | 界面元素基类，支持嵌套形成组件树 |
| 格子外观 | [`SlotAppearance`](model/SlotAppearance.java) | 描述格子上物品长什么样（类型、名称、描述、光效） |
| 格子类型 | [`SlotType`](model/SlotType.java) | 决定格子行为：按钮/存储/展示/锁定 |
| 布局 | [`Layout`](layout/Layout.java) | 自动排列容器中子组件的位置 |
| API 入口 | [`InventoryAPI`](InventoryAPI.java) | 打开/关闭/查询视图的公开门面 |

---

## 🚀 快速上手

### 第一步：获取 InventoryAPI

通过 Spring 注入（框架已通过 `inventory-spring.xml` 自动装配）：

```java
@Autowired
private InventoryAPI inventoryAPI;
```

### 第二步：创建视图

继承 [`InventoryView`](view/InventoryView.java)，在 [`buildRoot()`](view/InventoryView.java:122) 中构建组件树：

```java
public class ShopView extends InventoryView {

    public ShopView() {
        super(3, "§6武器商店");  // 3 行，标题"武器商店"
    }

    @Override
    protected InventoryComponent buildRoot() {
        Panel root = new Panel(9, 3);

        // 购买按钮
        root.add(new Button(SlotAppearance.builder()
                .type(Item.DIAMOND_SWORD)
                .name("§b购买武器")
                .lore("§7点击购买一把钻石剑", "§e价格: §f100 金币")
                .glowing()
                .build()).onClick(click -> {
            click.player().sendMessage("§a购买成功！");
        }), 1, 1);

        // 关闭按钮
        root.add(new Button(SlotAppearance.builder()
                .type(Item.BARRIER)
                .name("§c关闭")
                .build()).onClick(click -> {
            inventoryAPI.closeView(click.player());
        }), 1, 7);

        return root;
    }
}
```

### 第三步：打开视图

```java
inventoryAPI.openView(player, new ShopView());
```

---

## 🎨 格子类型 (SlotType)

每个格子有一个类型，决定玩家能否与之交互：

| 类型 | 能点击？ | 能放/取物品？ | 典型用途 |
|------|---------|-------------|---------|
| [`BUTTON`](model/SlotType.java:20) | ✅ 触发 `onClick` | ❌ | 购买按钮、导航按钮 |
| [`STORAGE`](model/SlotType.java:27) | — | ✅ 触发 `onStore` | 物品存储槽、回收槽 |
| [`DISPLAY`](model/SlotType.java:34) | ❌ | ❌ | 标题装饰、商品预览 |
| [`LOCKED`](model/SlotType.java:41) | ❌ | ❌ | 默认类型，空白格子 |

> **默认安全**：所有未被组件显式声明的格子都是 `LOCKED`，玩家无法操作。

---

## 🧩 内置组件

### Button — 按钮

显示一个物品，点击触发回调。格子类型为 `BUTTON`。

```java
Button button = new Button(SlotAppearance.builder()
        .type(Item.EMERALD)
        .name("§a商店")
        .build());

// 方式一：回调
button.onClick(click -> {
    click.player().sendMessage("你点击了商店！");
});

// 方式二：动态更新外观后重绘
button.setAppearance(SlotAppearance.builder()
        .type(Item.REDSTONE)
        .name("§c商店（已关闭）")
        .build());
repaint();  // 视图或组件级别的重绘
```

### StorageBox — 存储格

允许玩家放入/取出物品的区域。格子类型为 `STORAGE`。

```java
StorageBox box = new StorageBox(3, 1);  // 3 格宽、1 格高
box.name("depositBox");                 // 命名后可通过 findComponent 查找
box.onStore(event -> {
    if (event.isDeposit()) {
        event.player().sendMessage("你放入了: " + event.targetItem().getName());
    } else if (event.isWithdraw()) {
        event.player().sendMessage("你取出了物品");
    }
});
```

#### ⭐ 默认占位物品（空槽提示）

设置 `defaultItem()` 后，空槽会显示一个占位物品作为视觉提示。玩家放入物品后占位物品被覆盖，取出后恢复显示。

```java
StorageBox box = new StorageBox(3, 1);
box.defaultItem(SlotAppearance.builder()
        .type(Item.STAINED_GLASS_PANE)
        .meta(7)                // 灰色玻璃板
        .name("§7放入物品到这里")
        .build());
```

也可以直接传入原生物品：

```java
box.defaultItem(Item.get(Item.STAINED_GLASS_PANE, 8));  // 8 = 浅灰色
```

### Filler — 填充

用指定外观填充整个区域，格子类型为 `DISPLAY`（不可交互）。典型用途：装饰边框、分隔线。

```java
// 灰色玻璃板分隔行（9 格宽、1 格高）
Filler separator = new Filler(SlotAppearance.builder()
        .type(Item.STAINED_GLASS_PANE)
        .meta(7)
        .name(" ")
        .build(), 9, 1);
root.add(separator, 0, 0);
```

### IconLabel — 图标标签

仅显示一个物品外观，不可交互。典型用途：标题栏装饰、信息展示。

```java
IconLabel title = new IconLabel(SlotAppearance.builder()
        .type(Item.ORANGE_STAINED_GLASS_PANE)
        .name("§6§l武器商店")
        .build());
```

### Panel — 容器面板

可包含多个子组件的核心容器，配合布局管理器使用。

```java
Panel root = new Panel(9, 3);
root.background(SlotAppearance.builder()    // 可选：设置背景
        .type(Item.BLACK_STAINED_GLASS_PANE)
        .name(" ")
        .build());
root.add(button1, 0, 0);   // 手动布局
root.add(button2, 0, 1);
```

---

## 📐 布局管理器

布局管理器负责自动排列 [`Panel`](component/Panel.java) 中子组件的位置。

### ManualLayout — 手动布局（默认）

开发者通过 `add(child, row, col)` 显式指定每个子组件位置：

```java
Panel panel = new Panel(9, 3);
panel.add(button1, 0, 0);   // 第 0 行第 0 列
panel.add(button2, 0, 1);   // 第 0 行第 1 列
```

### BorderLayout — 边框布局

将容器分为五个区域：`NORTH`（顶部）、`SOUTH`（底部）、`CENTER`（中间）、`EAST`（右侧）、`WEST`（左侧）。

```java
Panel panel = new Panel(9, 6);
panel.setLayout(new BorderLayout());
panel.add(header, BorderLayout.NORTH);    // 顶部：占满宽度，1 行高
panel.add(footer, BorderLayout.SOUTH);    // 底部：占满宽度，1 行高
panel.add(menu, BorderLayout.WEST);       // 左侧：1 列宽
panel.add(content, BorderLayout.CENTER);  // 中间：占据剩余空间
```

### GridLayout — 网格布局

将容器均分为 `rows × cols` 网格，子组件按添加顺序依次填入。

```java
Panel grid = new Panel(8, 2);
grid.setLayout(new GridLayout(2, 4));  // 2 行 4 列
grid.add(btn1);  // 自动放到 (0,0)
grid.add(btn2);  // 自动放到 (0,1)
grid.add(btn3);  // 自动放到 (0,2)
grid.add(btn4);  // 自动放到 (0,3)
grid.add(btn5);  // 自动放到 (1,0)
```

---

## 🎯 SlotAppearance — 格子外观

[`SlotAppearance`](model/SlotAppearance.java) 把「格子上物品长什么样」抽象成可编程的样式对象，使用 Builder 模式构建：

```java
SlotAppearance appearance = SlotAppearance.builder()
        .type(Item.DIAMOND_SWORD)         // 物品种类（决定图标）
        .meta(0)                          // 数据值/损伤值（同种物品的变体）
        .count(1)                         // 显示数量
        .name("§b§l购买武器")              // 自定义名称（支持 § 颜色代码）
        .lore("§7点击购买一把武器",         // 子内容/描述（多行）
              "§e价格: §f100 金币")
        .glowing()                        // 附魔光效
        .build();
```

| 字段 | 说明 |
|------|------|
| `type` | 物品种类 ID（如 `Item.DIAMOND_SWORD`），决定图标外观 |
| `meta` | 数据值/损伤值（如羊毛颜色、工具耐久） |
| `count` | 显示数量 |
| `name` | 自定义名称，支持 `§` 颜色代码与格式 |
| `lore` | 子内容/描述，多行文字列表 |
| `glowing` | 附魔光效（无需真实附魔） |

---

## 📨 事件

### ClickEvent — 点击事件

玩家点击 `BUTTON` 格子时触发：

```java
button.onClick(click -> {
    Player player = click.player();   // 点击的玩家
    int slot = click.slot();          // 被点击的格子序号
    Item item = click.item();         // 格子上的物品（外观快照）
});
```

### StoreEvent — 存取事件

玩家在 `STORAGE` 格子中放入/取出物品时触发：

```java
box.onStore(event -> {
    if (event.isDeposit()) {
        // 放入：sourceItem 为空，targetItem 为放入的物品
        System.out.println("放入: " + event.targetItem().getName());
    }
    if (event.isWithdraw()) {
        // 取出：sourceItem 为被取走的物品，targetItem 为空
        System.out.println("取出: " + event.sourceItem().getName());
    }
});
```

---

## 🔍 组件树导航

组件可以通过 `name()` 命名，之后在任意位置查找：

```java
// 命名
StorageBox box = new StorageBox(3, 1);
box.name("depositBox");

// 在视图或组件中查找
StorageBox found = view.findComponent("depositBox", StorageBox.class);
// 或从任意组件开始查找
StorageBox found2 = someComponent.findByName("depositBox", StorageBox.class);

// 按类型查找
Button btn = view.findComponent(Button.class);

// 查找所有同名组件
List<InventoryComponent> all = view.findAllComponents("slot");
```

---

## 🔄 动态更新（重绘）

修改组件状态后调用 `repaint()` 刷新界面：

```java
public class ShopView extends InventoryView {

    @Override
    protected InventoryComponent buildRoot() {
        Button buyBtn = new Button(...);
        buyBtn.name("buyButton");
        buyBtn.onClick(click -> {
            // 修改外观
            findComponent("buyButton", Button.class)
                    .setAppearance(SlotAppearance.builder()
                            .type(Item.BARRIER)
                            .name("§c已售罄")
                            .build());
            // 刷新界面
            repaint();
        });
        // ...
    }
}
```

> **`repaint()` 会重新渲染整个组件树**。对于频繁更新的场景，建议只修改必要的状态后调用一次。

---

## 📚 API 速查

### InventoryAPI

| 方法 | 说明 |
|------|------|
| `openView(Player, InventoryView)` | 打开视图（自动关闭旧视图） |
| `closeView(Player)` | 关闭玩家的视图 |
| `getView(Player)` | 获取玩家当前视图 |
| `closeAll()` | 关闭所有视图（插件禁用时调用） |

### InventoryView 生命周期回调

| 方法 | 触发时机 |
|------|---------|
| `buildRoot()` | 首次渲染时（抽象方法，必须实现） |
| `onOpen()` | 界面打开后 |
| `onClose()` | 界面关闭时 |

### InventoryComponent 生命周期回调

| 方法 | 触发时机 |
|------|---------|
| `onRender(RenderContext)` | 渲染阶段，用 `ctx.slot()` 填充格子 |
| `onClick(ClickEvent)` | 点击 `BUTTON` 格子 |
| `onStore(StoreEvent)` | `STORAGE` 格子物品变化 |
| `onMount()` | 挂载到视图时 |
| `onUnmount()` | 卸载时 |

---

## 📂 完整示例

| 示例 | 场景 | 关键特性 |
|------|------|---------|
| [`ShopInventoryView`](../../../../../example/inventory/ShopInventoryView.java) | 武器商店 | Button + StorageBox + 默认占位物品 + BorderLayout |

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
