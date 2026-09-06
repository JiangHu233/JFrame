# jframe_inventory — 箱子界面与物品编解码

> 提供两大能力：**声明式箱子界面框架**（Swing 风格的组件树 + 布局管理器）与**物品 / 物品栏编解码**（完整保留 NBT 与槽位）。通过 [`InventoryAPI`](ui/InventoryAPI.java) 统一对外。

---

## 📑 目录

- [一、模块提供什么](#一模块提供什么)
- [二、与传统方式的对比](#二与传统方式的对比)
- [三、包结构](#三包结构)
- [四、快速开始：声明式箱子界面](#四快速开始声明式箱子界面)
- [五、快速开始：物品编解码](#五快速开始物品编解码)
- [六、InventoryAPI 门面](#六inventoryapi-门面)
- [七、深入阅读](#七深入阅读)

---

## 一、模块提供什么

本模块解决 Minecraft 服务端开发中与「箱子 / 物品栏」相关的两类高频需求：

1. **声明式箱子界面**（`ui` 子包）：用组件树 + 布局管理器构建商店、背包、设置面板等 GUI，告别手写 `setItem(i, ...)` 的过程式代码。框架自动处理渲染、事件分发、交易拦截、增量更新。
2. **物品编解码**（`codec` 子包）：将 Nukkit 原生 `Item` / `Inventory` 序列化为字符串（Base64 紧凑格式或 JSON 可读格式），完整保留 NBT 数据与槽位位置，适用于持久化、跨服同步、数据迁移。

> 两个子包相互独立：`codec` 仅依赖 Nukkit API 与 JDK，可脱离框架单独使用；`ui` 是完整的 GUI 框架。

---

## 二、与传统方式的对比

### 箱子界面

| 维度 | 传统过程式写法 | 本框架 [`ui`](ui/README.md) |
|------|------|------|
| **构建方式** | 手动 `inv.setItem(0, ...)` 逐格设置 | 组件树（`Panel` / `Button` / `StorageBox`）描述结构 |
| **布局** | 手算坐标，改位置要翻遍代码 | 布局管理器（`BorderLayout` / `GridLayout` / `ManualLayout`）自动排列 |
| **点击处理** | 事件里 `if (slot == 0) ...` 硬编码 | 组件 `onClick` 回调，逻辑与位置解耦 |
| **外观** | 散落在 setItem 调用中 | `SlotAppearance` 统一描述（类型/名称/lore/数量/附魔） |
| **交易拦截** | 手动 cancel 事件 | 框架自动拦截，按槽位类型（`SlotType`）放行/阻止 |

### 物品序列化

| 维度 | 手写 NBT 序列化 | 本框架 [`codec`](codec/README.md) |
|------|------|------|
| **NBT 保真** | 需自行处理 CompoundTag 递归，易丢数据 | 完整保留全部 NBT（名称/lore/附魔/自定义标签） |
| **槽位位置** | 需自行记录索引 | 物品栏序列化自动记录每个槽位，反序列化精确还原 |
| **格式** | 单一 | Base64 紧凑（入库）+ JSON 可读（调试）双格式 |
| **类型无关** | 每种箱子各写一套 | 背包/箱子/末影箱/熔炉等统一处理 |

---

## 三、包结构

```
inventory/
├── ui/                        # 声明式箱子界面框架
│   ├── InventoryAPI.java      # 模块公开门面（实现 PluginAware）
│   ├── manager/               # 视图管理（玩家↔视图映射、事件分发）
│   ├── view/                  # InventoryView 基类、虚拟箱子、假方块
│   ├── component/             # 组件：Button / StorageBox / Filler / Panel / IconLabel ...
│   ├── layout/                # 布局：Border / Grid / Manual
│   ├── model/                 # 槽位外观、槽位类型、事件
│   ├── config/                # Spring 装配
│   ├── README.md              # ← 界面框架详细文档
│   └── DEVELOPER.md           # ← 界面框架实现文档
├── codec/                     # 物品 / 物品栏编解码
│   ├── ItemCodec.java         # 单物品编解码
│   ├── InventoryCodec.java    # 物品栏编解码（含槽位）
│   ├── NbtJsonConverter.java  # NBT ↔ JSON 转换
│   ├── README.md              # ← 编解码详细文档
│   └── package-info.java
└── resources/
    └── inventory-spring.xml   # Spring Bean 定义
```

---

## 四、快速开始：声明式箱子界面

```java
public class ShopView extends InventoryView {
    @Override
    protected InventoryComponent buildRoot() {
        Panel root = new Panel(9, 3);                      // 9列×3行
        root.add(new Button(SlotAppearance.builder()
                .type(Item.DIAMOND_SWORD)
                .name("§b购买武器")
                .build())
                .onClick(e -> e.player().sendMessage("购买成功！")),
                1, 1);                                      // 放在第1列第1行
        return root;
    }
}

// 打开界面
inventoryAPI.openView(player, new ShopView());
```

> 完整能力（所有组件、布局、事件、图层、动态更新）见 [`ui/README.md`](ui/README.md)。

---

## 五、快速开始：物品编解码

```java
// 单物品 ↔ Base64 字符串
String encoded = ItemCodec.encode(itemStack);
Item restored  = ItemCodec.decode(encoded);

// 整个物品栏 ↔ JSON（保留槽位）
String json = InventoryCodec.toJson(inventory);
Inventory restoredInv = InventoryCodec.fromJson(json, size);
```

> 两种格式规范、异常处理、设计说明见 [`codec/README.md`](codec/README.md)。

---

## 六、InventoryAPI 门面

[`InventoryAPI`](ui/InventoryAPI.java) 是模块的公开入口，实现 [`PluginAware`](../core/module/PluginAware.java)，在插件启用时自动注册 Nukkit 事件监听器：

| 方法 | 说明 |
|------|------|
| [`openView(player, view)`](ui/InventoryAPI.java:59) | 为玩家打开视图（已有视图会先关闭） |
| [`closeView(player)`](ui/InventoryAPI.java:68) | 关闭玩家当前视图 |
| [`getView(player)`](ui/InventoryAPI.java:78) | 获取玩家当前打开的视图（无则 `null`） |
| [`closeAll()`](ui/InventoryAPI.java:85) | 关闭所有视图（框架停服时自动调用） |

在 `jframe_main` 环境下，通过 [`JFrameMain.getInventoryAPI()`](../main/JFrameMain.java) 获取。

---

## 七、深入阅读

| 主题 | 文档 |
|------|------|
| 声明式箱子界面用法 | [`ui/README.md`](ui/README.md) |
| 界面框架实现原理 | [`ui/DEVELOPER.md`](ui/DEVELOPER.md) |
| 物品 / 物品栏编解码 | [`codec/README.md`](codec/README.md) |
| 维护者文档 | [DEVELOPER.md](DEVELOPER.md) |
