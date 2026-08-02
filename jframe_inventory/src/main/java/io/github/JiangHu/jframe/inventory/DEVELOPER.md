# jframe_inventory — 维护者文档

> 面向模块维护者。记录箱子界面与物品编解码两大子包的架构划分、门面装配、协作关系与扩展方式。
> 使用文档请看 [README.md](README.md)；子包实现细节见 [`ui/DEVELOPER.md`](ui/DEVELOPER.md) 与 [`codec/README.md`](codec/README.md)。

---

## 📑 目录

- [一、模块架构](#一模块架构)
- [二、子包独立性与依赖](#二子包独立性与依赖)
- [三、InventoryAPI 门面与装配](#三inventoryapi-门面与装配)
- [四、ui 渲染引擎分层](#四ui-渲染引擎分层)
- [五、codec 设计要点](#五codec-设计要点)
- [六、生命周期](#六生命周期)
- [七、扩展指南](#七扩展指南)

---

## 一、模块架构

本模块由两个**职责正交**的子包组成：

```
jframe_inventory
├── ui/      ← 声明式箱子界面框架（GUI）
│   依赖：Nukkit + Spring + jframe_core(PluginAware)
│   对外门面：InventoryAPI
│
└── codec/   ← 物品 / 物品栏编解码（序列化）
    依赖：仅 Nukkit API + JDK（无 Spring，可独立使用）
    对外入口：ItemCodec / InventoryCodec 静态方法
```

- `ui` 解决「如何用代码描述并渲染一个箱子界面」。
- `codec` 解决「如何把物品 / 物品栏存成字符串再还原」。

二者没有调用关系：`ui` 的组件可以用 `codec` 序列化自身数据（如 `StorageBox` 存储玩家放入的物品），但框架层面不强制耦合。

---

## 二、子包独立性与依赖

| 子包 | 依赖 Spring | 依赖 jframe_core | 可独立使用 |
|------|:---:|:---:|:---:|
| `ui` | ✅（`InventorySpringConfig` 装配） | ✅（`PluginAware`） | 否（需框架容器） |
| `codec` | ❌ | ❌ | ✅（纯静态工具） |

`codec` 刻意保持零框架依赖：所有能力以 `ItemCodec` / `InventoryCodec` 的**静态方法**提供，任何 Nukkit 插件可直接复制该子包源码使用，无需引入 JFrame。

---

## 三、InventoryAPI 门面与装配

[`InventoryAPI`](ui/InventoryAPI.java) 是模块的唯一公开门面，位于 `ui` 包，实现 [`PluginAware`](../core/module/PluginAware.java)：

```java
public class InventoryAPI implements PluginAware {
    private final InventoryManager manager;

    @Override
    public void bindPlugin(Plugin plugin) {
        manager.bindPlugin(plugin);
        Server.getInstance().getPluginManager().registerEvents(manager, plugin);  // 注册事件监听
    }

    public void openView(Player player, InventoryView view) { manager.openView(player, view); }
    // ... closeView / getView / closeAll 委托 manager
}
```

**设计要点**：

- **门面 + 委托**：[`InventoryAPI`](ui/InventoryAPI.java) 不含业务逻辑，全部委托 [`InventoryManager`](ui/manager/InventoryManager.java)。门面隔离了使用者与内部管理器，便于未来替换实现或增加横切逻辑。
- **PluginAware 绑定**：`bindPlugin` 时把 `InventoryManager` 注册为 Nukkit 事件监听器（接收点击、交易、关闭事件）。这一步必须在拿到 `Plugin` 实例后进行，因此用 `PluginAware` 延迟到 `onEnable`。
- **Spring 装配**：[`InventorySpringConfig`](config/InventorySpringConfig.java) + [`inventory-spring.xml`](../../../../../resources/inventory-spring.xml) 定义 `InventoryManager` 与 `InventoryAPI` Bean，由 [`MainSpringConfig`](../main/config/MainSpringConfig.java) 统一 `@Import`。

---

## 四、ui 渲染引擎分层

`ui` 子包采用经典的三层分离（详见 [`ui/DEVELOPER.md`](ui/DEVELOPER.md)）：

```
用户代码层    InventoryView.buildRoot() + Component + SlotAppearance
    ↓
渲染引擎层    InventoryManager（事件分发、交易拦截、增量更新）
    ↓
底层适配层    VirtualInventory / FakeBlockHelper（虚拟箱子、假方块延迟打开）
```

- **用户代码层**：开发者继承 [`InventoryView`](ui/view/InventoryView.java)，用组件（[`Button`](ui/component/Button.java) / [`StorageBox`](ui/component/StorageBox.java) / [`Panel`](ui/component/Panel.java) ...）+ 布局（[`BorderLayout`](ui/layout/BorderLayout.java) / [`GridLayout`](ui/layout/GridLayout.java) / [`ManualLayout`](ui/layout/ManualLayout.java)）描述界面。
- **渲染引擎层**：[`InventoryManager`](ui/manager/InventoryManager.java) 负责把组件树渲染到虚拟箱子、分发点击 / 存储事件、拦截非法交易、处理增量更新。
- **底层适配层**：[`VirtualInventory`](ui/view/VirtualInventory.java) 抽象虚拟箱子；[`FakeBlockHelper`](ui/view/FakeBlockHelper.java) 处理「假方块延迟打开」（玩家右键假方块后才打开界面，避免卡顿）。

---

## 五、codec 设计要点

`codec` 子包的核心设计（详见 [`codec/README.md`](codec/README.md)）：

- **双格式**：每种编解码器同时提供 Base64 紧凑文本（入库）与 JSON 可读格式（调试），还原结果完全等价。
- **完整保真**：[`NbtJsonConverter`](codec/NbtJsonConverter.java) 递归处理 CompoundTag，不丢失任何 NBT 附加数据。
- **位置保留**：[`InventoryCodec`](codec/InventoryCodec.java) 记录每个槽位索引，反序列化精确还原到原位置。
- **类型无关**：统一处理背包 / 箱子 / 末影箱 / 熔炉等任意 Nukkit 原生物品栏。
- **独立于 jframe_data**：`codec` 是针对 Nukkit `Item` 的专用序列化，与 `jframe_data` 的注解驱动序列化框架无关，二者可并存。

---

## 六、生命周期

| 时机 | 行为 |
|------|------|
| 容器装配 | `InventorySpringConfig` 创建 `InventoryManager` + `InventoryAPI` Bean |
| `JFrameMain.onEnable` | `bindPlugin` 注册 `InventoryManager` 为事件监听器 |
| 运行时 | `openView` / `closeView` 管理玩家↔视图映射 |
| `JFrameMain.onDisable` | 调用 [`InventoryAPI.closeAll()`](ui/InventoryAPI.java:85) 关闭所有打开的视图（避免玩家卡在界面） |

> 关闭顺序见 [`JFrameMain.onDisable()`](../main/JFrameMain.java)：先关箱子视图，再关线程池，最后关容器。

---

## 七、扩展指南

### 新增一个界面组件

1. 在 [`ui/component/`](ui/component/) 下新建类，继承或实现 [`InventoryComponent`](ui/component/InventoryComponent.java)。
2. 定义组件的渲染逻辑（如何映射到槽位）与事件处理（点击 / 存储）。
3. 在 [`ui/README.md`](ui/README.md) 补充组件说明。
4. 在 [`jframe_example`](../../example/inventory/InventoryTestView.java) 的全特性测试界面中增加该组件的测试用例。

### 新增一个布局管理器

1. 在 [`ui/layout/`](ui/layout/) 下新建类，实现 [`Layout`](ui/layout/Layout.java) 接口。
2. 实现「将组件排列到槽位坐标」的算法。
3. 文档与测试同上。

### 新增一种编解码格式

1. 在 [`codec/`](codec/) 下扩展 [`ItemCodec`](codec/ItemCodec.java) / [`InventoryCodec`](codec/InventoryCodec.java)，增加新的 `encode`/`decode` 重载。
2. 保持「两种格式还原等价」的约束。
3. 在 [`codec/README.md`](codec/README.md) 补充格式规范，并在测试中增加往返（round-trip）断言。

> 修改 `ui` 渲染引擎核心（`InventoryManager` / `InventoryView`）前，请先阅读 [`ui/DEVELOPER.md`](ui/DEVELOPER.md) 了解事件分发与增量更新机制。
