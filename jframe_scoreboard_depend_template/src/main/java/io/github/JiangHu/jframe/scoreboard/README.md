# jframe_scoreboard

> 像前端开发一样写计分板——定义模板，改数据，自动刷新。

## 📑 目录

- [为什么用 Nukkit 原生 Scoreboard API](#为什么用-nukkit-原生-scoreboard-api)
- [核心特性](#核心特性)
- [架构](#架构)
- [快速开始](#快速开始)
  - [1. 获取 API](#1-获取-api)
  - [2. 定义模板](#2-定义模板)
  - [3. 显示 & 更新](#3-显示--更新)
- [模板语法](#模板语法)
- [API 参考](#api-参考)
- [四层数据粒度](#四层数据粒度)
- [KeepAlive 与生命周期](#keepalive-与生命周期)
- [跨插件加载模板](#跨插件加载模板)
- [完整示例](#完整示例)
- [最佳实践](#最佳实践)
- [依赖模块](#依赖模块)

---

## 为什么用 Nukkit 原生 Scoreboard API

> **背景**：早期版本的 Nukkit-MOT scoreboard API 缺少客户端同步逻辑，导致计分板无法显示。
> 当时本模块通过直接构造网络数据包（`player.dataPacket()`）绕过此问题。
>
> **现在**：Nukkit-MOT 已补全原生 Scoreboard API 的客户端同步逻辑，本模块已全面迁移至
> [`cn.nukkit.scoreboard.scoreboard.IScoreboard`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/README.md)
> 原生 API，由 Nukkit 内部负责数据包的构造与发送。

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

## 核心特性

| 特性 | 说明 |
|------|------|
| **模板驱动** | 用 XML 定义计分板内容结构（标题 + 行），一次编写，反复渲染 |
| **响应式更新** | 数据变化自动触发重新渲染，无需手动刷新 |
| **SpEL 表达式** | `{{ }}` 插值 + `<if>` 条件 + `<each>` 循环，SpEL 语法全覆盖 |
| **三种发送模式** | 指定玩家 / 条件筛选 / 全体广播 |
| **四层数据粒度** | 引擎全局 / 玩家全局(Session) / 计分板局部(Request)，像前端状态管理一样分层管理数据 |
| **KeepAlive 缓存** | 切换计分板时保留数据和渲染状态，切回时秒恢复，不丢数据 |
| **自动清理** | EffectScope 集中管理副作用，玩家退出自动释放全部资源，杜绝内存泄漏 |
| **增量渲染** | 基于依赖图只重新渲染受影响的行，而非全量重建 |
| **Spring 集成** | 一行 `@Autowired ScoreboardAPI` 即可使用 |

## 架构

```
┌─────────────────────────────────────────────────────┐
│                   ScoreboardAPI                      │  ← 用户入口（门面）
│              show / showIf / showAll                 │
│              hide / update / loadTemplate            │
└──────────────────────┬──────────────────────────────┘
                       │ 委托
┌──────────────────────▼──────────────────────────────┐
│                  ScoreboardManager                   │  ← 管理器
│      模板注册表 + KeepAlive 视图缓存                    │
│    templates:      Map<String, ScoreboardTemplate>   │
│    cachedViews:    Map<UUID, Map<String, View>>      │
│    activeTemplate: Map<UUID, String>                 │
└──────┬───────────────────────────────┬───────────────┘
       │                               │
       ▼                               ▼
┌──────────────┐              ┌────────────────────────┐
│ Scoreboard   │              │     ScoreboardView     │  ← 每玩家实例
│ Template     │              │                        │
│              │              │  DataContext (响应式)   │
│ • name       │◄─────────────│  TemplateEngine        │
│ • template   │              │  IScoreboard (原生API) │
│ • displaySlot│              │                        │
│ • sortOrder  │              │  onChange → refresh()  │
└──────────────┘              └────────────────────────┘
```

**数据流**：

```
DataContext.put("coins", 1000)
    ↓ onChange 触发
TemplateEngine.render(template, data)
    ↓ SpEL 求值
RenderResult(title, lines)
    ↓
IScoreboard.addLine() / removeLine()   ← Nukkit 原生 API
    ↓ Nukkit 内部自动发包
玩家看到更新后的计分板
```

> **原生 API**：计分板通过 Nukkit 的 `IScoreboard` 接口管理，
> `addViewer()` / `removeViewer()` / `addLine()` / `removeLine()` 等方法
> 内部自动构造并发送 `SetDisplayObjectivePacket` / `SetScorePacket` 给客户端。

## 快速开始

### 1. 获取 API

```java
@Autowired
private ScoreboardAPI scoreboard;

// 或通过 JFrameMain 门面
ScoreboardAPI scoreboard = JFrameMain.getInstance().getScoreboardAPI();
```

### 2. 定义模板

```java
scoreboard.loadTemplate("main", """
    <template>
        <title>§e§l我的服务器</title>
        <line>§7玩家: §f{{player.name}}</line>
        <line>§7金币: §f{{coins}}</line>
        <line>§7等级: §f{{level}}</line>
    </template>
    """);
```

### 3. 显示 & 更新

```java
// 给指定玩家显示
scoreboard.show(player, "main");

// 更新数据（自动刷新）
scoreboard.update(player, "coins", 1000);
scoreboard.update(player, "level", 30);

// 给所有在线玩家显示
scoreboard.showAll("main");

// 给 OP 玩家显示管理员面板
scoreboard.showIf(Player::isOp, "admin");

// 隐藏
scoreboard.hide(player);
```

## 模板语法

### 基本结构

```xml
<template>
    <title>标题（支持 {{ }} 插值）</title>
    <line>第 1 行</line>
    <line>第 2 行</line>
</template>
```

### `{{ }}` 表达式插值

使用 SpEL（Spring Expression Language）语法：

```xml
<line>金币: {{coins}}</line>                    <!-- 简单变量 -->
<line>总计: {{coins + gems}}</line>             <!-- 算术运算 -->
<line>状态: {{health > 50 ? '安全' : '危险'}}</line>  <!-- 三元 -->
<line>名字: {{player.name}}</line>              <!-- 嵌套属性 -->
<line>大写: {{name.toUpperCase()}}</line>       <!-- 方法调用 -->
```

### `<if>` 条件渲染

```xml
<line>
    <if test="vip">
        <text>§6[VIP] {{player.name}}</text>
    </if>
    <elif test="level >= 50">
        <text>§b[高手] {{player.name}}</text>
    </elif>
    <else>
        <text>§7{{player.name}}</text>
    </else>
</line>
```

### `<each>` 循环渲染

```xml
<each items="topPlayers" var="p" index="i">
    <line>§e#{{i + 1}} §f{{p.name}} §7- {{p.score}}</line>
</each>
```

> `items` 是 DataContext 中的 List/数组，`var` 是循环变量名，`index` 是可选的索引变量名。

### 完整模板示例

```xml
<template>
    <title>§e§l⚔ 战斗信息</title>

    <!-- 静态行 -->
    <line>§8═══════════════</line>

    <!-- 动态行 -->
    <line>§c生命: {{health}}/{{maxHealth}}</line>
    <line>§a攻击: {{attack}}</line>
    <line>§b防御: {{defense}}</line>

    <!-- 条件行 -->
    <line>
        <if test="buff != null">
            <text>§d增益: {{buff}}</text>
        </if>
        <else>
            <text>§7无增益效果</text>
        </else>
    </line>

    <!-- 循环行（排行榜） -->
    <each items="kills" var="entry" index="i">
        <line>§6#{{i + 1}} §f{{entry.target}} §7×{{entry.count}}</line>
    </each>
</template>
```

## API 参考

### [`ScoreboardAPI`](ScoreboardAPI.java:64) — 用户入口

#### 模板管理

| 方法 | 说明 |
|------|------|
| `loadTemplate(name, xmlSource)` | 从 XML 源码编译并注册模板 |
| `loadTemplate(name)` | 从已注册的 TemplateLoader 加载模板文件 |
| `loadTemplate(name, plugin)` | 从指定插件 jar 加载模板（默认前缀 `templates/`） |
| `loadTemplate(name, plugin, prefix)` | 从指定插件 jar 的 `prefix` 目录加载模板 |
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回 [`ScoreboardPluginScope`](ScoreboardPluginScope.java)，绑定指定插件的 ClassLoader |

#### 显示 / 隐藏

| 方法 | 说明 |
|------|------|
| `show(player, templateName)` | 给指定玩家显示计分板 |
| `showIf(predicate, templateName)` | 给满足条件的玩家显示，返回成功数量 |
| `showAll(templateName)` | 给所有在线玩家显示，返回成功数量 |
| `hide(player)` | 隐藏指定玩家的计分板 |
| `hideAll()` | 隐藏所有玩家的计分板 |
| `onPlayerQuit(player)` | 玩家退出时清理（应在事件中调用） |

#### 玩家全局数据（Session 作用域：该玩家所有计分板共享）

| 方法 | 说明 |
|------|------|
| `updatePlayer(player, key, value)` | 更新玩家全局数据（**切换计分板不丢**） |
| `updatePlayerAll(player, Map)` | 批量更新玩家全局数据 |
| `getPlayerDataContext(player)` | 获取玩家全局数据上下文 |

#### 计分板局部数据（Request 作用域：仅当前激活计分板）

| 方法 | 说明 |
|------|------|
| `update(player, key, value)` | 更新当前计分板局部数据（自动刷新） |
| `update(player, Map)` | 批量更新局部数据（一次刷新） |
| `updateAll(key, value)` | 更新所有玩家当前计分板的同一数据项 |
| `updateAll(Map)` | 批量更新所有玩家当前计分板的同一批数据 |
| `getDataContext(player)` | 获取当前计分板局部数据上下文 |

#### 模板数据（第二层：模板全局）

| 方法 | 说明 |
|------|------|
| `updateTemplate(templateName, key, value)` | 更新模板级共享数据（同模板所有玩家可见） |
| `updateTemplateAll(templateName, Map)` | 批量更新模板级共享数据 |
| `getTemplateDataContext(templateName)` | 获取模板数据上下文（可直接操作） |

#### 引擎数据（第一层：引擎全局）

| 方法 | 说明 |
|------|------|
| `updateGlobal(key, value)` | 更新全局数据（所有玩家共享，自动刷新所有在线玩家） |
| `updateGlobalAll(Map)` | 批量更新全局数据 |
| `getGlobalDataContext()` | 获取全局数据上下文（可直接操作） |

#### 其他

| 方法 | 说明 |
|------|------|
| `getView(player)` | 获取玩家计分板视图 |

### [`ScoreboardTemplate`](ScoreboardTemplate.java:40) — 模板配置

```java
// 快捷创建（默认配置）
ScoreboardTemplate.of("main", template);

// Builder 自定义配置
ScoreboardTemplate.builder("main", template)
    .displaySlot(DisplaySlot.SIDEBAR)    // 显示槽位
    .sortOrder(SortOrder.DESCENDING)     // 排序方式
    .criteriaName("dummy")               // Nukkit criteria
    .build();
```

### [`DataContext`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/DataContext.java) — 响应式数据

```java
DataContext data = scoreboard.getDataContext(player);

data.put("coins", 1000);                  // 单项更新 → 自动刷新
data.putAll(Map.of("a", 1, "b", 2));      // 批量更新 → 一次刷新
data.put("player.name", "Steve");         // 嵌套路径（自动创建 Map）
data.get("coins");                        // 读取
data.onChange(change -> { ... });         // 注册监听器
```

## 四层数据粒度

本模块采用**四层数据粒度**，对齐 Java EE 的 Application / Session / Request 作用域模型，像前端框架的状态管理一样分层管理数据：

```
┌──────────────────────────────────────────────────────────┐
│  第一层：引擎全局（Engine Global）— Application           │
│  所有玩家共享，进程级生命周期                                │
│  例：服务器名、在线人数、全局公告                             │
│  API: updateGlobal() / getGlobalDataContext()             │
├──────────────────────────────────────────────────────────┤
│  第二层：玩家全局（Player Global）— Session               │
│  该玩家所有计分板、所有下游模块共享                          │
│  例：玩家名称、金币、等级（切换计分板不丢）                   │
│  API: updatePlayer() / getPlayerDataContext()             │
├──────────────────────────────────────────────────────────┤
│  第三层：计分板局部（View Local）— Request                │
│  仅当前激活的计分板可见                                      │
│  例：临时状态、当前面板特有数据                              │
│  API: update() / getDataContext()                         │
└──────────────────────────────────────────────────────────┘

  ※ 模板全局（Template Global）作为可选独立层保留 API，
    不强制进入主 parent 链。API: updateTemplate()
```

### 读取优先级

**计分板局部 > 玩家全局 > 引擎全局**

同名 key 时，层级越低（越靠近玩家）优先级越高。

### 写入隔离

每层 `put()` 只写入自己的存储，**不影响父层**。

```java
// 引擎全局写入（所有玩家共享）
scoreboard.updateGlobal("serverName", "我的服务器");

// 玩家全局写入（该玩家所有计分板共享，切换不丢）
scoreboard.updatePlayer(player, "coins", 1000);

// 计分板局部写入（仅当前激活计分板可见）
scoreboard.update(player, "tmpKey", "tmpVal");
```

### 变更传播

- **父层变更 → 向下传播**：引擎全局数据变化时，所有玩家全局和计分板局部都会收到通知并刷新
- **局部变更 → 不向上传播**：计分板局部数据变化不会影响玩家全局或引擎全局

### 玩家全局 vs 计分板局部——如何选择？

| 场景 | 推荐层 | API |
|------|--------|-----|
| 玩家核心数据（coins、level、player.name），切换计分板后仍需保留 | **玩家全局** | `updatePlayer()` |
| 当前面板临时数据（如战斗面板的 buff 倒计时），切走后不需要 | **计分板局部** | `update()` |

> **关键区别**：玩家全局数据在切换计分板时**纹丝不动**；计分板局部数据随 view 的
> deactivate/reactivate 生命周期走（KeepAlive 缓存命中时保留，否则随 dispose 释放）。

### 模板中使用四层混合数据

```xml
<template>
    <title>§e{{serverName}}</title>                    <!-- 引擎全局 -->
    <line>§7玩家: §f{{player.name}}</line>              <!-- 玩家全局 -->
    <line>§7金币: §6{{coins}}</line>                    <!-- 玩家全局 -->
    <line>§7Buff: §d{{buffTimer}}</line>                <!-- 计分板局部 -->
</template>
```

> **原理**：[`ScoreboardManager.show()`](ScoreboardManager.java) 内部为每个玩家创建
> [`HierarchicalDataContext`](../../jframe_template/src/main/java/io/github/JiangHu/jframe/content_template/HierarchicalDataContext.java)
> 父链：`计分板局部 → 玩家全局 → 引擎全局`。玩家全局由
> [`TemplateEngine`](../../jframe_template/src/main/java/io/github/JiangHu/jframe/content_template/TemplateEngine.java)
> 统一管理（Session 作用域），玩家退出时自动 `dispose()` 清理。

### 四层对比表

| 层级 | 作用域 | 生命周期 | 典型场景 | API |
|------|--------|----------|----------|-----|
| **引擎全局** | 所有玩家 | Application | 服务器名、在线人数 | `updateGlobal()` |
| **玩家全局** | 单个玩家 | Session | 玩家名称、金币、等级 | `updatePlayer()` |
| **计分板局部** | 单个计分板 | Request | 面板临时状态 | `update()` |
| *模板全局* | *同模板玩家* | *Application* | *公会战分数（可选）* | `updateTemplate()` |

---

## KeepAlive 与生命周期

### 三段式生命周期

[`ScoreboardView`](ScoreboardView.java) 采用 KeepAlive 模式，切换计分板时**不销毁**旧视图，而是停用（deactivate）保留状态：

| 方法 | 显示 | dataContext | 渲染监听 | 用途 |
|------|------|-------------|----------|------|
| `show` / `reactivate` | 显示 | 不动 | 注册 | 进入或恢复激活 |
| `deactivate` | 隐藏 | **保留** | 移除（停渲染） | 切换/隐藏（KeepAlive） |
| `dispose` | 隐藏 | **销毁** | 全部摘除 | 玩家退出 |

### 切换计分板的数据保留

```
玩家显示 "main" 计分板
    ↓ show(player, "admin")
"main" view → deactivate（数据/渲染状态保留在缓存）
"admin" view → reactivate（从缓存恢复，秒显示）
    ↓ show(player, "main")
"admin" view → deactivate
"main" view → reactivate（coins 等玩家全局数据从未离开）
```

> **核心保证**：玩家全局数据（`updatePlayer` 写入）独立于具体计分板，
> 无论怎么切换都**不会丢失**。计分板局部数据（`update` 写入）在 KeepAlive 缓存命中时保留。

### EffectScope 自动清理

[`EffectScope`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EffectScope.java)
集中管理 view 的全部副作用（监听器、dataContext），dispose 时自动执行清理链，**从根上杜绝内存泄漏**：

```
ScoreboardView 构造时：
    effectScope.register(dataContext::dispose)

玩家退出 onPlayerQuit(player)：
    view.dispose(player)
        → effectScope.dispose()
            → dataContext.dispose()  // 摘除对玩家全局的监听引用
    engine.removePlayerData(uuid)     // dispose 玩家全局，摘除对引擎全局的监听引用
```

> **设计灵感**：对齐 Vue 3 的 `effectScope`——注册副作用时一并注册清理逻辑，
> scope 销毁时框架自动执行全部清理，无需开发者手动逐个摘除。

## 跨插件加载模板

默认情况下，`loadTemplate(name)` 从 `TemplateEngine` 的全局 loader 链加载（JFrame 自身类路径或已注册的文件目录）。如果你的模板放在**另一个插件的 jar** 里，使用带 `Plugin` 参数的重载：

```java
// 业务插件 MyAddon 的 jar 内有 templates/main.xml
scoreboard.loadTemplate("main", myAddonPlugin);

// 自定义路径前缀：从 MyAddon 的 scoreboard/ 目录加载
scoreboard.loadTemplate("main", myAddonPlugin, "scoreboard/");
```

**原理**：方法内部用 `plugin.getClass().getClassLoader()` 获取该插件的 `PluginClassLoader`，构造 [`ClasspathTemplateLoader`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/loader/ClasspathTemplateLoader.java) 从该插件 jar 读取资源。这绕过了 Nukkit 的插件类加载器隔离，实现「主插件渲染 + 扩展插件提供模板」的解耦。

> **目录约定**：`loadTemplate(name, plugin)` 默认前缀为 `templates/`，即查找 `templates/{name}.xml`。自定义前缀用三参重载。

### forPlugin 作用域代理

除了 `loadTemplate(name, plugin)` 重载，还可以用 [`forPlugin`](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/ForPlugin.java) 获取作用域代理，链式调用更简洁：

```java
// 从当前插件 jar 加载记分板模板
scoreboard.forPlugin(this).loadTemplate("hud");

// 指定自定义前缀
scoreboard.forPlugin(this).withPrefix("scoreboard/").loadTemplate("main");

// 从其他插件加载（按插件名）
scoreboard.forPlugin("OtherPlugin").loadTemplate("custom");
```

[`ScoreboardPluginScope`](ScoreboardPluginScope.java) 只暴露 `loadTemplate` 和 `withPrefix` 方法，内部通过 [`ClasspathTemplateLoader`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/loader/ClasspathTemplateLoader.java) 从绑定插件的 jar 加载模板。

> 详见 [core/README — ForPlugin](../../jframe_core/src/main/java/io/github/JiangHu/jframe/core/README.md#四forplugin跨插件作用域代理)。

## 完整示例

### 场景：经济系统计分板

```java
@CommandController("economy")
public class EconomyController {

    @Autowired
    private ScoreboardAPI scoreboard;

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 首次加载时注册模板
        if (scoreboard.getView(player) == null) {
            scoreboard.loadTemplate("economy", """
                <template>
                    <title>§e§l💰 经济面板</title>
                    <line>§7玩家: §f{{player.name}}</line>
                    <line>§7金币: §6{{coins}}</line>
                    <line>§7存款: §6{{bank}}</line>
                    <line>§8═══════════</line>
                    <line>§7排名: §e#{{rank}}</line>
                </template>
                """);
        }

        // 显示计分板
        scoreboard.show(player, "economy");

        // 填充初始数据
        scoreboard.update(player, Map.of(
            "player.name", player.getName(),
            "coins", economyApi.getCoins(player),
            "bank", economyApi.getBank(player),
            "rank", economyApi.getRank(player)
        ));
    }

    // 玩家退出时清理
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scoreboard.onPlayerQuit(event.getPlayer());
    }

    // 定时更新排名（全体玩家同一数据）
    @ScheduledEverySecond
    public void updateRank() {
        scoreboard.updateAll("rank", economyApi.getGlobalRank());
    }
}
```

### 场景：管理员监控面板（条件筛选）

```java
// 只给 OP 显示
scoreboard.loadTemplate("admin", """
    <template>
        <title>§c§l⚙ 管理面板</title>
        <line>§7TPS: {{tps}}</line>
        <line>§7在线: {{online}}/{{max}}</line>
        <line>§7内存: {{memory}}%</line>
        <each items="warnings" var="w">
            <line>§c⚠ {{w}}</line>
        </each>
    </template>
    """);

// showIf 筛选 OP 玩家
scoreboard.showIf(Player::isOp, "admin");
```

### 场景：多模板切换

```java
// 注册多个模板
scoreboard.loadTemplate("survival", survivalXml);
scoreboard.loadTemplate("creative", creativeXml);
scoreboard.loadTemplate("spectator", spectatorXml);

// 根据游戏模式切换
@EventHandler
public void onGameModeChange(PlayerGameModeChangeEvent event) {
    Player player = event.getPlayer();
    String mode = event.getNewGameMode().name().toLowerCase();
    scoreboard.show(player, mode);  // 自动隐藏旧模板，显示新模板
}
```

### 场景：四层数据混合使用

```java
// 第一层：引擎全局（所有玩家共享）
scoreboard.updateGlobal("serverName", "我的服务器");
scoreboard.updateGlobal("online", server.getOnlinePlayers().size());

// 第二层：玩家全局（该玩家所有计分板共享，切换不丢）
scoreboard.updatePlayer(player, "kills", 15);
scoreboard.updatePlayer(player, "deaths", 3);

// 第三层：计分板局部（仅当前激活计分板）
scoreboard.update(player, "buffTimer", 30);
```

```xml
<!-- 模板中混合引用四层 -->
<template>
    <title>§e{{serverName}} §7| §f战斗面板</title>  <!-- 引擎全局 -->
    <line>§7击杀: §f{{kills}} §7死亡: §f{{deaths}}</line>    <!-- 玩家全局 -->
    <line>§dBuff: {{buffTimer}}s</line>                       <!-- 计分板局部 -->
</template>
```

## 最佳实践

1. **模板注册一次**：在插件 `onEnable` 或玩家首次加入时注册模板，不要重复注册
2. **批量更新**：多个字段同时变化时用 `update(player, Map)` 或 `updateAll(Map)`，只触发一次渲染
3. **选择正确的数据层**：
   - 所有玩家共享 → `updateGlobal()`
   - 同模板玩家共享 → `updateTemplate()`
   - 玩家核心数据（切换不丢） → `updatePlayer()`
   - 仅当前计分板 → `update()`
4. **退出清理**：在 `PlayerQuitEvent` 中调用 `onPlayerQuit(player)`，框架自动 dispose 全部缓存视图和玩家全局数据，杜绝内存泄漏
5. **行数限制**：Nukkit 侧边栏最多 15 行，单行最长 30 字符（见 [`ScoreboardConstants`](ScoreboardConstants.java:14)）

## 依赖模块

| 模块 | 说明 |
|------|------|
| [`jframe_dependency_template`](../../jframe_dependency_template/pom.xml) | 模板引擎（XML 解析 + SpEL 渲染 + AST 缓存） |
| [`jframe_core`](../../jframe_core/pom.xml) | `DataContext` 响应式数据容器 |
