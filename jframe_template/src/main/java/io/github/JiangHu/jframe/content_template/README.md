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
3. **条件 / 循环**：块级 `<if>/<elif>/<else>` 与 `<for>` 控制行组显隐与展开，行内 `<if>/<for>` 拼接单行内容，循环变量名可自定义。
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
│   ├── IfNode.java              #   行内 <if>/<elif>/<else>
│   ├── ForNode.java             #   行内 <for> 循环拼接
│   ├── StaticLine.java          #   <line> 单行
│   ├── ConditionalBlock.java    #   块级 <if>/<elif>/<else>（行组显隐）
│   └── LoopBlock.java           #   块级 <for>（每元素展开一组行）
├── parser/
│   ├── TemplateParser.java      # XML / 纯文本 → AST
│   └── TemplateParseException.java
├── loader/                      # 模板源码加载
│   ├── TemplateLoader.java      #   加载器接口
│   ├── ClasspathTemplateLoader.java  # 从 jar 内加载
│   ├── FileTemplateLoader.java       # 从磁盘加载（支持热重载）
│   └── CompositeTemplateLoader.java  # 组合多个 loader
├── render/                      # 渲染
│   ├── TemplateRenderer.java    #   全量渲染器
│   ├── RenderContext.java       #   SpEL 求值上下文
│   ├── DependencyExtractor.java #   变量依赖提取（增量基础）
│   └── IncrementalRenderer.java #   增量渲染器
└── column/                      # 列格式（渲染后对齐阶段）
    ├── ColumnLayout.java        #   列布局配置（分隔符 + 逐列策略，不可变）
    ├── ColumnSpec.java          #   单列策略（对齐方向 + 宽度策略）
    ├── ColumnAlign.java         #   对齐方向枚举（LEFT / CENTER / RIGHT）
    ├── ColumnWidth.java         #   宽度策略（AUTO / FIXED(n)）
    ├── TextWidth.java           #   显示宽度计算（CJK 2 宽、颜色码 0 宽）
    └── ColumnAligner.java       #   列化算法（拆列 → 算宽 → 对齐/截断 → 重组）
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

### 3.2 `<line>` 行与裸文本行

`<line>` 渲染为单行文本，行内可混排文本、`{{ }}` 表达式、行内 `<if>`、行内 `<for>`：

```xml
<line>§e玩家: §f{{player.name}}</line>
<line>暴击率: {{critRate * 100}}%</line>
```

`<template>` 行区域中的**非空白裸文本**（未被任何标签包裹）也按一行渲染，等价于 `<line>`：

```xml
<template>
    <title>§e标题</title>
    §7这是一行裸文本
    <line>§f这是 line 标签行</line>
</template>
```

### 3.3 块级 `<if>` / `<elif>` / `<else>` —— 条件控制行组

出现在 `<template>` 直接子级（或块内）的 `<if>` 是**块级条件**：条件为真的分支内**所有行整组渲染**，否则整组隐藏。分支内可放任意行条目（`<line>`、裸文本、嵌套块级 `<if>/<for>`），可任意嵌套：

```xml
<!-- if 包裹多行：vip 为真时两行都显示，为假时一行都不显示 -->
<if cond="vip">
    <line>§6⭐ VIP 会员</line>
    <line>§7到期: {{vipExpire}}</line>
</if>

<!-- if / elif / else 链：首个为真的分支渲染 -->
<if cond="level >= 100">
    <line>§c大师段位</line>
</if>
<elif cond="level >= 50">
    <line>§e高手段位</line>
</elif>
<else>
    <line>§7新手段位</line>
</else>
```

| 属性 | 说明 |
|------|------|
| `cond` | SpEL 条件表达式（`<if>`/`<elif>` 必填，`<else>` 无属性） |

### 3.4 块级 `<for>` —— 循环展开行组

块级 `<for>` 遍历列表，**每个元素展开循环体内所有行**（如排行榜每人多行）：

```xml
<!-- 每个元素两行：名字行 + 分数行 -->
<for items="rankings" var="p" index="i" max="10">
    <line>§e#{{i + 1}} §f{{p.name}}</line>
    <line>§7分数: {{p.score}}</line>
</for>
```

| 属性 | 说明 |
|------|------|
| `items` | 数据列表的键名（DataContext 中的 List/数组，必填） |
| `var` | 元素变量名（缺省 `this`，嵌套 for 用不同名字互访） |
| `index` | 序号变量名（缺省 `index`，从 0 开始） |
| `max` | 最大迭代元素数（省略或 ≤0 表示不限，受限行数环境如 sidebar 有用） |

**嵌套作用域**：内层变量遮蔽同名外层变量，异名变量内外层互相可见：

```xml
<for items="teams" var="t">
    <for items="t.members" var="m">
        <line>§f{{t.name}} - {{m}}</line>   <!-- 内层访问外层 t -->
    </for>
</for>
```

### 3.5 行内 `<if>` 与行内 `<for>` —— 拼接单行

写在 `<line>`（或 `<title>`）**内部**的 `<if>`/`<for>` 是行内节点，只影响一行内的文本片段：

```xml
<!-- 行内 if 链：按顺序检查，渲染首个为真者 -->
<line>
    <if cond="level >= 100">§c[大师]</if>
    <elif cond="level >= 50">§e[高手]</elif>
    <else>§7[新手]</else>
</line>

<!-- 行内 for：遍历拼接成一行（好友列表空格分隔） -->
<line>好友: <for items="friends" var="f">{{f}} </for></line>
```

> **块级 vs 行内**：同一个标签按**出现位置**区分语义——行区域（`<template>` 直接子级或块内）= 块级（控制行组显隐/展开）；`<line>`/`<title>` 内 = 行内（拼接一行文本）。行内 `<for>` 属性与块级相同（`items`/`var`/`index`/`max`）。

### 3.6 完整模板示例

```xml
<template>
    <title>§e§l⚔ 战斗信息</title>
    <line>§8═══════════════</line>
    <line>§c生命: {{hp}}/{{maxHp}}</line>
    <line>§a攻击: {{attack}}</line>

    <!-- 块级 if：增益信息整组显隐 -->
    <if cond="buff != null">
        <line>§d增益: {{buff.name}}</line>
        <line>§7剩余: {{buff.seconds}}s</line>
    </if>
    <else>
        §7无增益效果
    </else>

    <!-- 块级 for：每个击杀目标一行 -->
    <for items="kills" var="k" index="i">
        <line>§6#{{i + 1}} §f{{k.target}} §7×{{k.count}}</line>
    </for>
</template>
```

### 3.7 列格式 `<template columns>` / `<columns>`

排行榜、经济面板这类**多列文本**在等宽字体下想对齐，手补空格在含中文（显示宽度 2 格）时几乎无法对齐。列格式让引擎在**渲染完成后自动列化对齐**：行内容按分隔符拆列 → 计算各列显示宽度 → 按对齐方向补空格 → 重组。

两种启用方式（可单独使用，也可组合）：

```xml
<!-- 方式一：根属性 columns 指定分隔符，全列默认 左对齐 + AUTO 宽 -->
<template columns="|">
    <line>金币|{{coins}}</line>
    <line>等级|{{level}}</line>
</template>

<!-- 方式二：<columns> 配置块（隐式启用，默认分隔符 |），逐列声明策略 -->
<template>
    <columns>
        <column align="left"/>
        <column align="right" width="10"/>
    </columns>
    <line>金币|{{coins}}</line>
    <line>等级|{{level}}</line>
</template>

<!-- 组合：根属性定分隔符（可多字符）+ <columns> 定列策略 -->
<template columns="§7|§f">
    <columns>
        <column/>
        <column align="right" width="12"/>
    </columns>
    <line>玩家 {{player.name}} §7|§f {{coins}}</line>
</template>
```

#### 规则要点

| 规则 | 说明 |
|------|------|
| 分隔符 | 可为多字符；根属性值为空串 → 编译报错 |
| `<columns>` 块 | 最多一个（重复 → 编译报错）；分隔符优先取根属性值，根属性未启用时用默认 `\|` |
| `<column>` 属性 | `align`（`left` / `center` / `right`，默认 `left`）、`width`（`auto` 或正整数，默认 `auto`）；非法值 → 编译报错 |
| 列数 | 声明了 `<column>` 取声明数，否则取各行最大单元格数；某行单元格缺失补空串、多余并入最后一列 |
| 转义 | `\ + 分隔符` 表示字面分隔符（不拆列） |
| 空行 | 空串行不参与列宽统计，原样直通（可做面板分隔行） |
| 重组 | 对齐后的单元格以 `" 分隔符 "`（两侧各一空格）重新拼接 |
| 标题 | `<title>` **不参与列化** |

#### 显示宽度（[`TextWidth`](column/TextWidth.java)）

- 颜色/格式代码 `§x`、`&x` 计 **0 宽**（含码文本与纯文本同宽对齐）
- CJK 字符（中文、日文假名、韩文、中文标点、全角形式）计 **2 宽**；其他计 1 宽
- **AUTO** 列宽 = 该列全部单元格最大显示宽度；**FIXED(n)** 固定 n，超宽截断加 `...`（省略号宽度计入 n，并延续最后一个颜色代码）
- 对齐补空格：LEFT 右补 / RIGHT 左补 / CENTER 居中（左 `⌊pad/2⌋`、右 `⌈pad/2⌉`）

#### API（[`Template`](Template.java) 上）

| 方法 | 说明 |
|------|------|
| [`getColumnLayout()`](Template.java:116) | 列布局配置（未启用时为 [`ColumnLayout.DISABLED`](column/ColumnLayout.java:21)） |
| [`isColumnLayoutEnabled()`](Template.java:121) | 是否启用列格式 |
| [`ColumnLayout.isEnabled() / getSeparator() / getColumns()`](column/ColumnLayout.java) | 启用判断 / 分隔符 / 逐列策略列表 |

#### 增量渲染语义

启用列格式时 [`IncrementalRenderer`](render/IncrementalRenderer.java) 每次增量渲染**全量重算列宽**，与上次列宽比较：

- **列宽变化**（如某格变长）→ 对齐整体移位 → 变更集自动扩为**全集**（所有行视为变更，避免漏更新）
- **列宽不变** → 仅内容变化的行标记变更，其余行复用缓存

#### 向后兼容

不写 `columns` 属性与 `<columns>` 块 → [`ColumnLayout.DISABLED`](column/ColumnLayout.java:21)，渲染行为与扩展前**完全一致**；旧构造器 `new Template(titleNodes, lineEntries, source)` 等价于禁用列格式 + 空元数据。

### 3.8 元数据标签 `<meta>`

在模板内声明键值对，编译期收集为**不可变 Map**，引擎不校验其语义，仅供业务模块读取（如 [jframe_title](../../../../../../../../jframe_title/src/main/java/io/github/JiangHu/jframe/title/README.md) 定义了 9 个 `title.*` 标签控制通道映射、时序与模式）。

```xml
<template>
    <meta key="module" value="title"/>
    <meta key="title.mode" value="transient"/>
    <meta key="title.timing.stay" value="60"/>
    <title>§e公告</title>
    <line>欢迎 {{player.name}}</line>
</template>
```

#### 规则要点

| 规则 | 说明 |
|------|------|
| `key` | 必填非空（缺失/为空 → 编译报错） |
| `value` | 缺省为空串 |
| 数量 | 可写多个；重复 `key` 后者覆盖（不报错） |
| 位置 | `<template>` 下与 `<title>` / `<line>` 平级，顺序不限 |

#### API（[`Template`](Template.java) 上）

| 方法 | 说明 |
|------|------|
| [`getMetadata()`](Template.java:126) | 全部元数据（不可变 Map，无 `<meta>` 时为空 Map） |
| [`getMetadata(key)`](Template.java:131) | 按 key 读取，不存在返回 `Optional.empty()` |

#### 向后兼容

无 `<meta>` 标签时 `getMetadata()` 返回空 Map，渲染产物与扩展前完全一致；引擎对元数据只收集、不消费，不影响渲染结果。

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
| [`getGlobalData()`](TemplateEngine.java:201) | 获取引擎全局数据上下文（所有模板、所有玩家共享） |
| [`setGlobal(key, value)`](TemplateEngine.java:209) | 写入引擎全局数据（返回 this，支持链式） |
| [`setGlobalAll(map)`](TemplateEngine.java:218) | 批量写入引擎全局数据 |
| [`getTemplateData(name)`](TemplateEngine.java) | 获取模板全局数据上下文（同模板所有玩家共享，自动缓存） |
| [`setTemplateData(name, key, value)`](TemplateEngine.java) | 写入模板全局数据（返回 this） |
| [`setTemplateDataAll(name, map)`](TemplateEngine.java) | 批量写入模板全局数据 |
| [`removeTemplateData(name)`](TemplateEngine.java) | 移除模板全局数据并释放监听器（返回被移除的 DataContext） |
| `forPlugin(Plugin)` / `forPlugin(String)` | 返回 [`TemplatePluginScope`](TemplatePluginScope.java)，绑定指定插件的 ClassLoader |

### 4.5 三层数据粒度

实际场景中，数据有不同的**共享范围**：

| 层级 | 共享范围 | 典型数据 | API |
|------|----------|----------|-----|
| **引擎全局** | 所有模板、所有玩家 | 服务器名称、在线人数、MOTD | `setGlobal` / `getGlobalData` |
| **模板全局** | 同一模板的所有玩家 | 公会分数、队伍信息、副本进度 | `setTemplateData` / `getTemplateData` |
| **玩家本地** | 仅该玩家 | 金币、等级、VIP 状态 | `HierarchicalDataContext.put` |

[`TemplateEngine`](TemplateEngine.java) 内置**引擎全局**和**模板全局**两层管理，配合 [`HierarchicalDataContext`](HierarchicalDataContext.java) 的多级父链实现三层自动合并：

```java
TemplateEngine engine = new TemplateEngine();

// 第一层：引擎全局（所有模板、所有玩家共享）
engine.setGlobal("serverName", "我的服务器");
engine.setGlobal("online", 42);

// 第二层：模板全局（同一模板的所有玩家共享，按模板名隔离）
engine.setTemplateData("main", "guildScore", 5000);
engine.setTemplateDataAll("admin", Map.of("perm", "root", "level", 99));

// 第三层：玩家本地（每玩家独立）
DataContext templateGlobal = engine.getTemplateData("main");
HierarchicalDataContext playerData = HierarchicalDataContext.of(templateGlobal);
playerData.put("coins", 1000);
playerData.put("level", 30);

// 渲染时自动合并三层：引擎全局 → 模板全局 → 玩家本地（后者覆盖同名 key）
RenderResult result = engine.render("main", playerData);
// {{serverName}} → 引擎全局值，{{guildScore}} → 模板全局值，{{coins}} → 玩家值
```

#### 三层架构图

```
引擎全局（engine.getGlobalData）    ← 所有模板、所有玩家共享
    ↑ parent
模板全局（engine.getTemplateData）  ← 同一模板的所有玩家共享
    ↑ parent
玩家本地（HierarchicalDataContext）  ← 仅该玩家可见
```

#### 合并规则

| 操作 | 行为 |
|------|------|
| **读取** `get(key)` | 按优先级查找：玩家本地 → 模板全局 → 引擎全局 |
| **写入** `put(key, val)` | 只写入当前层，**不污染上层** |
| **快照** `asMap()` / `snapshot()` | 返回三层合并视图（下层覆盖同名 key） |
| **变更通知** `onChange()` | 本层变更 **和** 所有上层变更都会触发监听器 |

#### 变更传播方向

- **上层变更 → 向下传播**：引擎全局变更会传播到所有模板全局和所有玩家本地
- **下层变更 → 不向上传播**：玩家本地写入不会影响模板全局或引擎全局

#### [`HierarchicalDataContext`](HierarchicalDataContext.java) API

| 方法 | 说明 |
|------|------|
| `of(parent)` | 创建分层上下文，`parent` 可为任意 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java)（支持多级嵌套） |
| `get(key)` | 先查本地，未命中查 parent（递归向上） |
| `asMap()` | 合并 parent + 本地（本地覆盖同名 key） |
| `snapshot()` | 合并后返回不可变快照 |
| `onChange(listener)` | 本地变更 + parent 变更都触发 |
| `dispose()` | 清理 parent 监听器引用，**资源释放时必须调用** |

> **内存管理**：[`HierarchicalDataContext`](HierarchicalDataContext.java) 在构造时向 parent 注册了变更监听器。释放资源时必须调用 [`dispose()`](HierarchicalDataContext.java:142) 移除该引用，否则会导致内存泄漏。
>
> - **玩家退出**：调用玩家本地的 `dispose()`，移除对模板全局的监听器
> - **模板注销**：调用 [`engine.removeTemplateData(name)`](TemplateEngine.java)，内部自动调用模板全局的 `dispose()`，移除对引擎全局的监听器
>
> 计分板模块（[`ScoreboardManager`](../../jframe_scoreboard_depend_template/src/main/java/io/github/JiangHu/jframe/scoreboard/ScoreboardManager.java)）已在 `hide()` / `removeTemplate()` 时自动调用。

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
    <if cond="vip">
        §6身份: ⭐VIP
    </if>
    <else>
        §7身份: 普通玩家
    </else>
    <for items="rankings" var="r" index="i" max="10">
        <line>§e#{{i + 1}} §f{{r.name}}</line>
    </for>
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
| **条件 / 循环** | 手写 `if` / `for` 拼字符串 | `<if>` / `<for>` 声明式 |
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
