# jframe_form — 表单（GUI 窗口）模块

> 面向对象的 Nukkit 表单框架：统一管理 SimpleForm / CustomForm / ModalForm 三种窗口，
> 支持链式布局构建、按钮对象回调、栈式导航、窗口间数据传递与通知更新。

---

## 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [三种表单类型](#三种表单类型)
  - [SimpleForm（按钮列表）](#simpleform按钮列表)
  - [CustomForm（输入表单）](#customform输入表单)
  - [ModalForm（确认框）](#modalform确认框)
- [窗口导航](#窗口导航)
- [窗口间数据传递](#窗口间数据传递)
- [按钮图标](#按钮图标)
- [生命周期](#生命周期)
- [高级特性](#高级特性)
  - [关闭回调](#关闭回调)
  - [构建策略](#构建策略)
  - [通知刷新](#通知刷新)
  - [视图替换与新根](#视图替换与新根)
- [完整示例](#完整示例)
- [Maven 依赖](#maven-依赖)

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **零第三方依赖** | 直接封装 Nukkit MOT 原生 `FormWindow` API，不再依赖 `moe.him188.gui` |
| **面向对象按钮** | 每个 [`Button`](window/Button.java) 自带点击回调，告别 `switch(id)` 魔法索引 |
| **统一三种表单** | [`JForm`](window/JForm.java) 统一抽象，SimpleForm / CustomForm / ModalForm 一套 API |
| **栈式导航** | [`ViewManager`](ViewManager.java) 维护视图栈，支持进入子菜单、返回上一级、原地替换 |
| **数据总线** | [`ViewDataBus`](data/ViewDataBus.java) 实现窗口间数据塞入与订阅通知更新 |
| **自动清理** | 玩家退出时自动释放资源，无内存泄漏 |

---

## 快速开始

### 第 1 步：创建一个视图

继承 [`FormView`](FormView.java)，在 [`onBuild()`](FormView.java) 中用链式 Builder 描述界面：

```java
public class MainMenuView extends FormView {

    @Override
    protected JForm onBuild() {
        return new SimpleForm("§6主菜单")
                .content("§7请选择功能")
                .button("§a查看统计", click -> {
                    click.addStack(new StatsView());   // 进入子界面
                })
                .button("§c关闭", click -> {
                    click.close();                     // 关闭菜单
                });
    }
}
```

### 第 2 步：发送给玩家

通过 [`ViewAPI`](ViewAPI.java) 打开界面（`ViewAPI` 由 Spring 自动注入）：

```java
@Autowired
private ViewAPI viewAPI;

// 在命令处理器或事件处理器中：
viewAPI.sendForm(new MainMenuView(), player);
```

就这么简单！玩家会看到一个带两个按钮的菜单，点击按钮自动执行对应回调。

---

## 三种表单类型

### SimpleForm（按钮列表）

最常用的类型：标题 + 正文 + 若干按钮。

```java
new SimpleForm("§6商店")
        .content("§7欢迎光临！")
        .button("§a购买", click -> buy(click.player()))
        .button("§e出售", click -> sell(click.player()))
        .button("§c退出", click -> click.close());
```

**便捷方法**：`button(String text, Consumer<ButtonClick> handler)` 内联创建按钮并注册回调。

**对象方式**：也可显式创建 [`Button`](window/Button.java) 对象（适合需要设置图标的场景）：

```java
.button(new Button("§a购买", FormIcon.path("textures/items/apple"))
        .onClick(click -> buy(click.player())))
```

---

### CustomForm（输入表单）

用于收集玩家输入。通过类型化的 [`FormElement`](element/FormElement.java) 添加输入控件：

```java
InputElement nameEl = new InputElement("昵称", "请输入昵称");
DropdownElement modeEl = new DropdownElement("模式", "生存", "创造", "冒险");
ToggleElement pvpEl = new ToggleElement("允许 PvP", false);

new CustomForm("§b创建角色")
        .element(nameEl)
        .element(modeEl)
        .element(pvpEl);
```

**读取提交值**有两种方式：

```java
@Override
protected void onResult(FormResult result) {
    // 方式 1：直接从元素对象读取（类型安全）
    String name = nameEl.value();
    String mode = modeEl.value();
    boolean pvp = pvpEl.value();

    // 方式 2：按标签从结果读取
    String name2 = result.get("昵称");
}
```

#### 可用元素一览

| 元素类 | 说明 | 值类型 | 示例 |
|--------|------|--------|------|
| [`InputElement`](element/InputElement.java) | 文本输入框 | `String` | `new InputElement("昵称", "占位提示")` |
| [`DropdownElement`](element/DropdownElement.java) | 下拉选择 | `String` | `new DropdownElement("难度", "简单", "困难")` |
| [`SliderElement`](element/SliderElement.java) | 数值滑块 | `Float` | `new SliderElement("音量", 0, 100, 1)` |
| [`StepSliderElement`](element/StepSliderElement.java) | 步进滑块 | `String` | `new StepSliderElement("画质", "低", "中", "高")` |
| [`ToggleElement`](element/ToggleElement.java) | 开关 | `Boolean` | `new ToggleElement("开启 PvP", false)` |
| [`LabelElement`](element/LabelElement.java) | 纯文本标签 | `String` | `new LabelElement("§e提示文字")` |

---

### ModalForm（确认框）

二选一确认对话框，常用于「确认 / 取消」：

```java
new ModalForm("§e确认传送", "是否传送到主城？")
        .buttons("§a确认", "§c取消")
        .onConfirm(() -> teleport(player))
        .onCancel(() -> player.sendMessage("§7已取消"));
```

也可用 `onClick(Consumer<Boolean>)` 统一处理（参数 `true` = 点了第一个按钮）。

---

## 窗口导航

[`ViewManager`](ViewManager.java) 为每位玩家维护一个**视图栈**，栈顶即为当前显示的界面。

### 在视图内部导航

```java
// 进入子界面（当前界面压入栈底）
addStack(new DetailView(item));

// 返回上一级（弹出当前界面，自动显示父界面）
goBack();

// 原地替换为另一个界面（保持父视图关系，替换后自动发送）
replaceThis(new EditView(item));

// 关闭整个界面栈
close();

// 刷新当前界面（重新构建并显示）
refresh();

// 清空整个视图栈，以新视图为根重新开始
restartWith(new HomeView());
```

### 在按钮回调中导航

[`ButtonClick`](response/ButtonClick.java) 提供了便捷导航方法，无需持有视图引用：

```java
.button("返回", click -> click.goBack())
.button("刷新", click -> click.refresh())
.button("替换", click -> click.replaceThis(new EditView()))
.button("回首页", click -> click.restartWith(new HomeView()))
.button("关闭", click -> click.close())
```

### 统一重发机制

玩家对界面做出回应（提交表单或关闭窗口）后，框架会**统一重发当前栈顶视图**——
无论回调中执行了什么导航操作（`goBack()` / `addStack()` / `replaceThis()` / `refresh()` / 无操作），
最终都只会发送一次栈顶界面。只有当栈被清空（如 `close()`）时，才没有界面显示。

回调过程中所有 `send()` 调用都会被延迟合并，避免重复注册响应处理器。

---

## 窗口间数据传递

### 场景一：一次性数据传递（带参打开 / 更换视图）

所有「切换到新视图」的导航方法都支持附加一个数据参数，目标视图通过
[`onData(Object)`](FormView.java) 接收：

```java
// 目标视图：接收数据
@Override
protected void onData(Object data) {
    this.item = (Item) data;
}
```

| 导航场景 | 方法 | 说明 |
|---------|------|------|
| 进入子界面 | `addStack(new DetailView(), item)` | 压入新视图并传参 |
| 原地替换 | `replaceThis(new EditView(), item)` | 同层替换并传参（保持父视图关系） |
| 回到首页 | `restartWith(new HomeView(), payload)` | 清空栈、以新视图为根并传参 |

```java
// 父界面：打开详情时传入物品
.button("查看详情", click -> addStack(new DetailView(), selectedItem))

// 编辑界面：替换为预览界面时把草稿传过去
.button("预览", click -> replaceThis(new PreviewView(), draft))

// 流程结束后回到首页，带上结算结果
.button("完成", click -> restartWith(new HomeView(), result))
```

### 场景二：共享状态 + 通知更新（数据总线）

[`ViewDataBus`](data/ViewDataBus.java) 是一个基于「键」的发布订阅通道，
同一玩家的所有视图共享同一个实例。

```java
// 主菜单：订阅金币变化，变化时自动刷新
@Override
protected void onShow() {
    subscribe("coins", v -> refresh());
}

// 商店子界面：购买后更新金币，自动通知主菜单刷新
.button("购买", click -> {
    putData("coins", newCoins);   // 写入数据总线 → 自动通知订阅者
    refresh();
});
```

**数据总线 API**（在 `FormView` 中直接调用）：

| 方法 | 说明 |
|------|------|
| `putData(key, value)` | 写入数据并通知订阅者 |
| `getData(key)` / `getData(key, default)` | 读取数据 |
| `subscribe(key, listener)` | 订阅某个键的变化 |
| `passDataTo(targetView, data)` | 向另一个视图传递一次性数据 |

---

## 按钮图标

通过 [`FormIcon`](window/FormIcon.java) 为按钮添加图标：

```java
// 客户端内置纹理路径
new Button("苹果", FormIcon.path("textures/items/apple"))

// 网络图片 URL
new Button("头像", FormIcon.url("https://example.com/avatar.png"))
```

---

## 生命周期

[`FormView`](FormView.java) 提供以下生命周期回调（均可选重写）：

| 方法 | 触发时机 | 典型用途 |
|------|----------|----------|
| [`onBuild()`](FormView.java) | 每次发送界面前 | 构建表单布局（**必须实现**） |
| [`onShow()`](FormView.java) | `onBuild()` 之后、发送之前 | 订阅数据、播放音效 |
| [`onResult(FormResult)`](FormView.java) | 玩家提交表单后 | 读取输入值、执行业务逻辑 |
| [`onCloseAttempt()`](FormView.java) | 玩家点击 X 关闭后 | 决定是否真正关闭（默认重发栈顶；goBack/close 才不重发） |
| [`onClose()`](FormView.java) | 窗口确认关闭 / 被弹出栈 | 取消订阅、清理资源 |
| [`onData(Object)`](FormView.java) | 收到其他视图塞入的数据 | 接收一次性参数 |

> **注意**：默认情况下 `onBuild()` 在每次发送前都会重新执行。
> 若视图内容基本静态，可切换为按需构建模式以提升性能，详见[构建策略](#构建策略)。

---

## 高级特性

### 关闭回调

当玩家点击窗口右上角 X（或按 ESC）时，窗口**已经被客户端关闭**，无法阻止。
随后框架调用 [`onCloseAttempt()`](FormView.java)，并在其返回后**重新发送当前栈顶视图**
（默认「窗口弹回」）。若不希望关闭后重发当前界面，需在 `onCloseAttempt()` 中主动操作视图栈：

```java
public class EditorView extends FormView {

    @Override
    protected void onCloseAttempt() {
        if (hasUnsavedChanges()) {
            // 有未保存内容 → 弹出确认框（替换栈顶，重发确认框）
            addStack(new ConfirmExitView());
        } else {
            // 无未保存内容 → 真正关闭整个界面（清空栈，不再重发）
            close();
        }
    }
}
```

常见的后续行为选择：

| 需求 | 代码 |
|------|------|
| 关闭后重发当前界面（默认，窗口弹回） | 空实现或不重写 |
| 返回上一级 | `goBack()` |
| 彻底关闭界面 | `close()` |
| 弹出确认框 | `addStack(new ConfirmExitView())` |
| 重新打开当前界面 | `refresh()` |

### 构建策略

通过 [`BuildStrategy`](FormView.java) 控制表单何时重新构建：

| 策略 | 行为 | 适用场景 |
|------|------|----------|
| `ALWAYS`（默认） | 每次发送前重新执行 `onBuild()` | 内容会动态变化的视图 |
| `ON_DEMAND` | 仅首次构建或 `markDirty()` 后重建 | 内容基本静态的视图 |

```java
public class SettingsView extends FormView {

    public SettingsView() {
        buildStrategy(BuildStrategy.ON_DEMAND);  // 按需构建
    }

    @Override
    protected JForm onBuild() {
        // 仅在首次或 markDirty() 后执行
        return new SimpleForm("§6设置").content(...);
    }

    // 当设置变化时手动标记需要重建
    public void onSettingsChanged() {
        markDirty();
    }
}
```

> 随时可用 [`refresh()`](FormView.java) 强制重建，不受策略影响。
> 仅当本视图是当前栈顶时才立即重发；若已被子界面覆盖（非栈顶），则只标记为脏，待重回栈顶时自然重建——避免打断玩家当前查看的子界面。

### 通知刷新

当一个视图的数据变化需要通知**其他视图**（或全部视图）重建时，
可使用通知刷新方法，无需手动获取 `ViewManager`：

| 方法 | 说明 |
|------|------|
| [`notifyRefresh(target)`](FormView.java) | 通知指定视图刷新（标记为脏，若为当前栈顶则立即重发） |
| [`notifyRefreshAll()`](FormView.java) | 通知栈中所有视图刷新（全部标记为脏，重发当前栈顶） |

```java
public class ShopView extends FormView {

    @Override
    protected void onResult(FormResult result) {
        if (result.clicked("buy")) {
            // 购买后库存变化，通知商品列表视图刷新
            notifyRefresh(productListView);
            // 或通知所有相关视图刷新
            notifyRefreshAll();
            goBack();
        }
    }
}
```

> 底层委托 [`ViewManager.refreshView()`](ViewManager.java) / [`refreshAll()`](ViewManager.java)。

### 视图替换与新根

**原地替换**（`replaceThis`）：在同一层级切换界面，保持父视图关系不变，
替换后自动发送新视图：

```java
// 编辑界面 → 预览界面（可 goBack 回到父菜单）
replaceThis(new PreviewView(item));

// 也可在替换时传递一次性数据（目标视图通过 onData 接收）
replaceThis(new PreviewView(), draft);
```

**建立新根**（`restartWith`）：清空整个视图栈，以新视图为根重新开始。
触发所有旧视图的 `onClose()`：

```java
// 完成流程后回到首页（清空所有导航历史）
restartWith(new HomeView());

// 同样支持传递一次性数据
restartWith(new HomeView(), result);
```

---

## 完整示例

参见 [`jframe_example`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view) 模块，
覆盖了表单框架的全部特性，可对照源码学习：

**入门**
- [`MainMenuView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/MainMenuView.java) — 主菜单（SimpleForm + 按钮回调 + 进入子界面）
- [`StatsView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/StatsView.java) — 统计详情（SimpleForm + goBack 栈式返回）

**表单特性总览**
- [`FormDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/FormDemoView.java) — 特性演示总菜单（汇总入口）

**三种表单类型**
- [`CustomFormDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/CustomFormDemoView.java) — 自定义表单（全部 6 种输入元素 + onResult）
- [`ModalFormDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/ModalFormDemoView.java) — 模态框（onConfirm / onCancel / onClick）
- [`IconDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/IconDemoView.java) — 按钮图标（FormIcon path / url）

**栈式导航**
- [`NavigationDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/NavigationDemoView.java) — 导航演示（addStack / replaceThis / restartWith / close / goBack，含实时栈快照）
- [`NavigationSubView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/NavigationSubView.java) — 子界面（addStack 目标，深度 +1）
- [`NavigationReplacedView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/NavigationReplacedView.java) — 替换后视图（replaceThis 目标，同层替换）

**数据总线与数据传递**
- [`DataBusDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/DataBusDemoView.java) — 数据总线（putData / getData / subscribe / notifyRefreshAll + 商店联动）
- [`ShopSubView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/ShopSubView.java) — 商店子界面（跨视图数据传输与更新通知）
- [`OnDataDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/OnDataDemoView.java) — 一次性数据传递（addStack(data) / passDataTo）
- [`OnDataDetailView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/OnDataDetailView.java) — 详情界面（onData 接收参数）

**构建策略**
- [`BuildStrategyDemoView`](../../../../../jframe_example/src/main/java/io/github/JiangHu/jframe/example/view/BuildStrategyDemoView.java) — 按需构建（ON_DEMAND / markDirty / refresh）

---

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.JiangHu</groupId>
    <artifactId>jframe_form</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

本模块仅依赖 `jframe_core`（用于 `PluginAware` 自动绑定机制）与 Nukkit MOT API，
**不再依赖** `moe.him188.gui`。

> 📖 更详细的 API 说明请参阅 [DEVELOPER.md](DEVELOPER.md)
