# jframe_dependency_template — 内容模板引擎

> 一个**与游戏内容无关**的通用模板引擎。用 XML / 纯文本编写模板，配合 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 数据即可渲染出标题 + 多行文本，供计分板、标题、浮空字等功能模块消费。

---

## 📑 目录

- [一、模块定位](#一模块定位)
- [二、包结构](#二包结构)
- [三、模板语法](#三模板语法)
- [四、API 使用](#四api-使用)
- [五、模板加载器](#五模板加载器)
- [六、缓存与热重载](#六缓存与热重载)
- [七、增量渲染](#七增量渲染)
- [八、快速示例](#八快速示例)
- [九、与传统方法对比](#九与传统方法对比)
- [十、异常处理](#十异常处理)

---

## 一、模块定位

游戏服务端有大量「把数据拼成文本」的需求：计分板、Boss 血条、浮空字、Action Bar……如果每个功能都手写字符串拼接，会出现：

- **逻辑与展示耦合**：业务代码里塞满 `§e金币: §f" + coins`，改颜色要改业务类。
- **无法热更新**：改一行显示文本就要重新编译、重启服务器。
- **重复造轮子**：每个功能各写一套条件判断、循环展开。

本模块用一套**统一的模板语法**解决上述问题：

1. **数据与展示分离**：模板（`.xml`）只描述「长什么样」，数据由 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 提供。
2. **SpEL 表达式**：`{{ }}` 内可写变量、属性、算术、三元、方法调用，由 Spring Expression Language 求值。
3. **条件 / 循环**：`<if>/<elif>/<else>`、`<each>`、`<line-each>` 满足动态展示需求。
4. **热重载**：文件系统模板改完即时生效，无需重启。
5. **增量渲染**：配合 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 的变更集，只重渲染受影响的行（详见 [DEVELOPER.md](DEVELOPER.md)）。

> **设计原则**：模板引擎层完全通用，不理解 scoreboard / title 等概念；各模块只负责把 [`RenderResult`](RenderResult.java) 映射到具体的游戏 API。

---

## 二、包结构

```
content_template/
├── TemplateEngine.java          # 门面：编译 + 加载 + 渲染 + 缓存 + 全局数据
├── HierarchicalDataContext.java # 分层数据上下文（全局 + 玩家数据合并）
├── Template.java                # 编译后的模板（不可变 AST）
├── TemplateConstants.java       # 保留字常量（标签名/属性名/定界符）
├── RenderResult.java            # 全量渲染结果（title + lines）
├── IncrementalRenderResult.java # 增量渲染结果（含变更信息）
├── TemplateNotFoundException.java
├── TemplateLoadException.java
├── ast/                         # 抽象语法树节点
│   ├── TemplateNode.java        #   行内节点基类（sealed）
│   ├── LineEntry.java           #   行级别条目基类（sealed）
│   ├── TextNode.java            #   纯文本
│   ├── ExpressionNode.java      #   {{ }} 表达式
│   ├── IfNode.java              #   <if>/<elif>/<else>
│   ├── EachNode.java            #   <each> 行内循环
│   ├── StaticLine.java          #   <line> 静态行
│   └── DynamicLine.java         #   <line-each> 动态行展开
├── parser/
│   ├── TemplateParser.java      # XML / 纯文本 → AST
│   └── TemplateParseException.java
├── loader/                      # 模板源码加载
│   ├── TemplateLoader.java      #   加载器接口
│   ├── ClasspathTemplateLoader.java  # 从 jar 内加载
│   ├── FileTemplateLoader.java       # 从磁盘加载（支持热重载）
│   └── CompositeTemplateLoader.java  # 组合多个 loader
└── render/                      # 渲染
    ├── TemplateRenderer.java    #   全量渲染器
    ├── RenderContext.java       #   SpEL 求值上下文
    ├── DependencyExtractor.java #   变量依赖提取（增量基础）
    └── IncrementalRenderer.java #   增量渲染器
```

---

## 三、模板语法

引擎支持两种模板形式：

| 形式 | 结构 | 适用场景 |
|------|------|----------|
| **XML 模板** | `<template>` 包裹，含 `<title>` + 多个 `<line>` | 计分板等需要 title + lines 的场景 |
| **纯文本模板** | 仅含 `{{ }}` 插值的字符串 | title、Action Bar 等单字符串场景 |

### 3.1 插值 `{{ }}`

`{{ }}` 内是 [SpEL 表达式](https://docs.spring.io/spring-framework/reference/core/expressions.html)，统一处理：

```xml
<line>玩家: {{player.name}}</line>          <!-- 嵌套属性 -->
<line>暴击率: {{critRate * 100}}%</line>      <!-- 算术 -->
<line>状态: {{hp > 50 ? '健康' : '危险'}}</line> <!-- 三元 -->
<line>职业: {{job.toUpperCase()}}</line>      <!-- 方法调用 -->
```

> `{{ }}` 之外的所有文本原样输出（包括 `§` 颜色代码）。

### 3.2 `<line>` 静态行

渲染为单行文本，支持行级条件：

```xml
<!-- 无条件：始终显示 -->
<line>§e玩家: §f{{player.name}}</line>

<!-- 条件行：if 为真才显示 -->
<line if="vip">§6⭐ VIP 会员</line>

<!-- 条件行带 else：为真/假显示不同文本 -->
<line if="vip" else="§7普通玩家">§6⭐ VIP 会员</line>
```

### 3.3 `<line-each>` 动态行展开

遍历列表，**每个元素生成一个独立行**（如排行榜每人一行）：

```xml
<line-each items="inventory" max="5">
    §f{{index + 1}}. {{this.name}} §7x{{this.count}}
</line-each>
```

| 属性 | 说明 |
|------|------|
| `items` | 数据列表的键名（SpEL 表达式） |
| `max` | 最大展开行数，省略表示无限制 |

循环体内可用两个上下文变量：

| 变量 | 含义 |
|------|------|
| `{{this}}` | 当前元素（`{{this.name}}` 取属性） |
| `{{index}}` | 当前序号（从 0 开始） |

### 3.4 `<if>` / `<elif>` / `<else>` 行内条件

写在 `<line>` 内部，按顺序检查条件，渲染首个为真者：

```xml
<line>
    <if cond="level >= 100">§c[大师]</if>
    <elif cond="level >= 50">§e[高手]</elif>
    <elif cond="level >= 10">§a[进阶]</elif>
    <else>§7[新手]</else>
</line>
```

### 3.5 `<each>` 行内循环

遍历列表，结果**拼接成一行**（如好友列表用空格分隔）：

```xml
<line>好友: <each items="friends">{{this}} </each></line>
```

> `<each>` 与 `<line-each>` 的区别：`<each>` 拼接成**一行**，`<line-each>` 每个元素生成**一个独立行**。

### 3.6 完整模板示例

```xml
<template>
    <title>§e§l⚔ 战斗信息</title>
    <line>§8═══════════════</line>
    <line>§c生命: {{hp}}/{{maxHp}}</line>
    <line>§a攻击: {{attack}}</line>
    <line>
        <if cond="buff != null">§d增益: {{buff}}</if>
        <else>§7无增益效果</else>
    </line>
    <line-each items="kills">§6#{{index + 1}} §f{{this.target}} §7×{{this.count}}</line-each>
</template>
```

---

## 四、API 使用

[`TemplateEngine`](TemplateEngine.java) 是一站式门面，整合解析、加载、渲染与缓存。

### 4.1 编译 + 渲染（直接传源码）

```java
TemplateEngine engine = new TemplateEngine();

Template template = engine.compile("""
    <template>
        <title>欢迎</title>
        <line>你好，{{name}}！</line>
    </template>
    """);

DataContext data = DataContext.of();
data.put("name", "Steve");

RenderResult result = engine.render(template, data);
result.getTitle();          // "欢迎"
result.getLines();          // ["你好，Steve！"]
```

### 4.2 按名称加载 + 渲染

```java
TemplateEngine engine = new TemplateEngine();
engine.registerLoader(new FileTemplateLoader(dataFolder, "templates/"));
engine.registerLoader(new ClasspathTemplateLoader("templates/scoreboard/"));

DataContext data = DataContext.of();
data.put("serverName", "我的服务器");

// 自动加载 + 编译 + 缓存 + 热重载检测
RenderResult result = engine.render("main", data);
```

### 4.3 纯文本模板

```java
Template template = engine.compileText("欢迎 {{name}} 来到 {{server}}！");

DataContext data = DataContext.of();
data.put("name", "Steve");
data.put("server", "生存服");

String text = engine.renderText(template, data);   // "欢迎 Steve 来到 生存服！"
```

### 4.4 API 一览

| 方法 | 说明 |
|------|------|
| [`compile(source)`](TemplateEngine.java:86) | 编译 XML 源码为 [`Template`](Template.java)（带缓存） |
| [`compileText(source)`](TemplateEngine.java:97) | 编译纯文本模板（带缓存） |
| [`getTemplate(name)`](TemplateEngine.java:110) | 按名称加载 + 编译（带缓存 + 热重载检测） |
| [`render(template, data)`](TemplateEngine.java:146) | 渲染已编译的 XML 模板 |
| [`render(name, data)`](TemplateEngine.java:157) | 按名称加载并渲染 XML 模板 |
| [`renderText(template, data)`](TemplateEngine.java:168) | 渲染已编译的纯文本模板 |
| [`renderText(name, data)`](TemplateEngine.java:179) | 按名称加载并渲染纯文本模板 |
| [`registerLoader(loader)`](TemplateEngine.java:74) | 注册模板加载器 |
| [`clearCache()`](TemplateEngine.java:186) | 清除所有缓存 |
| [`invalidate(name)`](TemplateEngine.java:193) | 清除指定名称的缓存 |
| [`getGlobalData()`](TemplateEngine.java:201) | 获取全局数据上下文（所有玩家共享） |
| [`setGlobal(key, value)`](TemplateEngine.java:209) | 写入全局数据（返回 this，支持链式） |
| [`setGlobalAll(map)`](TemplateEngine.java:218) | 批量写入全局数据 |
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回 [`TemplatePluginScope`](TemplatePluginScope.java)，绑定指定插件的 ClassLoader |

### 4.5 全局数据 + 玩家数据分层合并

实际场景中，有些数据是**所有玩家共享**的（如服务器名称、在线人数、全局公告），有些是**每个玩家独有**的（如金币、等级、VIP 状态）。

[`TemplateEngine`](TemplateEngine.java) 内置一个**全局数据上下文** [`globalData`](TemplateEngine.java:201)，配合 [`HierarchicalDataContext`](HierarchicalDataContext.java) 实现「全局 + 玩家」自动合并：

```java
TemplateEngine engine = new TemplateEngine();

// 写入全局数据（所有玩家共享）
engine.setGlobal("serverName", "我的服务器");
engine.setGlobal("online", 42);
engine.setGlobalAll(Map.of(
    "maxPlayers", 100,
    "motd", "欢迎来到生存服"
));

// 为每个玩家创建分层数据上下文
DataContext global = engine.getGlobalData();
HierarchicalDataContext playerData = HierarchicalDataContext.of(global);

// 玩家私有数据
playerData.put("coins", 1000);
playerData.put("level", 30);

// 渲染时自动合并：global + player（player 同名 key 优先）
RenderResult result = engine.render("main", playerData);
// 模板中 {{serverName}} → 全局值，{{coins}} → 玩家值
```

#### 合并规则

| 操作 | 行为 |
|------|------|
| **读取** `get(key)` | 先查玩家数据，未命中则查全局数据 |
| **写入** `put(key, val)` | 只写入玩家数据，**不污染全局** |
| **快照** `asMap()` / `snapshot()` | 返回全局 + 玩家的合并视图（玩家覆盖同名全局 key） |
| **变更通知** `onChange()` | 玩家数据变更 **和** 全局数据变更都会触发监听器 |

#### [`HierarchicalDataContext`](HierarchicalDataContext.java) API

| 方法 | 说明 |
|------|------|
| `of(parent)` | 创建分层上下文，`parent` 为全局 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java)（可为 null） |
| `get(key)` | 先查本地，未命中查 parent |
| `asMap()` | 合并 parent + 本地（本地覆盖同名 key） |
| `snapshot()` | 合并后返回不可变快照 |
| `onChange(listener)` | 本地变更 + parent 变更都触发 |
| `dispose()` | 清理 parent 监听器引用，**玩家退出时必须调用** |

> **内存管理**：[`HierarchicalDataContext`](HierarchicalDataContext.java) 在构造时向 parent 注册了变更监听器。玩家退出时必须调用 [`dispose()`](HierarchicalDataContext.java:142) 移除该引用，否则会导致内存泄漏。计分板模块（[`ScoreboardManager`](../../jframe_scoreboard_depend_template/src/main/java/io/github/JiangHu/jframe/scoreboard/ScoreboardManager.java)）已在 `hide()` 时自动调用。

---

## 五、模板加载器

模板源码通过 [`TemplateLoader`](loader/TemplateLoader.java) 接口加载，内置三种实现：

| 加载器 | 来源 | 热重载 | 典型用途 |
|--------|------|:------:|----------|
| [`FileTemplateLoader`](loader/FileTemplateLoader.java) | 磁盘文件 | ✅ | 服务器管理员自定义模板 |
| [`ClasspathTemplateLoader`](loader/ClasspathTemplateLoader.java) | jar 内 classpath | ❌ | 随插件发布的默认模板 |
| [`CompositeTemplateLoader`](loader/CompositeTemplateLoader.java) | 组合多个 loader | 取命中者 | 文件优先、classpath 兜底 |

### 组合加载（推荐）

```java
CompositeTemplateLoader loader = new CompositeTemplateLoader(
    new FileTemplateLoader(dataFolder, "templates/"),      // 优先：用户自定义
    new ClasspathTemplateLoader("templates/scoreboard/")   // 兜底：内置默认
);
```

按注册顺序依次尝试，返回首个找到的模板。`FileTemplateLoader` 还内置了**路径穿越防护**，确保解析后的文件不会逃出基准目录。

### 跨插件加载（加载其他插件 jar 内的模板）

Nukkit 中每个插件有独立的类加载器，默认的 [`ClasspathTemplateLoader`](loader/ClasspathTemplateLoader.java) **只能加载本插件 jar**内的资源。若要加载**其他插件 jar**内的模板，需传入目标插件的类加载器（由 [`jframe_core`](../../../../../../../../core/classloader/PluginClassLoaderFactory.java) 提供）：

```java
import io.github.JiangHu.jframe.core.classloader.PluginClassLoaderFactory;
import io.github.JiangHu.jframe.core.classloader.CompositeClassLoader;

// 方式一：加载单个指定插件的模板
ClassLoader addonLoader = PluginClassLoaderFactory.getClassLoader("MyAddon");
engine.registerLoader(new ClasspathTemplateLoader("templates/", addonLoader));

// 方式二：聚合多个插件，从任一插件 jar 加载（按顺序查找）
CompositeClassLoader multi = PluginClassLoaderFactory.compositeOf("JFrame", "MyAddon");
engine.registerLoader(new ClasspathTemplateLoader("templates/", ".xml", multi));

// 方式三：聚合所有已加载插件（兜底）
CompositeClassLoader all = PluginClassLoaderFactory.compositeOfAllPlugins();
engine.registerLoader(new ClasspathTemplateLoader("templates/", all));
```

> **注意**：`PluginClassLoaderFactory` 依赖 `Server.getInstance()`，只能在服务端启动后（如 `onEnable`）调用。

### forPlugin 作用域代理

除了手动注册 loader，还可以用 [`forPlugin`](../../../../../../../../core/module/ForPlugin.java) 获取作用域代理，直接从指定插件的 jar 加载模板：

```java
// 从当前插件 jar 加载模板
Template tpl = engine.forPlugin(this).getTemplate("main");

// 从当前插件 jar 加载并渲染
RenderResult result = engine.forPlugin(this).render("main", data);

// 指定自定义前缀和后缀
Template tpl = engine.forPlugin(this)
    .withPrefix("views/").withSuffix(".html")
    .getTemplate("home");

// 从其他插件加载（按插件名）
Template tpl = engine.forPlugin("OtherPlugin").getTemplate("custom");
```

[`TemplatePluginScope`](TemplatePluginScope.java) 暴露 `getTemplate`、`render`、`renderText`、`loadRaw`、`withPrefix`、`withSuffix` 方法，内部每次用绑定的 ClassLoader 构造临时的 [`ClasspathTemplateLoader`](loader/ClasspathTemplateLoader.java)，编译结果复用引擎的全局缓存。

> 详见 [core/README — ForPlugin](../../../../../../../../core/README.md#四forplugin跨插件作用域代理)。

---

## 六、缓存与热重载

[`TemplateEngine`](TemplateEngine.java) 维护两套缓存：

| 缓存 | Key | 用途 |
|------|-----|------|
| **按名称缓存** | 模板名称 | 配合 `lastModified` 检测文件变更，实现热重载 |
| **按源码缓存** | 源码 SHA-256 | 避免重复编译相同源码 |

- **热重载**：每次 [`getTemplate(name)`](TemplateEngine.java:110) 都会比较文件的 `lastModified`，文件被修改后自动重新编译。
- **classpath 模板**：`lastModified` 恒为 0，首次编译后一直命中缓存（不支持热重载）。
- **手动失效**：[`invalidate(name)`](TemplateEngine.java:193) 清除单个模板，[`clearCache()`](TemplateEngine.java:186) 清除全部。

---

## 七、增量渲染

对于计分板这类**高频刷新**的场景，全量渲染（每次重新求值所有表达式）开销较大。本模块提供增量渲染能力：

- [`DependencyExtractor`](render/DependencyExtractor.java)：编译期分析模板，提取每个行条目依赖的变量集合。
- [`IncrementalRenderer`](render/IncrementalRenderer.java)：运行时根据 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 的变更集，**只重渲染受影响的行**，未受影响的行直接复用缓存。

```java
IncrementalRenderer renderer = new IncrementalRenderer();

// 首次：全量渲染
IncrementalRenderResult first = renderer.renderFull(template, data);

// 后续：增量渲染（只重渲染依赖了变更变量的行）
IncrementalRenderResult delta = renderer.renderIncremental(data, changeSet);

if (!delta.hasChanges()) {
    return;   // 无任何变更，跳过发包
}
if (delta.isStructureChanged()) {
    sendFull(delta.getAllLines());          // 行数变化，全量重建
} else {
    sendChangedLines(delta.getChangedLineIndices(), delta.getAllLines()); // 只发变化的行
}
```

> 实现原理与算法细节见 [DEVELOPER.md](DEVELOPER.md)。

---

## 八、快速示例

### 计分板模板

```xml
<template>
    <title>§e{{serverName}} - 联机大厅</title>
    <line>§7玩家: §f{{player.name}}</line>
    <line>§7金币: §6{{coins}}</line>
    <line if="vip" else="§7身份: 普通玩家">§6身份: ⭐VIP</line>
    <line-each items="rankings" max="10">§e#{{index + 1}} §f{{this.name}}</line-each>
</template>
```

```java
DataContext data = DataContext.of();
data.put("serverName", "我的服务器");
data.put("player", Map.of("name", "Steve"));
data.put("coins", 1000);
data.put("vip", true);
data.put("rankings", List.of(
    Map.of("name", "Alex"),
    Map.of("name", "Herobrine")
));

RenderResult result = engine.render("main", data);
result.getTitle();   // "§e我的服务器 - 联机大厅"
result.getLines();   // ["§7玩家: §fSteve", "§7金币: §61000", "§6身份: ⭐VIP", "§e#1 §fAlex", "§e#2 §fHerobrine"]
```

### 响应式更新

模板编译一次即可反复渲染，**数据变化只需更新 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 再渲染**：

```java
RenderResult before = engine.render(template, data);   // 金币: 1000

data.put("coins", 5000);
RenderResult after = engine.render(template, data);    // 金币: 5000
```

---

## 九、与传统方法对比

| 维度 | 手写字符串拼接 | 本模板引擎 |
|------|------|------|
| **展示与逻辑** | 耦合（业务类里写 `§e` 颜色码） | 分离（模板文件 + 数据） |
| **热更新** | 改文本要重新编译重启 | 文件模板改完即时生效 |
| **条件 / 循环** | 手写 `if` / `for` 拼字符串 | `<if>` / `<line-each>` 声明式 |
| **表达式求值** | 手写取值、算术 | SpEL 一行搞定 |
| **高频刷新性能** | 每次全量重算 | 增量渲染只算变化的行 |
| **跨功能复用** | 每个功能各写一套 | 统一 [`RenderResult`](RenderResult.java)，各模块映射 |

---

## 十、异常处理

所有异常均为 `RuntimeException`，无需在方法签名上声明：

| 异常 | 触发场景 |
|------|----------|
| [`TemplateParseException`](parser/TemplateParseException.java) | XML 语法错误、根标签不是 `<template>` |
| [`TemplateNotFoundException`](TemplateNotFoundException.java) | 所有 loader 都找不到指定名称的模板 |
| [`TemplateLoadException`](TemplateLoadException.java) | 加载模板时发生 IO 错误 |

```java
try {
    RenderResult result = engine.render("main", data);
} catch (TemplateNotFoundException e) {
    // 模板不存在，回退到默认显示
} catch (TemplateLoadException e) {
    // IO 错误，记录日志
}
```

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
