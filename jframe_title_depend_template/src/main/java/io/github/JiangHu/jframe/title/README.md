# jframe_title — 模板驱动的标题系统

> 基于 jframe_template 引擎的 Nukkit 标题模块：**主标题 / 副标题 / 动作栏**三通道渲染，
> **TITLE / ACTIONBAR 双槽位**独立争用，**瞬时（TRANSIENT）/ 常驻（PERSISTENT）**双模式，
> 与 jframe_scoreboard 完全一致的 API 风格与四层数据粒度。

---

## 目录

- [为什么需要 jframe_title](#为什么需要-jframe_title)
- [核心特性](#核心特性)
- [架构](#架构)
- [快速开始](#快速开始)
- [模板语法](#模板语法)
- [API 参考](#api-参考)
- [四层数据粒度](#四层数据粒度)
- [双模式与生命周期](#双模式与生命周期)
- [跨插件加载](#跨插件加载)
- [完整示例](#完整示例)
- [最佳实践](#最佳实践)
- [依赖模块](#依赖模块)

---

## 为什么需要 jframe_title

原生 Nukkit 标题 API（`sendTitle` / `sendActionBar`）存在以下痛点：

| 原生痛点 | jframe_title 方案 |
|---------|------------------|
| 字符串拼接，样式与数据耦合 | 模板 + 占位符 `{{key}}`，数据驱动渲染 |
| 每次全量重发，无法局部刷新 | 通道级 diff：title+subtitle / actionbar 独立比对，无变化不发包 |
| 标题与动作栏互相覆盖，无并发管理 | 双槽位模型：TITLE 槽与 ACTIONBAR 槽独立争用，公告与常驻信息栏共存 |
| 瞬时标题到期后无法自动回收 | 瞬时模式到期自动清除（可配 `autoclear`）并回收视图 |
| 常驻 actionbar 会自然消退 | 常驻模式 + `title.actionbar.refresh` 周期续期 |
| 各插件各自发送，互相干扰 | 统一 TitleAPI 门面 + 槽位调度，跨插件共享模板引擎与数据层 |

---

## 核心特性

1. **三通道渲染**：`<title>` 标签 → 主标题；`lines[from, to)` → 副标题（多行以 `\n` 拼接）；`lines[actionbarLine]` → 动作栏
2. **双槽位管理**：副标题区间非空即占 TITLE 槽；`actionbarLine >= 0` 即占 ACTIONBAR 槽；同一模板可同时占用双槽
3. **双模式**：`persistent`（常驻，KeepAlive + 可选动作栏续期）/ `transient`（瞬时，fadeIn/stay/fadeOut 到期自动回收）
4. **三级配置融合**：Builder 显式设置 > 模板元数据（`title.*`）> 内置默认
5. **通道级增量刷新**：数据变更只重发发生变化的通道；批量更新合并为一次渲染
6. **四层数据粒度**：引擎全局 / 模板全局 / 玩家全局 / 视图局部（与 scoreboard 一致）
7. **列格式对齐**：复用 jframe_template 的 `columns` 列格式能力，副标题多行列表可按列对齐
8. **跨插件加载**：`forPlugin(...)` 从任意插件 jar 内加载标题模板，注册结果统一管理

---

## 架构

```
TemplateEngine（jframe_template：编译 / 渲染 / 列格式 / 元数据 / 四层数据）
        ↑
   TitleManager（模板注册表 + TITLE/ACTIONBAR 双槽位视图管理 + KeepAlive）
        ↑                         ↑
   TitleView ×N            channel 包（元数据解析 / 通道切分）
   （通道级 diff +          nukkit 包（TitleSender/TitleScheduler 适配）
    主线程调度）
        ↑
   TitleAPI（门面，面向用户的统一入口）
```

- **TitleAPI**：轻量门面，全部公开 API 的唯一入口，自身无业务逻辑
- **TitleManager**：模板注册表、双槽位占用表（`玩家 → 模板名`）、视图缓存与 KeepAlive
- **TitleView**：单玩家单模板视图，通道级 diff 与主线程调度
- **TitleTemplate**：`Template` + 时序 + 通道映射配置的不可变打包（三级融合产物）
- **channel 包**：`TitleMetaKeys`（9 个 `title.*` key）/ `TitleMetaResolver`（解析融合）/ `TitleChannelMapper`（渲染结果 → 三通道纯切分）
- **nukkit 包**：`TitleSender`（发包适配）/ `TitleScheduler`（主线程调度适配）接口 + Default 实现，测试可注入桩

---

## 快速开始

### 1. 获取 API

jframe_main 环境下（已通过五步曲接入）：

```java
// 方式一：主插件门面方法
TitleAPI title = JFrameMain.getInstance().getTitleAPI();

// 方式二：ServiceManager
TitleAPI title = Server.getInstance().getServiceManager().getProvider(TitleAPI.class);
```

独立环境（无 Spring）：

```java
TitleAPI title = TitleAPI.create(new TemplateEngine());
```

### 2. 注册并显示一个瞬时公告

```java
TitleAPI title = JFrameMain.getInstance().getTitleAPI();

// 注册模板（时序与模式写在模板元数据里）
title.loadTemplate("welcome", """
        <template>
            <meta key="title.mode" value="transient"/>
            <meta key="title.timing.fadeIn" value="10"/>
            <meta key="title.timing.stay" value="60"/>
            <meta key="title.timing.fadeOut" value="20"/>
            <title>§e欢迎来到服务器</title>
            <line>§a{{player.name}}</line>
        </template>
        """);

// 玩家进服时显示
title.updatePlayer(player, "player.name", player.getName());
title.show(player, "welcome");
```

---

## 模板语法

标题模板完全复用 jframe_template 的 XML 语法（`<title>` / `<line>` / 块级 `<if>/<for>` / 行内 `<if>/<for>` / 占位符 / 颜色码），并消费两类扩展能力：**`title.*` 元数据标签**与**列格式**。

### title.* 元数据标签（共 9 个）

在 `<template>` 根下用 `<meta key="..." value="..."/>` 声明，由 title 模块在模板构建时解析融合：

| key | 默认值 | 说明 |
|-----|-------|------|
| `title.subtitle.from` | `0` | 副标题区间起点（含），对应 `lines[from, ...)` |
| `title.subtitle.to` | `1` | 副标题区间终点（不含）；`from == to` 表示不占 TITLE 槽 |
| `title.actionbar.line` | `1` | 动作栏行号；`-1` 表示禁用动作栏通道 |
| `title.timing.fadeIn` | `10` | 淡入时长（tick） |
| `title.timing.stay` | `70` | 停留时长（tick） |
| `title.timing.fadeOut` | `20` | 淡出时长（tick） |
| `title.mode` | `persistent` | 显示模式：`persistent` / `transient` |
| `title.autoclear` | `true` | 瞬时模式到期是否自动清除标题 |
| `title.actionbar.refresh` | `0` | 动作栏周期续期间隔（tick）；`0` 不续期 |

**通道映射规则**（`TitleChannelMapper`）：

- 主标题 ← `<title>` 标签渲染产物
- 副标题 ← `lines[subtitle.from, subtitle.to)` 半开区间，多行以 `\n` 拼接（越界自动裁剪）
- 动作栏 ← `lines[actionbar.line]`；行号 `-1` 或行不存在时该通道无内容

**约束**（`TitleTemplate.Builder.build()` fail-fast 校验）：

- `from >= 0`、`to >= from`、`actionbar >= -1`、`refresh >= 0`
- **通道互斥**：`actionbar` 行号不得落入 `[from, to)` 区间，否则抛 `IllegalArgumentException`
- 副标题行数超过 8 行仅记录告警（客户端可能显示异常）

**容错**：非 `title.*` 前缀的元数据属于其他模块命名空间，title 侧静默忽略；`title.*` 前缀但未知的 key 记录告警日志（不抛异常）。

### 列格式（复用 jframe_template）

副标题多行列表可启用列格式，让 `|` 分隔的单元格按列对齐：

```xml
<template columns="|">
    <meta key="title.subtitle.from" value="0"/>
    <meta key="title.subtitle.to" value="4"/>
    <meta key="title.actionbar.line" value="-1"/>
    <columns>
        <column align="left" width="12"/>
        <column align="center" width="8"/>
        <column align="right" width="10"/>
    </columns>
    <title>§l§6排行榜</title>
    <line>§e排名|§e玩家|§e金币</line>
    <line>1|{{top1.name}}|{{top1.coins}}</line>
    <line>2|{{top2.name}}|{{top2.coins}}</line>
    <line>3|{{top3.name}}|{{top3.coins}}</line>
</template>
```

- 根属性 `columns="|"` 声明分隔符（支持多字符，如 `;;`）
- `<columns>` 配置块按序声明每列策略：`align`（`left`/`center`/`right`，缺省 `left`）、`width`（`auto` 或正整数，缺省 `auto`）
- 对齐宽度按**显示宽度**计算（中文占 2 格），列格式在引擎渲染阶段完成，title 侧只做行切分

---

## API 参考

`TitleAPI` 全部公开方法（与 ScoreboardAPI 风格对齐）：

### 模板管理

| 方法 | 说明 |
|------|------|
| `loadTemplate(String name, String xmlSource)` | 编译 XML 源码并注册（三级融合），返回 `TitleTemplate` |
| `loadTemplate(TitleTemplate template)` | 注册预构建模板（Builder 定制产物） |
| `loadTemplate(String name)` | 按名从引擎 loader 链加载并注册；不存在返回 `null` |
| `removeTemplate(String name)` | 移除模板：销毁全部玩家对应视图并清理槽位标记、模板级数据 |

### 显示 / 隐藏

| 方法 | 说明 |
|------|------|
| `show(Player, String)` → `TitleView` | 显示标题（双槽位切换）；模板不存在返回 `null` |
| `showIf(Predicate<Player>, String)` → `int` | 给满足条件的在线玩家显示，返回成功数 |
| `showAll(String)` → `int` | 给全体在线玩家显示，返回成功数 |
| `hide(Player)` | 隐藏该玩家双槽视图（KeepAlive，可再 show 恢复） |
| `hideAll()` | 隐藏所有在线玩家的双槽视图 |

### 数据更新（自动触发增量渲染）

| 方法 | 数据层 | 说明 |
|------|-------|------|
| `updatePlayer(Player, String, Object)` | 玩家全局 | 该玩家全部视图共享，切换模板不丢 |
| `updatePlayerAll(Player, Map<String, Object>)` | 玩家全局 | 批量，只触发一次变更通知 |
| `update(Player, String, Object)` | 视图局部 | 遍历双槽激活视图 |
| `update(Player, Map<String, Object>)` | 视图局部 | 批量，只触发一次增量渲染 |
| `updateAll(String, Object)` / `updateAll(Map)` | 玩家全局 | 全体正在显示标题的玩家 |
| `updateGlobal(String, Object)` / `updateGlobalAll(Map)` | 引擎全局 | 所有玩家共享（服务器名、在线数等） |
| `updateTemplate(String, String, Object)` / `updateTemplateAll(String, Map)` | 模板全局 | 同模板玩家共享，不同模板隔离 |

### 数据访问与视图查询

| 方法 | 说明 |
|------|------|
| `getPlayerDataContext(Player)` | 玩家全局数据上下文（Session 作用域） |
| `getDataContext(Player)` | 当前 TITLE 槽激活视图的局部上下文；无则 `null` |
| `getGlobalDataContext()` | 引擎全局数据上下文 |
| `getTemplateDataContext(String)` | 模板全局数据上下文 |
| `getView(Player)` | 当前 TITLE 槽激活视图；无则 `null` |
| `getActionBarView(Player)` | 当前 ACTIONBAR 槽激活视图；无则 `null` |

### 生命周期

| 方法 | 说明 |
|------|------|
| `onPlayerQuit(Player)` | 玩家退出清理（应在 `PlayerQuitEvent` 中调用） |
| `disposeAll()` | 销毁全部视图并清空缓存与槽位标记（模块停用入口） |

### 其他

| 方法 | 说明 |
|------|------|
| `getManager()` / `getEngine()` | 底层管理器 / 模板引擎（高级用法） |
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回绑定插件 ClassLoader 的 `TitlePluginScope` |
| `static create(TemplateEngine)` | 静态工厂（独立环境） |

### TitleTemplate.Builder（三级融合最高优先级）

```java
TitleTemplate template = TitleTemplate.builder("welcome", engineTemplate)
        .timing(TitleTiming.of(10, 60, 20))   // 整体时序（tick：淡入/停留/淡出）
        .mode(TitleMode.TRANSIENT)            // 显示模式
        .subtitleLines(0, 2)                  // 副标题区间 [from, to)
        .actionbarLine(-1)                    // 动作栏行号；-1 禁用
        .autoClear(true)                      // 瞬时到期自动清除
        .actionBarRefresh(40)                 // 动作栏续期间隔（tick）；0 不续期
        .build();                             // 三级融合 + fail-fast 校验
title.loadTemplate(template);
```

---

## 四层数据粒度

与 jframe_scoreboard 完全一致的数据模型，读取优先级 **玩家 > 模板 > 引擎**：

| 层 | 作用域 | 更新入口 | 典型数据 |
|----|-------|---------|---------|
| 引擎全局 | 所有玩家共享 | `updateGlobal` | 服务器名、在线人数、当前时间 |
| 模板全局 | 同模板玩家共享 | `updateTemplate` | 游戏模式、队伍名称 |
| 玩家全局 | 单玩家所有视图共享 | `updatePlayer` | coins、level、player.name |
| 视图局部 | 单玩家单视图私有 | `update` | 该视图特有的临时状态 |

数据变更经 `HierarchicalDataContext` 父链自动传播，触发对应视图的通道级增量渲染。

---

## 双模式与生命周期

### 瞬时模式（TRANSIENT）

- 按 `fadeIn + stay + fadeOut` 时序显示，**到期自动清除**并回收视图（移除缓存与槽位标记）
- `title.autoclear = false` 时到期不清屏（标题自然淡出后由客户端处理），视图仍会回收
- 适合：公告、欢迎语、成就提示、技能冷却提示

### 常驻模式（PERSISTENT，默认）

- 视图持续保持，`hide()` 时 KeepAlive（deactivate 保留数据与渲染状态），再次 `show()` 同名模板直接复用缓存
- 动作栏通道可配 `title.actionbar.refresh`（如 `40` tick = 2 秒）周期续期，抵消客户端动作栏自然消退
- 适合：常驻 HUD 信息栏、金币/血量显示

### 双槽位

每位玩家两个独立槽位，**互不干扰**：

- **TITLE 槽**：副标题区间非空（`from < to`）的模板占用
- **ACTIONBAR 槽**：`actionbarLine >= 0` 的模板占用
- `show` 时按目标模板占用情况先停用同槽位当前视图再激活目标视图
- 副标题区间为空（`from == to`）的模板（如纯动作栏信息栏）不参与 TITLE 槽争用——**常驻动作栏与瞬时公告可双槽共存**

---

## 跨插件加载

业务插件可将标题模板打进自己的 jar，通过 `forPlugin` 加载（默认前缀 `templates/`）：

```java
// 从当前插件 jar 的 templates/welcome.xml 加载
title.forPlugin(this).loadTemplate("welcome");

// 指定自定义前缀（jar 内 title/ 目录）
title.forPlugin(this).withPrefix("title/").loadTemplate("announce");

// 按插件名从其他插件加载
title.forPlugin("OtherPlugin").loadTemplate("custom");
```

- 每次调用 `forPlugin` 创建独立 Scope 实例，注册结果统一到共享的 `TitleManager`
- 模板编译复用引擎的插件作用域（缓存、prefix/suffix 逻辑），title 侧只做标题专属包装（三级融合）+ 注册

---

## 完整示例

### 场景一：瞬时公告（进服欢迎）

```java
TitleAPI title = JFrameMain.getInstance().getTitleAPI();

title.loadTemplate("welcome", """
        <template>
            <meta key="title.mode" value="transient"/>
            <meta key="title.timing.fadeIn" value="10"/>
            <meta key="title.timing.stay" value="80"/>
            <meta key="title.timing.fadeOut" value="20"/>
            <meta key="title.subtitle.from" value="0"/>
            <meta key="title.subtitle.to" value="2"/>
            <meta key="title.actionbar.line" value="-1"/>
            <title>§e欢迎来到 §b{{serverName}}</title>
            <line>§a{{player.name}}，祝你游戏愉快！</line>
            <line>§7当前在线：§f{{online}}</line>
        </template>
        """);

// 公共数据（引擎全局，一次更新全体生效）
title.updateGlobal("serverName", "生存服");
title.updateGlobal("online", Server.getInstance().getOnlinePlayers().size());

@EventHandler
public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    title.updatePlayer(player, "player.name", player.getName());
    title.show(player, "welcome");   // 10+80+20 tick 后自动清除并回收
}
```

### 场景二：常驻动作栏信息栏（与公告共存）

```java
title.loadTemplate("hud", """
        <template>
            <!-- 副标题区间为空：不占 TITLE 槽，可与公告共存 -->
            <meta key="title.subtitle.from" value="0"/>
            <meta key="title.subtitle.to" value="0"/>
            <meta key="title.actionbar.line" value="0"/>
            <meta key="title.actionbar.refresh" value="40"/>
            <line>§6金币: §e{{coins}} §7| §6等级: §a{{level}} §7| §6延迟: §b{{ping}}ms</line>
        </template>
        """);

// 显示常驻 HUD（随后显示的瞬时公告只占 TITLE 槽，互不影响）
title.show(player, "hud");

// 数据变化时增量刷新（只重发动作栏通道）
title.updatePlayer(player, "coins", economy.getCoins(player));
```

### 场景三：多行副标题列表面板（列格式对齐）

```java
title.loadTemplate("top3", """
        <template columns="|">
            <meta key="title.mode" value="transient"/>
            <meta key="title.timing.stay" value="100"/>
            <meta key="title.subtitle.from" value="0"/>
            <meta key="title.subtitle.to" value="4"/>
            <meta key="title.actionbar.line" value="-1"/>
            <columns>
                <column align="left" width="12"/>
                <column align="center" width="10"/>
                <column align="right" width="10"/>
            </columns>
            <title>§l§6金币排行榜</title>
            <line>§e排名|§e玩家|§e金币</line>
            <line>🥇|{{top1.name}}|{{top1.coins}}</line>
            <line>🥈|{{top2.name}}|{{top2.coins}}</line>
            <line>🥉|{{top3.name}}|{{top3.coins}}</line>
        </template>
        """);

// 模板全局数据：同模板所有玩家共享
title.updateTemplateAll("top3", Map.of(
        "top1.name", "Steve", "top1.coins", 9999,
        "top2.name", "Alex", "top2.coins", 8888,
        "top3.name", "Notch", "top3.coins", 7777));

title.showAll("top3");   // 全体在线玩家查看同一榜单
```

---

## 最佳实践

1. **公共数据放全局层**：服务器名、在线人数用 `updateGlobal`，避免逐玩家重复推送
2. **玩家核心数据放玩家全局层**：coins/level 用 `updatePlayer`，切换标题模板不丢失
3. **常驻动作栏务必配置续期**：`title.actionbar.refresh = 40`（2 秒）可抵消客户端动作栏自然消退
4. **纯动作栏模板将副标题区间置空**（`from == to`），让出 TITLE 槽给公告类模板
5. **副标题控制在 8 行以内**：超过仅告警不拦截，但客户端可能显示异常
6. **动作栏行号避开副标题区间**：`actionbar ∈ [from, to)` 会在 `build()` 时抛异常（通道互斥）
7. **批量更新用 Map 变体**：`updatePlayerAll` / `updateTemplateAll` 只触发一次渲染
8. **玩家退出监听 `PlayerQuitEvent` 调用 `onPlayerQuit`**，及时回收视图与数据
9. **模板配置优先写在元数据里**（`title.*`），Builder 只用于运行时动态覆盖，保持模板自描述
10. **多行列表启用列格式**（`columns="|"` + `<columns>` 策略），中文宽度感知对齐比手动补格可靠

---

## 依赖模块

| 模块 | 依赖内容 |
|------|---------|
| `jframe_core` | `ForPlugin` 接口、`PluginClassLoaderFactory`、`DataContext` 响应式数据层 |
| `jframe_template` | `TemplateEngine` / `Template` 编译渲染、`title.*` 元数据、`columns` 列格式、四层数据 |
| Nukkit API | `Player#sendTitle(title, subtitle, fadeIn, stay, fadeOut)`、`Player#sendActionBar`、`Server#getScheduler` 主线程调度 |

- 构建产物：`jframe_title`（jar，随 jframe_main shade 打包）
- Spring 装配：`title-spring.xml`（Bean 链 `templateEngine → titleManager → titleAPI`），经 `TitleSpringConfig` 由 jframe_main 五步曲导入
