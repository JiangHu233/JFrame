# jframe_form 开发者文档

> 本文档面向框架开发者与高级使用者，详细说明表单模块的架构设计、类职责、内部流程与扩展点。
> 快速上手请先阅读 [README.md](README.md)。

---

## 目录

- [1. 架构总览](#1-架构总览)
- [2. 包结构](#2-包结构)
- [3. 核心类详解](#3-核心类详解)
  - [3.1 布局层 — `JForm` 体系](#31-布局层--jform-体系)
  - [3.2 元素层 — `FormElement` 体系](#32-元素层--formelement-体系)
  - [3.3 响应层 — `FormResult` / `ButtonClick`](#33-响应层--formresult--buttonclick)
  - [3.4 视图层 — `FormView`](#34-视图层--formview)
  - [3.5 管理层 — `ViewManager` / `ViewAPI`](#35-管理层--viewmanager--viewapi)
  - [3.6 数据层 — `ViewDataBus`](#36-数据层--viewdatabus)
- [4. 内部流程](#4-内部流程)
  - [4.1 发送表单的完整流程](#41-发送表单的完整流程)
  - [4.2 响应处理的完整流程](#42-响应处理的完整流程)
  - [4.3 统一重发机制](#43-统一重发机制)
- [5. 设计模式](#5-设计模式)
- [6. 扩展指南](#6-扩展指南)
- [7. 从旧版迁移](#7-从旧版迁移)

---

## 1. 架构总览

模块采用**四层分离**架构，每层职责单一，层间通过抽象接口通信：

```
┌──────────────────────────────────────────────────────────────┐
│  管理层 (Management)                                           │
│  ViewAPI ──→ ViewManager(视图栈) ──→ ViewDataBus(数据总线)     │
│  职责：玩家级视图生命周期管理、栈操作、自动清理                   │
├──────────────────────────────────────────────────────────────┤
│  视图层 (View)                                                 │
│  FormView                                                     │
│  职责：界面逻辑载体，定义 onBuild/onShow/onResult/onClose       │
├──────────────────────────────────────────────────────────────┤
│  布局层 (Layout)                                               │
│  JForm ← SimpleForm / CustomForm / ModalForm                  │
│  Button + FormIcon                                            │
│  职责：面向对象描述界面布局，链式 Builder                        │
├──────────────────────────────────────────────────────────────┤
│  适配层 (Adapter) — 唯一接触 Nukkit 原生 API 的地方              │
│  FormElement<T> + FormResult + ButtonClick                    │
│  职责：将布局对象转换为 Nukkit 原生对象 / 读取原生响应           │
└──────────────────────────────────────────────────────────────┘
```

**核心设计原则**：适配层是唯一接触 `cn.nukkit.form.*` 的地方。
未来若更换底层 GUI 库（如从 Nukkit MOT 迁移到其他服务端），只需修改适配层的转换逻辑，
视图层与布局层零改动。

---

## 2. 包结构

```
io.github.JiangHu.jframe.form
├── FormView.java              // 视图抽象基类（界面逻辑载体）
├── ViewManager.java           // 单玩家视图栈管理器
├── ViewAPI.java               // 全局入口（玩家→管理器映射 + 自动清理）
├── window/                    // 布局层
│   ├── JForm.java             // 表单统一抽象（SIMPLE/CUSTOM/MODAL）
│   ├── SimpleForm.java        // 简单表单（按钮列表）
│   ├── CustomForm.java        // 自定义表单（输入元素集合）
│   ├── ModalForm.java         // 模态框（二选一确认）
│   ├── Button.java            // 面向对象按钮（文本+图标+回调）
│   └── FormIcon.java          // 按钮图标封装
├── element/                   // 元素层（CustomForm 的输入控件）
│   ├── FormElement.java       // 类型化元素抽象基类 <T>
│   ├── InputElement.java      // 文本输入 → String
│   ├── DropdownElement.java   // 下拉选择 → String
│   ├── SliderElement.java     // 数值滑块 → Float
│   ├── StepSliderElement.java // 步进滑块 → String
│   ├── ToggleElement.java     // 开关 → Boolean
│   └── LabelElement.java      // 纯文本标签 → String
├── response/                  // 响应层
│   ├── FormResult.java        // 统一提交结果（三种表单共用）
│   └── ButtonClick.java       // 按钮点击上下文（含便捷导航）
└── data/                      // 数据层
    └── ViewDataBus.java       // 窗口间数据总线（发布订阅）
```

---

## 3. 核心类详解

### 3.1 布局层 — `JForm` 体系

#### [`JForm`](window/JForm.java)（抽象基类）

所有表单类型的统一抽象，是布局层与 Nukkit 原生 API 之间的**隔离层**。

| 方法 | 说明 |
|------|------|
| `title()` | 表单标题 |
| `type()` | 表单类型枚举（`SIMPLE` / `CUSTOM` / `MODAL`） |
| `toNukkit()` | **模板方法**：调用 `buildWindow()` 转换为原生窗口并缓存 |
| `buildResult(Player)` | 从缓存的原生窗口读取响应，构造 `FormResult` |
| `dispatch(FormView, FormResult)` | 分发各自的回调（SimpleForm→按钮回调，ModalForm→确认/取消回调） |
| `wasClosed()` | 玩家是否直接关闭了窗口 |

> `toNukkit()` 是 `final` 方法，确保转换+缓存的流程不可被子类绕过。

#### [`SimpleForm`](window/SimpleForm.java)

| 方法 | 说明 |
|------|------|
| `content(String)` | 设置正文（链式） |
| `button(Button)` | 追加按钮对象（链式） |
| `button(String, Consumer<ButtonClick>)` | 追加文本按钮 + 回调（链式便捷方法） |
| `button(String)` | 追加纯文本按钮（链式便捷方法） |
| `buttons()` | 全部按钮（不可变视图） |

#### [`CustomForm`](window/CustomForm.java)

| 方法 | 说明 |
|------|------|
| `element(FormElement<?>)` | 追加输入元素（链式） |
| `submitText(String)` | 设置提交按钮文本（链式） |
| `icon(FormIcon)` | 设置表单图标（链式） |
| `elements()` | 全部元素（不可变视图） |

#### [`ModalForm`](window/ModalForm.java)

| 方法 | 说明 |
|------|------|
| `content(String)` | 设置正文（链式） |
| `buttons(String, String)` | 设置两个按钮文本（链式） |
| `onConfirm(Runnable)` | 注册「点击第一个按钮」回调（链式，可多次调用叠加） |
| `onCancel(Runnable)` | 注册「点击第二个按钮」回调（链式，可多次调用叠加） |
| `onClick(Consumer<Boolean>)` | 注册统一点击回调（链式，`true`=第一个按钮） |

> `onConfirm` / `onCancel` / `onClick` 通过 `Consumer<Boolean>` 组合实现叠加，
> 多次调用不会覆盖而是依次执行。

#### [`Button`](window/Button.java)

| 方法 | 说明 |
|------|------|
| `Button(String)` / `Button(String, FormIcon)` | 构造函数 |
| `text()` | 按钮文本 |
| `icon()` / `icon(FormIcon)` | 图标（getter / 链式 setter） |
| `onClick(Consumer<ButtonClick>)` | 注册点击回调（链式） |
| `hasHandler()` | 是否已注册回调 |
| `click(ButtonClick)` | 触发回调（**包级私有**，由 `SimpleForm.dispatch` 调用） |
| `toNukkit()` | 转换为 `ElementButton`（**包级私有**） |

#### [`FormIcon`](window/FormIcon.java)

| 方法 | 说明 |
|------|------|
| `FormIcon.path(String)` | 静态工厂：客户端内置纹理路径 |
| `FormIcon.url(String)` | 静态工厂：网络图片 URL |
| `type()` / `data()` | 图片类型 / 数据 |
| `toNukkit()` | 转换为 `ElementButtonImageData` |

> 封装目的：`ElementButtonImageData` 构造参数顺序为 `(data, type)`，反直觉。
> `FormIcon` 屏蔽此细节，提供语义清晰的工厂方法。

---

### 3.2 元素层 — `FormElement` 体系

#### [`FormElement<T>`](element/FormElement.java)（抽象泛型基类）

为 `CustomForm` 的每种输入控件提供统一的「构建 + 类型化读取」契约。

| 方法 | 说明 |
|------|------|
| `label()` | 元素标签（显示文字） |
| `toNukkit()` | 转换为 Nukkit `Element`（抽象） |
| `read(FormResponseCustom, int)` | 按全局索引读取类型化值（抽象，**protected**） |
| `readAndCache(response, index)` | 读取并缓存值（由 `CustomForm.buildResult` 调用） |
| `value()` | 获取最近一次读取到的值（类型安全） |

> 泛型 `<T>` 使取值类型安全：`InputElement.value()` 直接返回 `String`，无需强转。

#### 六个具体实现

| 类 | Nukkit 对应 | 值类型 | 主要构造参数 |
|----|-------------|--------|-------------|
| [`InputElement`](element/InputElement.java) | `ElementInput` | `String` | `label, placeholder, defaultText` |
| [`DropdownElement`](element/DropdownElement.java) | `ElementDropdown` | `String` | `label, options[], defaultIndex` |
| [`SliderElement`](element/SliderElement.java) | `ElementSlider` | `Float` | `label, min, max, step, defaultValue` |
| [`StepSliderElement`](element/StepSliderElement.java) | `ElementStepSlider` | `String` | `label, steps[], defaultIndex` |
| [`ToggleElement`](element/ToggleElement.java) | `ElementToggle` | `Boolean` | `label, defaultValue` |
| [`LabelElement`](element/LabelElement.java) | `ElementLabel` | `String` | `label` |

---

### 3.3 响应层 — `FormResult` / `ButtonClick`

#### [`FormResult`](response/FormResult.java)

无论哪种表单类型，提交后都构造一个 `FormResult` 交给 `FormView.onResult()`。

| 方法 | 适用类型 | 说明 |
|------|----------|------|
| `player()` | 全部 | 触发提交的玩家 |
| `wasClosed()` | 全部 | 是否直接关闭了窗口 |
| `type()` | 全部 | 表单类型 |
| `clickedButton()` | SIMPLE | 被点击的 `Button` 对象 |
| `clickedIndex()` | SIMPLE | 被点击按钮的索引 |
| `clickedButton1()` | MODAL | `true` = 点了第一个按钮 |
| `clickedButton2()` | MODAL | `true` = 点了第二个按钮 |
| `get(String label)` | CUSTOM | 按元素标签取值 |
| `get(String, T default)` | CUSTOM | 按标签取值，带默认值 |
| `values()` | CUSTOM | 全部值的不可变 Map |

#### [`ButtonClick`](response/ButtonClick.java)

`Button.onClick(Consumer<ButtonClick>)` 回调的参数，封装点击上下文。

| 方法 | 说明 |
|------|------|
| `player()` | 点击玩家 |
| `index()` | 按钮索引 |
| `button()` | 按钮对象 |
| `view()` | 所属视图 |
| `goBack()` | 便捷导航：返回上一级 |
| `refresh()` | 便捷导航：刷新当前界面 |
| `replaceThis(FormView)` | 便捷导航：原地替换（保持父视图关系） |
| `restartWith(FormView)` | 便捷导航：清空栈并以新视图为根 |
| `close()` | 便捷导航：关闭界面栈 |

---

### 3.4 视图层 — `FormView`

#### 生命周期方法（子类可重写）

| 方法 | 性质 | 触发时机 |
|------|------|----------|
| `onBuild()` | **抽象，必须实现** | 每次发送前，返回 `JForm` 描述布局 |
| `onShow()` | 可选 | `onBuild()` 之后、发送之前 |
| `onResult(FormResult)` | 可选 | 玩家提交后（按钮回调已先行触发） |
| `onCloseAttempt()` | 可选 | 玩家点击 X 关闭后，程序员自行决定后续行为（void） |
| `onClose()` | 可选 | 窗口确认关闭 / 被弹出栈 |
| `onData(Object)` | 可选 | 收到其他视图塞入的一次性数据 |

#### 导航方法

| 方法 | 说明 |
|------|------|
| `goBack()` | 返回上一级（弹出当前视图） |
| `addStack(FormView)` | 进入子界面（压入新视图） |
| `addStack(FormView, Object)` | 进入子界面并传递一次性数据 |
| `replaceThis(FormView)` | 原地替换为另一个视图（保持父视图关系，替换后自动发送） |
| `restartWith(FormView)` | 清空整个视图栈，以新视图为根重新开始 |
| `close()` | 关闭整个界面栈 |
| `refresh()` | 标记为脏并刷新：栈顶时立即重建重发；非栈顶时只标记脏，待重回栈顶时重建（不受构建策略限制） |

#### 数据访问方法（委托 `ViewDataBus`）

| 方法 | 说明 |
|------|------|
| `putData(key, value)` | 写入数据总线，自动通知订阅者 |
| `getData(key)` / `getData(key, default)` | 读取数据 |
| `subscribe(key, listener)` | 订阅键变化 |
| `passDataTo(targetView, data)` | 向另一个视图传递一次性数据 |

#### 通知刷新方法（委托 `ViewManager`）

| 方法 | 说明 |
|------|------|
| `notifyRefresh(targetView)` | 通知指定视图刷新（标记为脏，若为当前栈顶则立即重发） |
| `notifyRefreshAll()` | 通知栈中所有视图刷新（全部标记为脏，重发当前栈顶） |

#### 框架内部方法（包级私有）

| 方法 | 调用方 | 说明 |
|------|--------|------|
| `bind(ViewManager)` | `ViewManager.push` | 注入管理器引用 |
| `shouldRebuild()` | `ViewManager.send` | 根据构建策略判断是否需要重建 |
| `rebuild()` | `ViewManager.send` | 调用 `onBuild()` 并缓存结果，清除脏标志 |
| `handleResult(FormResult)` | `ViewManager.handleResponse` | 分发 dispatch + onResult |
| `handleCloseAttempt()` | `ViewManager.handleResponse` | 触发 `onCloseAttempt`（void，程序员自行决定后续） |
| `handleClose()` | `ViewManager` | 触发 onClose |
| `receiveData(Object)` | `ViewManager.push(view, data)` | 触发 onData |

---

### 3.5 管理层 — `ViewManager` / `ViewAPI`

#### [`ViewManager`](ViewManager.java)

单玩家视图栈管理器，每位玩家一个实例。

**栈操作**：

| 方法 | 说明 |
|------|------|
| `push(FormView)` | 压栈（注入管理器引用） |
| `push(FormView, Object)` | 压栈 + 传递一次性数据 |
| `pushAndSend(FormView)` | 压栈并立即发送 |
| `pop()` | 弹出栈顶（触发 onClose） |
| `goBack()` | 返回上一级（栈中 >1 时才弹出） |

**栈的增删改查**：

| 方法 | 说明 |
|------|------|
| `current()` | 栈顶视图 |
| `size()` / `isEmpty()` | 栈大小 / 是否为空 |
| `snapshot()` | 栈快照（不可变列表，栈底在前） |
| `insert(int index, FormView)` | 在指定位置插入视图 |
| `remove(int index)` | 移除指定位置的视图（触发 onClose） |
| `replace(oldView, newView)` | 同层替换 |
| `replaceAndSend(oldView, newView)` | 同层替换并立即发送新视图 |
| `clear()` | 清空栈（逐个触发 onClose） |
| `restartWith(FormView)` | 清空栈并以新视图为根重新开始 |

**发送与响应**：

| 方法 | 说明 |
|------|------|
| `send()` | 发送当前栈顶界面（shouldRebuild? → rebuild → onShow → toNukkit → 注册处理器 → showFormWindow） |
| `refreshCurrent()` | 等同于 `send()` |
| `refreshView(FormView)` | 标记指定视图为脏，若为当前栈顶则立即重发 |
| `refreshAll()` | 标记栈中所有视图为脏，重发当前栈顶 |
| `handleResponse(FormView)` | **私有**：处理玩家响应（见[§4.2](#42-响应处理的完整流程)） |

#### [`ViewAPI`](ViewAPI.java)

全局入口，实现 `PluginAware` + `Listener`。

| 方法 | 说明 |
|------|------|
| `sendForm(FormView, Player)` | 打开界面（自动创建管理器） |
| `sendForm(FormView, Player, Object)` | 打开界面 + 传递数据 |
| `manager(Player)` | 获取管理器（不存在返回 null） |
| `managerOrCreate(Player)` | 获取或创建管理器 |
| `create(Player)` | 创建空管理器 |
| `remove(Player)` | 移除并清理管理器 |
| `onPlayerQuit(PlayerQuitEvent)` | **自动**：玩家退出时清理（由 PluginAware 注册） |

---

### 3.6 数据层 — `ViewDataBus`

#### [`ViewDataBus`](data/ViewDataBus.java)

基于「键」的发布订阅通道，同一玩家的所有视图共享一个实例（由 `ViewManager` 持有）。

| 方法 | 说明 |
|------|------|
| `put(key, value)` | 写入数据 + 通知订阅者（value=null 时删除键） |
| `get(key)` / `get(key, default)` | 读取数据 |
| `contains(key)` | 是否包含键 |
| `remove(key)` | 删除键 + 通知订阅者（值为 null） |
| `subscribe(key, listener)` | 订阅键变化 |
| `unsubscribe(key, listener)` | 取消订阅 |
| `notify(key, value)` | 主动通知（通常由 put 内部调用） |
| `clear()` | 清空全部数据与订阅者 |

**线程安全**：内部使用 `ConcurrentHashMap` + `CopyOnWriteArrayList`，
可在异步线程安全 `put`。但回调执行在调用线程，若需操作主线程 API 应自行调度。

---

## 4. 内部流程

### 4.1 发送表单的完整流程

```
ViewAPI.sendForm(view, player)
    │
    ▼
ViewManager.pushAndSend(view)
    │
    ├── push(view)
    │     └── view.bind(this)          // 注入管理器引用
    │     └── views.push(view)         // 压入栈顶
    │
    └── send()
          ├── view.shouldRebuild()?    // 根据构建策略判断
          │     └── view.rebuild()     // 需要时才调用 onBuild()，缓存 JForm
          ├── view.onShow()            // 生命周期回调
          ├── form.toNukkit()          // 转换为原生 FormWindow（缓存）
          ├── window.addHandler(...)   // 注册 FormResponseHandler
          └── player.showFormWindow()  // 发送给客户端
```

### 4.2 响应处理的完整流程

```
玩家提交/关闭表单
    │
    ▼
FormResponseHandler 回调 → ViewManager.handleResponse(view)
    │  （前置 handlingResponse=true，期间所有 send() 调用被延迟合并）
    │
    ├── form.wasClosed() ?
    │     ├── YES → view.handleCloseAttempt() → onCloseAttempt()
    │     │           （程序员可在此 goBack / close / addStack / 无操作）
    │     └── NO  ↓
    │
    ├── form.buildResult(player)       // 构造 FormResult
    ├── view.handleResult(result)
    │     ├── form.dispatch(view, result)  // 触发按钮/确认回调（可能执行导航）
    │     └── view.onResult(result)        // 统一结果处理
    │
    └── handlingResponse=false → 栈不空？ → doSend()  // 统一重发当前栈顶（见下）
```

### 4.3 统一重发机制

响应处理完毕后（无论提交还是关闭），`ViewManager` 只要视图栈不空，就重新发送当前栈顶：

```java
// ViewManager.handleResponse() 末尾
if (!isEmpty()) {
    doSend();   // 统一重发当前栈顶
}
```

**为什么统一重发？**

回调中可能执行了导航操作（`goBack` / `addStack` / `replaceThis` / `close`），导致栈顶变化。
统一重发当前栈顶，意味着导航方法只需修改栈结构，无需各自操心 `send()`——
框架在回应末尾负责显示最终栈顶。回调过程中所有 `send()` 调用都会被延迟（`handlingResponse` 标志），
最终至多实际发送一次，避免重复注册响应处理器。

| 回调中的操作 | 栈顶变化 | 重发的视图 |
|-------------|----------|-----------|
| 无（仅读取数据） | 不变 | ✅ 当前视图 |
| `refresh()` | 不变 | ✅ 当前视图（已标记脏，会重建） |
| `goBack()` | 变为父视图 | ✅ 父视图 |
| `addStack(child)` | 变为子视图 | ✅ 子视图 |
| `replaceThis(new)` | 变为新视图 | ✅ 新视图 |
| `close()` | 栈空 | ❌ 不重发（无界面） |

---

## 5. 设计模式

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| **模板方法** | [`JForm.toNukkit()`](window/JForm.java) | final 方法定义「转换+缓存」骨架，`buildWindow()` 由子类实现 |
| **建造者** | `SimpleForm` / `CustomForm` / `ModalForm` | 链式 `.content().button().button()` 描述布局 |
| **策略** | [`FormElement.read()`](element/FormElement.java) / [`BuildStrategy`](FormView.java) | 元素读取策略 / 视图构建策略（ALWAYS / ON_DEMAND） |
| **观察者** | [`ViewDataBus`](data/ViewDataBus.java) | 发布订阅模式实现窗口间通知更新 |
| **外观** | [`FormView`](FormView.java) | 将 `ViewManager` + `ViewDataBus` 的复杂操作封装为简洁 API |
| **适配器** | 整个适配层 | 将 Nukkit 异构 API 适配为统一接口 |

---

## 6. 扩展指南

### 6.1 添加新的表单元素类型

如果 Nukkit 新增了一种表单元素（或你需要自定义行为），按以下步骤扩展：

1. 继承 [`FormElement<T>`](element/FormElement.java)，指定值类型 `T`
2. 实现 `toNukkit()`：返回对应的 Nukkit `Element`
3. 实现 `read(FormResponseCustom, int)`：从响应中读取类型化值

```java
public class ColorElement extends FormElement<String> {

    private final String defaultColor;

    public ColorElement(String label, String defaultColor) {
        super(label);
        this.defaultColor = defaultColor;
    }

    @Override
    public Element toNukkit() {
        // 假设 Nukkit 有 ElementColor
        return new ElementColor(label, defaultColor);
    }

    @Override
    protected String read(FormResponseCustom response, int index) {
        return response.getColorResponse(index);
    }
}
```

然后在 `CustomForm` 中直接使用：`new CustomForm("设置").element(new ColorElement("颜色", "#FF0000"))`。

### 6.2 直接操作视图栈（高级）

通过 `ViewAPI.manager(player)` 获取 `ViewManager`，可直接操作栈：

```java
ViewManager mgr = viewAPI.manager(player);
if (mgr != null) {
    // 在栈底插入一个「确认页」
    mgr.insert(0, new ConfirmView());

    // 移除栈中第 2 个视图
    mgr.remove(1);

    // 查看当前导航路径（调试用）
    mgr.snapshot().forEach(v -> System.out.println(v.getClass().getSimpleName()));
}
```

### 6.3 替换底层 GUI 库

适配层是唯一接触 Nukkit 的地方。若需迁移到其他服务端：

1. 修改 [`JForm.buildWindow()`](window/JForm.java) 各子类的实现，返回新平台的窗口对象
2. 修改 [`FormElement.toNukkit()`](element/FormElement.java) 各子类
3. 修改 [`ViewManager.send()`](ViewManager.java) 中的发送与响应注册逻辑
4. 视图层、布局层的业务代码**无需任何改动**

---

## 7. 从旧版迁移

### 7.1 依赖变更

**旧版** `pom.xml`：
```xml
<!-- 已移除 -->
<dependency>
    <groupId>moe.him188.gui</groupId>
    <artifactId>GUI</artifactId>
    <scope>system</scope>
    <systemPath>${project.basedir}/libs/gui-1.15.1.jar</systemPath>
</dependency>
```

**新版**：无需额外依赖，`jframe_form` 自动引入 `jframe_core`。

### 7.2 视图代码迁移

**旧版写法**（`switch(id)` 魔法索引）：

```java
public class OldMenuView extends FormView {
    @Override
    protected FormSimple buildForm() {
        return new FormSimple("菜单", "请选择")
                .addButton("统计")
                .addButton("关闭");
    }

    @Override
    protected void onClicked(int id) {
        switch (id) {
            case 0: replaceThis(new StatsView()); break;  // 索引耦合
            case 1: close(); break;
        }
    }
}
```

**新版写法**（面向对象按钮回调）：

```java
public class NewMenuView extends FormView {
    @Override
    protected JForm onBuild() {
        return new SimpleForm("菜单", "请选择")
                .button("统计", click -> click.addStack(new StatsView()))
                .button("关闭", click -> click.close());
    }
}
```

### 7.3 返回导航迁移

**旧版**：`replaceThis(new MainMenuView(...))` —— 重新构造父界面，丢失状态。

**新版**：`goBack()` —— 直接弹出栈顶，父界面状态完整保留。

### 7.4 迁移检查清单

- [ ] `pom.xml` 中移除 `moe.him188.gui` 依赖与 shade 排除项
- [ ] `extends FormView` 的类：`buildForm()` → `onBuild()`，返回类型 `FormSimple` → `JForm`
- [ ] `onClicked(int id)` 中的 `switch` → 按钮的 `.onClick(click -> ...)`
- [ ] `replaceThis(new ParentView())` → `goBack()`
- [ ] 删除所有 `import moe.him188.gui.*`
