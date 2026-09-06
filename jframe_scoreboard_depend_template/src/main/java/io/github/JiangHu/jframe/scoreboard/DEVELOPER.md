# jframe_scoreboard 开发者文档

> 面向贡献者和二次开发者的内部实现文档。

## 📑 目录

- [Nukkit 原生 Scoreboard API](#nukkit-原生-scoreboard-api)
- [目录结构](#目录结构)
- [类职责矩阵](#类职责矩阵)
- [核心机制](#核心机制)
  - [1. 模板编译与缓存](#1-模板编译与缓存)
  - [2. 响应式更新机制](#2-响应式更新机制)
  - [3. Nukkit 原生 API 架构](#3-nukkit-原生-api-架构)
  - [4. 四层数据粒度](#4-四层数据粒度)
  - [5. 生命周期与 KeepAlive](#5-生命周期与-keepalive)
- [Spring 集成](#spring-集成)
- [模板引擎内部原理](#模板引擎内部原理)

---

## Nukkit 原生 Scoreboard API

> **背景**：早期版本的 Nukkit-MOT scoreboard API 缺少客户端同步逻辑，导致计分板无法显示。
> 当时本模块通过直接构造网络数据包（`player.dataPacket()`）绕过此问题。
>
> **现在**：Nukkit-MOT 已补全原生 Scoreboard API 的客户端同步逻辑，本模块已全面迁移至
> `cn.nukkit.scoreboard.scoreboard.IScoreboard` 原生 API。

| 对比项 | 旧方案（直接发包） | 新方案（Nukkit 原生 API） |
|--------|---------------------|---------------------------|
| 实现方式 | 手动构造 `SetScorePacket` 等数据包 | `IScoreboard` + `FakeScorer` + `addViewer()` |
| 客户端同步 | 手动 `player.dataPacket()` | ✅ Nukkit 内部自动同步 |
| 行管理 | 手动维护 `scoreIdCounter` + `scoreIds` | `IScoreboard.addLine()` / `removeLine()` |
| 重复行去重 | `makeUniqueName()` 追加颜色码后缀 | `makeUniqueName()` 追加颜色码后缀（仍需要） |
| 依赖 | `cn.nukkit.network.protocol.*` | `cn.nukkit.scoreboard.*` |

> **结论**：迁移到原生 API 后，本模块不再需要手动管理数据包协议细节，
> 由 Nukkit 框架负责 `SetDisplayObjectivePacket` / `SetScorePacket` 的构造与发送。

---

## 目录结构

```
jframe_scoreboard/
├── pom.xml                              ← Maven 配置（依赖 jframe_core + jframe_dependency_template）
└── src/main/
    ├── java/io/github/JiangHu/jframe/scoreboard/
    │   ├── ScoreboardAPI.java           ← 用户门面（API 入口）
    │   ├── ScoreboardManager.java       ← 管理器（模板注册表 + 玩家视图映射，模板全局数据委托 TemplateEngine）
    │   ├── ScoreboardView.java          ← 玩家视图（DataContext + Nukkit IScoreboard）
    │   ├── ScoreboardTemplate.java      ← 模板配置（Template + 显示参数）
    │   ├── ScoreboardConstants.java     ← 常量（前缀、默认值、限制）
    │   ├── ScoreboardPluginScope.java   ← forPlugin 作用域代理
    │   ├── config/
    │   │   └── ScoreboardSpringConfig.java  ← Spring 配置类
    │   └── README.md                    ← 用户文档
    └── resources/
        └── scoreboard-spring.xml        ← Spring XML Bean 装配
```

## 类职责矩阵

| 类 | 职责 | 依赖 |
|----|------|------|
| [`ScoreboardAPI`](ScoreboardAPI.java:64) | 用户入口门面，委托转发给 Manager | `ScoreboardManager`, `TemplateEngine` |
| [`ScoreboardManager`](ScoreboardManager.java:32) | 模板注册表 + 玩家视图映射 + 生命周期（模板全局数据委托 `TemplateEngine`） | `TemplateEngine` |
| [`ScoreboardView`](ScoreboardView.java:50) | 单玩家计分板实例，绑定 DataContext ↔ Nukkit IScoreboard | `ScoreboardTemplate`, `DataContext`, `IScoreboard` |
| [`ScoreboardTemplate`](ScoreboardTemplate.java:40) | 模板配置（Template + DisplaySlot + SortOrder） | `Template` |
| [`ScoreboardConstants`](ScoreboardConstants.java:14) | 常量集中管理 | — |

## 核心设计

### 1. 类架构分层（API / Manager / View）

```
ScoreboardAPI（门面）
    └→ ScoreboardManager（管理器）
         ├→ ScoreboardTemplate（模板配置）  ← 静态：注册一次
         └→ ScoreboardView（玩家视图）      ← 动态：每玩家一个
```

> 注意：这里的「分层」指**类职责划分**（API → Manager → View），与下文 [4. 四层数据粒度](#4-四层数据粒度)（数据作用域划分）是不同维度。

**为什么分三层？**
- **API**：对用户隐藏内部细节，提供简洁的方法签名
- **Manager**：管理共享状态（模板注册表、视图映射），模板全局数据委托 [`TemplateEngine`](../../jframe_template/src/main/java/io/github/JiangHu/jframe/content_template/TemplateEngine.java) 管理，可被多个 API 实例共享
- **View**：封装单玩家状态，隔离玩家间的数据

### 2. 响应式更新机制

[`ScoreboardView`](ScoreboardView.java:50) 的核心是 **DataContext.onChange → refresh()** 链路：

```java
// 构造时注册监听器
this.changeListener = change -> refresh();

// show() 时绑定
dataContext.onChange(changeListener);

// 数据变化时
DataContext.put("coins", 1000)
    ↓ 触发 changeListener
ScoreboardView.refresh()
    ↓
IncrementalRenderer.renderIncremental(template, dataContext)
    ↓
IncrementalRenderResult(title, allLines, changedLineIndices)
    ↓
IScoreboard.addLine() / removeLine()   ← Nukkit 原生 API
    ↓ Nukkit 内部自动发包
客户端收到 SetScorePacket → 计分板更新

// hide() 时解绑
dataContext.removeListener(changeListener);
```

### 3. Nukkit 原生 API 架构

> **背景**：Nukkit-MOT 的 `IScoreboard` 原生 API 现在已包含完整的客户端同步逻辑。
> 本模块通过 `IScoreboard` 接口管理计分板，由 Nukkit 内部负责数据包的构造与发送。

[`ScoreboardView`](ScoreboardView.java:50) 使用以下 Nukkit 原生 API：

| Nukkit API | 作用 | 调用时机 |
|------------|------|----------|
| `new Scoreboard(name, displayName, criteria, sortOrder)` | 创建计分板实例 | `show()` 首次显示 |
| `addViewer(player, displaySlot)` | 将计分板显示给玩家（内部发送 `SetDisplayObjectivePacket` + `SetScorePacket`） | `show()` |
| `removeViewer(player, displaySlot)` | 从玩家移除计分板（内部发送 `RemoveObjectivePacket`） | `hide()` |
| `addLine(FakeScorer, score)` | 添加分数行（内部发送 `SetScorePacket`） | `show()` / `refresh()` |
| `removeLine(IScorer)` | 移除分数行（内部发送 `SetScorePacket REMOVE`） | `refresh()` 行数减少 |
| `removeAllLine(true)` | 移除所有行并重发 | `refresh()` 全量更新 |

**FakeScorer 管理**：Bedrock 计分板的每一行本质上是一个 "fake player entry"。
[`ScoreboardView`](ScoreboardView.java:50) 内部维护 `List<FakeScorer> scorers`，
在行数变化时同步增删。

**重复行合并问题**：Bedrock 计分板客户端用 **FakeScorer 的 fakeName** 进行去重。
如果两行的 fakeName 完全相同（如多个空行、重复的分隔符），客户端会认为是同一个 "玩家"
而**合并成一行**，导致计分板内容显示不全。

[`ScoreboardView.makeUniqueName()`](ScoreboardView.java:50) 通过给每行追加**唯一的
不可见颜色代码后缀**（`§0`~`§f`，共 16 个）来解决这个问题：

```
行 0: "§e金币: 100" → fakeName = "§e金币: 100§0"
行 1: ""            → fakeName = "§1"            （空行也能正常显示）
行 2: "§e金币: 100" → fakeName = "§e金币: 100§2"  （与行 0 不再合并）
```

颜色代码追加在文本末尾且后面无可见字符，玩家不可见，不影响显示效果。
`show()` / `applyFullUpdate()` / `applyPerLineUpdate()` 内部自动调用此方法。

### 4. 四层数据粒度

本模块采用**四层数据粒度**，通过 [`HierarchicalDataContext`](../../jframe_template/src/main/java/io/github/JiangHu/jframe/content_template/HierarchicalDataContext.java) 多级父链实现，对齐 Java EE 的 Application / Session / Request 作用域模型。

> **架构说明**：四层中的**引擎全局 + 玩家全局由 [`TemplateEngine`](../../jframe_template/src/main/java/io/github/JiangHu/jframe/content_template/TemplateEngine.java) 统一管理**，
> 本模块（[`ScoreboardManager`](ScoreboardManager.java)）通过委托调用 `engine.getPlayerData()` / `engine.setPlayerData()` 等方法操作玩家全局数据。
> 这样设计使得 inventory、bossbar 等其他模块也能共享同一套四层数据架构。

```
引擎全局（engine.getGlobalData）    ← TemplateEngine 持有，所有玩家共享（Application）
    ↑ parent
玩家全局（engine.getPlayerData）    ← TemplateEngine 持有，按 UUID 隔离（Session）
    ↑ parent
计分板局部（HierarchicalDataContext） ← ScoreboardView 持有，每计分板独立（Request）
```

> 模板全局（`engine.getTemplateData`）作为可选独立层保留 API，不强制进入主 parent 链。

#### 父链构建

[`ScoreboardManager.show()`](ScoreboardManager.java) 内部为每个玩家构建四层父链：

```java
// 第一层：引擎全局（TemplateEngine 持有，Application 作用域）
DataContext engineGlobal = engine.getGlobalData();

// 第二层：玩家全局（委托 TemplateEngine 管理，Session 作用域，切换计分板不丢）
DataContext playerGlobal = engine.getPlayerData(uuid);
// → 内部 new HierarchicalDataContext(engineGlobal)

// 第三层：计分板局部（每计分板独立，Request 作用域）
HierarchicalDataContext viewData = new HierarchicalDataContext(playerGlobal);
```

#### 读取优先级

`HierarchicalDataContext.asMap()` 先合并 parent（玩家全局 → 引擎全局），再覆盖 local（计分板局部）：

```
计分板局部 > 玩家全局 > 引擎全局
```

#### 写入隔离

每层 `put()` 只写入自己的存储，不影响父层：

```java
viewData.put("buffTimer", 30);                  // 只写入计分板局部
engine.setPlayerData(uuid, "coins", 1000);      // 只写入玩家全局（委托）
engine.setGlobal("online", 42);                 // 只写入引擎全局
```

#### 变更传播

- **父层变更 → 向下传播**：`HierarchicalDataContext.onChange()` 同时注册到本地和 parent，
  parent 变化时通过 `onParentChange` 回调通知子层
- **局部变更 → 不向上传播**：子层 `put()` 不会触发 parent 的 `onChange`

#### 玩家全局 vs 计分板局部

| 维度 | 玩家全局（Session） | 计分板局部（Request） |
|------|---------------------|----------------------|
| API | `updatePlayer()` / `engine.setPlayerData()` | `update()` / `viewData.put()` |
| 切换计分板 | **数据保留**（独立于 view） | 随 view 的 deactivate/reactivate 走 |
| 玩家退出 | `engine.removePlayerData()` 销毁 | 随 view dispose 销毁 |
| 典型数据 | coins、level、player.name | buffTimer、面板临时状态 |

### 5. 生命周期与 KeepAlive

[`ScoreboardView`](ScoreboardView.java) 采用 **KeepAlive 模式**，三段式生命周期管理：

#### 三段式生命周期

| 方法 | 显示 | dataContext | 渲染监听 | 触发场景 |
|------|------|-------------|----------|----------|
| `show` / `reactivate` | 显示 | 不动 | 注册 | 进入或恢复激活 |
| `deactivate` | 隐藏 | **保留** | 移除（停渲染） | 切换/隐藏（KeepAlive） |
| `dispose` | 隐藏 | **销毁** | 全部摘除 | 玩家退出 |

#### EffectScope 自动清理

[`EffectScope`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EffectScope.java)
集中管理 view 的全部副作用（监听器、dataContext），dispose 时自动执行清理链：

```java
// ScoreboardView 构造时注册清理逻辑
effectScope.register(dataContext::dispose);

// dispose() 时
effectScope.dispose()
    → dataContext.dispose()   // 摘除对玩家全局的监听引用，杜绝内存泄漏
```

> **设计灵感**：对齐 Vue 3 的 `effectScope`——注册副作用时一并注册清理逻辑，
> scope 销毁时框架自动执行全部清理，无需开发者手动逐个摘除监听器。

#### 玩家退出清理

[`ScoreboardManager.onPlayerQuit()`](ScoreboardManager.java) 执行完整的资源释放：

```
onPlayerQuit(player)
    ↓
遍历该玩家所有 cachedViews
    ├→ view.deactivate()         ← 停渲染、移除监听
    └→ view.dispose()            ← effectScope.dispose() → dataContext.dispose()
cachedViews.remove(uuid)         ← 移除全部缓存
engine.removePlayerData(uuid)    ← dispose 玩家全局，摘除对引擎全局的监听引用
```

> **重要**：必须在 `PlayerQuitEvent` 中调用 `scoreboard.onPlayerQuit(player)`，否则会内存泄漏。

#### 切换计分板的数据保留

```
show(player, "admin")
    ├→ 旧 view("main").deactivate()    ← 数据/渲染状态保留在 cachedViews
    └→ 新 view("admin").reactivate()   ← 从缓存恢复，秒显示
```

> **核心保证**：玩家全局数据（`updatePlayer` 写入）独立于具体计分板，
> 无论怎么切换都**不会丢失**。计分板局部数据在 KeepAlive 缓存命中时保留。

## Spring 集成

### Bean 装配链

[`scoreboard-spring.xml`](../../jframe_scoreboard/src/main/resources/scoreboard-spring.xml)：

```xml
<!-- 1. 模板引擎（来自 jframe_dependency_template） -->
<bean id="templateEngine" class="io.github.JiangHu.jframe.content_template.TemplateEngine"/>

<!-- 2. 计分板管理器 -->
<bean id="scoreboardManager" class="io.github.JiangHu.jframe.scoreboard.ScoreboardManager">
    <constructor-arg ref="templateEngine"/>
</bean>

<!-- 3. 计分板 API（门面） -->
<bean id="scoreboardAPI" class="io.github.JiangHu.jframe.scoreboard.ScoreboardAPI">
    <constructor-arg ref="scoreboardManager"/>
    <constructor-arg ref="templateEngine"/>
</bean>
```

### 模块注册五步曲

新模块接入 JFrame 框架的标准流程：

1. **创建 SpringConfig**：[`ScoreboardSpringConfig.java`](config/ScoreboardSpringConfig.java)
2. **创建 XML 装配**：[`scoreboard-spring.xml`](../../jframe_scoreboard/src/main/resources/scoreboard-spring.xml)
3. **注册 ConfigEnum**：[`ConfigEnum.SCOREBOARD`](../../jframe_main/src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java)
4. **添加 MainSpringConfig**：[`@Import(ScoreboardSpringConfig.class)`](../../jframe_main/src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java)
5. **添加 JFrameMain 门面**：[`getScoreboardAPI()`](../../jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) + Service 注册

## 模板引擎内部原理

计分板模板由 [`jframe_dependency_template`](../../jframe_dependency_template/pom.xml) 模块提供，核心流程：

```
XML 源码字符串
    ↓ TemplateParser（JDK DOM 解析）
AST（抽象语法树）
    ├→ TemplateNode（根节点）
    │    ├→ title: String
    │    └→ lines: List<LineEntry>
    │         ├→ StaticLine（一行，bodyNodes 拼成一行文本）
    │         ├→ ConditionalBlock（块级 <if>/<elif>/<else>，控制行组显隐）
    │         └→ LoopBlock（块级 <for>，每个元素展开一组行）
    │              └→ 行内 TemplateNode：
    │                   ├→ TextNode（静态文本片段）
    │                   ├→ ExpressionNode（{{ }} SpEL 表达式）
    │                   ├→ IfNode（行内条件分支）
    │                   └→ ForNode（行内循环拼接）
    ↓ 缓存到 TemplateEngine
Template 对象（可重复渲染）
    ↓ TemplateRenderer（SpEL 求值）
RenderResult(title, lines)
```

### AST 节点体系

| 节点 | 对应 XML | 职责 |
|------|----------|------|
| [`TemplateNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/TemplateNode.java) | `<template>` | 根节点，持有 title + lines |
| [`StaticLine`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/StaticLine.java) | `<line>...</line>` | 一行，行内节点拼接为一行文本 |
| [`ConditionalBlock`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/ConditionalBlock.java) | 块级 `<if>/<elif>/<else>` | 条件为真的分支整组显示，否则整组隐藏 |
| [`LoopBlock`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/LoopBlock.java) | 块级 `<for>` | 遍历列表，每个元素展开一组行 |
| [`ExpressionNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/ExpressionNode.java) | `{{expr}}` | SpEL 表达式 |
| [`IfNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/IfNode.java) | 行内 `<if>/<elif>/<else>` | 行内条件片段 |
| [`ForNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/ForNode.java) | 行内 `<for>` | 行内循环拼接 |

### 渲染流程

[`TemplateRenderer`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/render/TemplateRenderer.java) 使用 Spring SpEL（`SpelExpressionParser`）求值：

```java
// 简化伪代码
for (LineEntry line : template.getLines()) {
    if (line instanceof StaticLine sl) {
        // 行内节点（文本/表达式/行内 if/行内 for）拼接为一行
        result.add(renderNodes(sl.nodes(), context).strip());
    } else if (line instanceof ConditionalBlock cb) {
        // 首个条件为真的分支，其子条目整组递归渲染；全假走 elseEntries
        result.addAll(renderLineEntries(firstTrueBranch(cb, context), context));
    } else if (line instanceof LoopBlock lb) {
        // 遍历 items，注入 var/index 变量后循环体整组递归渲染
        for (item : takeUpTo(context.get(lb.itemsKey()), lb.max())) {
            result.addAll(renderLineEntries(lb.bodyEntries(),
                    context.withLoopVariable(lb.varName(), item, lb.indexVarName(), i)));
        }
    }
}
```

## DataContext 响应式容器

[`DataContext`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/DataContext.java) 是轻量级响应式键值容器：

```java
// 基本操作
data.put("coins", 1000);                    // → 触发 onChange
data.put("player.name", "Steve");           // → 嵌套路径（自动创建 Map）
data.putAll(Map.of("a", 1, "b", 2));        // → 批量，只触发一次 onChange
data.putSilently("key", value);             // → 不触发 onChange
data.get("coins");                          // → 1000
data.snapshot();                            // → 不可变快照

// 监听器
data.onChange(changeSet -> { ... });        // → 注册
data.removeListener(listener);              // → 移除
```

**为什么不用 `EntangledValue`？**
- `EntangledValue` 是单值响应式容器，适合「一个变量」的场景
- 计分板需要「多个键值对」的集合，`DataContext` 更合适
- `DataContext` 支持嵌套路径（`player.name`）、批量更新（只触发一次渲染）

## 扩展指南

### 自定义 DisplaySlot

```java
// 在 ScoreboardTemplate.Builder 中指定
ScoreboardTemplate.builder("below_name", template)
    .displaySlot(DisplaySlot.BELOW_NAME)   // 显示在玩家头顶
    .sortOrder(SortOrder.ASCENDING)
    .build();
```

### 自定义 TemplateLoader

如果需要从数据库或网络加载模板：

```java
public class DatabaseTemplateLoader implements TemplateLoader {
    @Override
    public Template load(String name) {
        String xml = database.queryTemplate(name);
        return engine.compile(xml);
    }
}

// 注册到 TemplateEngine
engine.addLoader(new DatabaseTemplateLoader());

// 使用
scoreboard.loadTemplate("from_db");
```

### 多计分板共存

当前设计是每玩家一个计分板（`show` 会替换旧的）。如需多槽位共存：

```java
// 扩展 ScoreboardManager，按 slot 维护视图
Map<DisplaySlot, ScoreboardView> viewsBySlot = new ConcurrentHashMap<>();

public ScoreboardView show(Player player, String templateName, DisplaySlot slot) {
    // 按 slot 而非 UUID 索引
}
```

## 调试技巧

### 1. 查看渲染结果

```java
ScoreboardView view = scoreboard.getView(player);
if (view != null) {
    RenderResult result = view.getTemplate().getTemplate()...;  // 手动渲染查看
    System.out.println("Title: " + result.getTitle());
    System.out.println("Lines: " + result.getLines());
}
```

### 2. 检查 DataContext 状态

```java
DataContext data = scoreboard.getDataContext(player);
System.out.println("Data: " + data.asMap());
```

### 3. 监听变更事件

```java
scoreboard.getDataContext(player).onChange(change -> {
    System.out.println("Changed: " + change.key() + " = " + change.newValue());
});
```

## Nukkit API 对接细节

### 关键类与包路径

| Nukkit 类 | 包路径 | 用途 |
|-----------|--------|------|
| `Scoreboard` | `cn.nukkit.scoreboard.scoreboard` | 计分板实例（实现 `IScoreboard`） |
| `IScoreboard` | `cn.nukkit.scoreboard.scoreboard` | 计分板接口 |
| `FakeScorer` | `cn.nukkit.scoreboard.scorer` | 假玩家计分者（用于自定义文本行） |
| `IScorer` | `cn.nukkit.scoreboard.scorer` | 计分者接口 |
| `DisplaySlot` | `cn.nukkit.network.protocol.types` | 显示槽位枚举 |
| `SortOrder` | `cn.nukkit.network.protocol.types` | 排序方式枚举 |

### ScoreboardView 对接流程

```java
// 创建
nukkitScoreboard = new Scoreboard(objectiveName, displayName, criteriaName, sortOrder);
List<FakeScorer> scorers = addAllLines(lines);  // 内部调用 makeUniqueName
nukkitScoreboard.addViewer(player, displaySlot);  // Nukkit 自动发送 SetDisplayObjectivePacket + SetScorePacket

// 更新（全量）
nukkitScoreboard.removeAllLine(true);
scorers = addAllLines(newLines);

// 更新（增量，仅变化的行）
nukkitScoreboard.removeLine(scorers.get(idx));
FakeScorer newScorer = new FakeScorer(makeUniqueName(newText, idx));
nukkitScoreboard.addLine(newScorer, score);
scorers.set(idx, newScorer);

// 销毁
nukkitScoreboard.removeViewer(player, displaySlot);  // Nukkit 自动发送 RemoveObjectivePacket
```

> **注意**：`Scoreboard` 的 `displayName` 在构造时设置，`refresh()` 目前只更新 `lines`。
> 如需动态标题，需扩展 Nukkit API 调用。
