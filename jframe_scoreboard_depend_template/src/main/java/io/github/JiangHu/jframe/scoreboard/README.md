# jframe_scoreboard

> 像前端开发一样写计分板——定义模板，改数据，自动刷新。

## 核心特性

| 特性 | 说明 |
|------|------|
| **模板驱动** | 用 XML 定义计分板内容结构（标题 + 行），一次编写，反复渲染 |
| **响应式更新** | 数据变化自动触发重新渲染，无需手动刷新 |
| **SpEL 表达式** | `{{ }}` 插值 + `<if>` 条件 + `<each>` 循环，SpEL 语法全覆盖 |
| **三种发送模式** | 指定玩家 / 条件筛选 / 全体广播 |
| **玩家隔离** | 每个玩家拥有独立的 `DataContext`，互不干扰 |
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
│         模板注册表 + 玩家视图映射                       │
│    templates: Map<String, ScoreboardTemplate>        │
│    views:     Map<UUID,    ScoreboardView>           │
└──────┬───────────────────────────────┬───────────────┘
       │                               │
       ▼                               ▼
┌──────────────┐              ┌────────────────────────┐
│ Scoreboard   │              │     ScoreboardView     │  ← 每玩家实例
│ Template     │              │                        │
│              │              │  DataContext (响应式)   │
│ • name       │◄─────────────│  TemplateEngine        │
│ • template   │              │  IScoreboard (Nukkit)  │
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
IScoreboard.setLines(lines)
    ↓
玩家看到更新后的计分板
```

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

| 方法 | 说明 |
|------|------|
| `loadTemplate(name, xmlSource)` | 从 XML 源码编译并注册模板 |
| `loadTemplate(name)` | 从已注册的 TemplateLoader 加载模板文件 |
| `loadTemplate(name, plugin)` | 从指定插件 jar 加载模板（默认前缀 `templates/`） |
| `loadTemplate(name, plugin, prefix)` | 从指定插件 jar 的 `prefix` 目录加载模板 |
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回 [`ScoreboardPluginScope`](ScoreboardPluginScope.java)，绑定指定插件的 ClassLoader |
| `show(player, templateName)` | 给指定玩家显示计分板 |
| `showIf(predicate, templateName)` | 给满足条件的玩家显示，返回成功数量 |
| `showAll(templateName)` | 给所有在线玩家显示，返回成功数量 |
| `hide(player)` | 隐藏指定玩家的计分板 |
| `hideAll()` | 隐藏所有玩家的计分板 |
| `update(player, key, value)` | 更新单玩家数据（自动刷新） |
| `update(player, Map)` | 批量更新单玩家数据（一次刷新） |
| `updateAll(key, value)` | 更新所有玩家的同一数据项 |
| `updateAll(Map)` | 批量更新所有玩家的同一批数据 |
| `getDataContext(player)` | 获取玩家数据上下文（可直接操作） |
| `getView(player)` | 获取玩家计分板视图 |
| `onPlayerQuit(player)` | 玩家退出时清理（应在事件中调用） |

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

## 最佳实践

1. **模板注册一次**：在插件 `onEnable` 或玩家首次加入时注册模板，不要重复注册
2. **批量更新**：多个字段同时变化时用 `update(player, Map)` 或 `updateAll(Map)`，只触发一次渲染
3. **主线程操作**：Nukkit 计分板 API 应在主线程调用，异步线程更新数据时用 `Server.getScheduler().scheduleTask`
4. **退出清理**：在 `PlayerQuitEvent` 中调用 `onPlayerQuit(player)` 避免内存泄漏
5. **行数限制**：Nukkit 侧边栏最多 15 行，单行最长 30 字符（见 [`ScoreboardConstants`](ScoreboardConstants.java:14)）

## 依赖模块

| 模块 | 说明 |
|------|------|
| [`jframe_dependency_template`](../../jframe_dependency_template/pom.xml) | 模板引擎（XML 解析 + SpEL 渲染 + AST 缓存） |
| [`jframe_core`](../../jframe_core/pom.xml) | `DataContext` 响应式数据容器 |
