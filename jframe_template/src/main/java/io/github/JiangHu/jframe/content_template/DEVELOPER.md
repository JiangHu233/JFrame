# jframe_dependency_template — 维护者文档

> 面向模块维护者。记录模板引擎的实现原理：编译流水线、AST 设计、SpEL 求值、增量渲染的两层架构、缓存与线程安全模型。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、模块职责与依赖](#一模块职责与依赖)
- [二、编译流水线](#二编译流水线)
- [三、AST 设计](#三ast-设计)
- [四、SpEL 求值与 MapPropertyAccessor](#四spel-求值与-mappropertyaccessor)
- [五、增量渲染：两层架构](#五增量渲染两层架构)
- [六、缓存策略](#六缓存策略)
- [七、Loader 链与热重载](#七loader-链与热重载)
- [八、线程安全](#八线程安全)
- [九、扩展指南](#九扩展指南)

---

## 一、模块职责与依赖

`jframe_dependency_template` 是纯工具层，**只依赖 [`jframe_core`](../../../../../../../../core/README.md)**（提供 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 与 [`ChangeSet`](../../../../../../../../core/data/reactive/ChangeSet.java)）和 Spring（SpEL 求值、`ClassPathResource`）。它不理解任何游戏概念，产出的是与游戏无关的 [`RenderResult`](RenderResult.java) / [`IncrementalRenderResult`](IncrementalRenderResult.java)，由 scoreboard / title 等上层模块消费映射。

四个子包职责单一、互不交叉：

| 子包 | 职责 |
|------|------|
| [`ast/`](ast/TemplateNode.java) | 纯数据节点（record），描述模板结构 |
| [`parser/`](parser/TemplateParser.java) | 源码 → AST（编译） |
| [`loader/`](loader/TemplateLoader.java) | 从不同来源加载源码 |
| [`render/`](render/TemplateRenderer.java) | AST + 数据 → 文本（渲染） |

[`TemplateEngine`](TemplateEngine.java) 是门面，把上述四者与缓存机制整合成一站式 API。

---

## 二、编译流水线

模板从源码到渲染结果经过**编译期**与**渲染期**两个阶段，二者严格分离：

```
编译期（一次性，可缓存）                  渲染期（每次 render 调用）
┌─────────────────────┐                ┌──────────────────────┐
│  XML / 纯文本源码    │                │  DataContext 数据快照 │
│         │           │                │         │            │
│   TemplateParser    │                │   TemplateRenderer   │
│   (DOM 解析 + 拆分)  │                │   (遍历 AST + SpEL)  │
│         │           │                │         │            │
│   Template (AST)    │ ────复用────→  │   RenderResult       │
│   (不可变)          │                │   (title + lines)    │
└─────────────────────┘                └──────────────────────┘
```

### 编译期：[`TemplateParser`](parser/TemplateParser.java)

1. **XML 模板**（[`parseXml`](parser/TemplateParser.java:80)）：用 JDK DOM 解析字符串 → `Document`，遍历 `<template>` 子元素：
   - `<title>` → [`parseInlineNodes`](parser/TemplateParser.java:153) 解析为行内节点列表
   - `<line>` → [`parseStaticLine`](parser/TemplateParser.java:129)（提取 `if`/`else` 属性）
   - `<line-each>` → [`parseDynamicLine`](parser/TemplateParser.java:139)（提取 `items`/`max`）
2. **纯文本模板**（[`parseText`](parser/TemplateParser.java:120)）：直接 [`parseInlineText`](parser/TemplateParser.java:244) 拆分 `{{ }}`。

### 行内节点解析

[`parseInlineNodes`](parser/TemplateParser.java:153) 递归处理 DOM 子节点，是编译期的核心：

- **文本节点** → [`parseInlineText`](parser/TemplateParser.java:244) 按 `{{ }}` 拆分为 [`TextNode`](ast/TextNode.java) / [`ExpressionNode`](ast/ExpressionNode.java)
- **`<if>`** → [`parseIfChain`](parser/TemplateParser.java:188) 消费后续连续的 `<elif>`/`<else>`，合并为单个 [`IfNode`](ast/IfNode.java)
- **`<each>`** → [`parseEachElement`](parser/TemplateParser.java:230) 生成 [`EachNode`](ast/EachNode.java)

**关键设计——if 链合并**：DOM 中 `<if>`/`<elif>`/`<else>` 是平级兄弟节点，[`parseIfChain`](parser/TemplateParser.java:188) 把它们「贪心」地合并成一个 [`IfNode`](ast/IfNode.java)，返回下一个待处理索引，避免渲染时重复扫描。孤立的 `<elif>`/`<else>`（无前置 `<if>`）会被跳过。

### 渲染期：[`TemplateRenderer`](render/TemplateRenderer.java)

1. 从 [`DataContext.asMap()`](../../../../../../../../core/data/reactive/DataContext.java) 取**数据快照**，构建 [`RenderContext`](render/RenderContext.java)。
2. 渲染标题节点列表 → 标题字符串。
3. 遍历行条目（[`StaticLine`](ast/StaticLine.java) / [`DynamicLine`](ast/DynamicLine.java)）→ 行列表。
4. 封装为 [`RenderResult`](RenderResult.java)。

行内节点渲染（[`renderNode`](render/TemplateRenderer.java:151)）用 `switch` 模式匹配处理四种节点类型，节点本身是纯数据，渲染逻辑集中在渲染器。

> **为什么编译期与渲染期分离**：编译结果（AST）不可变、可缓存、可并发渲染。同一模板编译一次，配合不同数据可渲染无数次，是缓存与增量渲染的基础。

---

## 三、AST 设计

AST 用 Java 的 **sealed 接口 + record** 表达，编译期即可保证穷尽性：

```
行级别（LineEntry，sealed）            行内（TemplateNode，sealed）
┌──────────────────────┐             ┌──────────────────────┐
│  StaticLine          │  nodes →    │  TextNode            │  纯文本
│  (<line>)            │ ──────────→ │  ExpressionNode      │  {{ }}
│                      │             │  IfNode              │  <if>/<elif>/<else>
│  DynamicLine         │  bodyNodes →│  EachNode            │  <each>
│  (<line-each>)       │ ──────────→ └──────────────────────┘
└──────────────────────┘
```

### 两个维度

| 维度 | 接口 | 实现 | 含义 |
|------|------|------|------|
| **行级别** | [`LineEntry`](ast/LineEntry.java) | [`StaticLine`](ast/StaticLine.java) / [`DynamicLine`](ast/DynamicLine.java) | 决定「产生几行」 |
| **行内** | [`TemplateNode`](ast/TemplateNode.java) | [`TextNode`](ast/TextNode.java) / [`ExpressionNode`](ast/ExpressionNode.java) / [`IfNode`](ast/IfNode.java) / [`EachNode`](ast/EachNode.java) | 决定「一行内拼什么」 |

### 关键区分：`<each>` vs `<line-each>`

二者容易混淆，是设计上的有意区分：

| 标签 | AST 类型 | 产出 |
|------|----------|------|
| `<each>` | [`EachNode`](ast/EachNode.java)（行内节点） | 拼接成**一行**（如好友列表用空格分隔） |
| `<line-each>` | [`DynamicLine`](ast/DynamicLine.java)（行级别条目） | 每个元素生成**一个独立行**（如排行榜每人一行） |

### 保留字集中管理

所有标签名、属性名、定界符、循环上下文变量都集中在 [`TemplateConstants`](TemplateConstants.java)，DOM 解析器、AST 构建器、渲染器全部引用常量而非硬编码字符串，便于统一维护与扩展。

---

## 四、SpEL 求值与 MapPropertyAccessor

[`RenderContext`](render/RenderContext.java) 封装 SpEL 表达式求值，是渲染期的核心。它以 [`DataContext`](../../../../../../../../core/data/reactive/DataContext.java) 的数据快照（`Map<String, Object>`）为 root object。

### MapPropertyAccessor：让 SpEL 支持 Map 取值

这是整个引擎最关键的设计点。SpEL 默认的 `ReflectivePropertyAccessor` 遇到 Map root object 时，会尝试通过 getter/字段反射访问，而 Map 没有 `getName()` 方法——导致 `{{player.name}}` 取不到值。

[`RenderContext.MapPropertyAccessor`](render/RenderContext.java:163) 解决了这个问题：

```java
private static class MapPropertyAccessor implements PropertyAccessor {
    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] { Map.class };   // 只对 Map 生效
    }
    @Override
    public boolean canRead(...) {
        return ((Map<?, ?>) target).containsKey(name);
    }
    @Override
    public TypedValue read(...) {
        return new TypedValue(((Map<?, ?>) target).get(name));  // map.get(key)
    }
}
```

注册时**插入到 accessor 列表最前面**（`getPropertyAccessors().add(0, MAP_ACCESSOR)`），优先于默认的 `ReflectivePropertyAccessor`。对嵌套路径同样生效：`player.name` → 先 `map.get("player")` 得到内层 Map，再 `innerMap.get("name")`。

> **为什么用单例**：`MapPropertyAccessor` 无状态，作为 `static final` 单例注册到所有 `StandardEvaluationContext`，避免每次求值重复创建。

### 类型转换辅助方法

[`RenderContext`](render/RenderContext.java) 提供三种求值出口，处理 SpEL 结果的类型多样性：

| 方法 | 行为 |
|------|------|
| [`evaluate`](render/RenderContext.java:81) | 原始求值，异常返回 `null` |
| [`evaluateString`](render/RenderContext.java:96) | `toString()`，`null` 返回空串 |
| [`evaluateBoolean`](render/RenderContext.java:108) | Boolean 直用；Number 非 0 为真；String 非空且非 `"false"` 为真；其余非 null 为真 |
| [`evaluateIterable`](render/RenderContext.java:128) | 非 Iterable 返回空列表 |

**求值容错**：所有求值方法都 catch 异常返回安全默认值（`null` / 空串 / 空），保证单个表达式出错不会让整个模板渲染崩溃——这对游戏服务端的健壮性很重要。

---

## 五、增量渲染：两层架构

全量渲染（[`TemplateRenderer`](render/TemplateRenderer.java)）每次遍历整个 AST 并对所有表达式求值，对计分板这类高频刷新场景开销较大。增量渲染通过**变量依赖图**避免无谓的重渲染，分两层：

```
Layer 1（编译期，一次性）              Layer 2（运行时，每次变更）
┌──────────────────────────┐         ┌────────────────────────────┐
│  DependencyExtractor     │         │  IncrementalRenderer       │
│  分析 AST → 依赖图        │ ──提供→ │  依据 ChangeSet 只重渲染    │
│  行条目 → {变量集合}      │  依赖图  │  受影响的行，其余复用缓存   │
└──────────────────────────┘         └────────────────────────────┘
```

### Layer 1：[`DependencyExtractor`](render/DependencyExtractor.java)

编译期分析模板 AST，提取每个行条目依赖的变量集合，构建 `行条目 → 变量集合` 映射。

**提取原理**：用 SpEL 自带的 AST 解析器（[`SpelExpressionParser`](render/DependencyExtractor.java:59)）解析表达式字符串，递归遍历 AST 树收集所有 [`PropertyOrFieldReference`](render/DependencyExtractor.java:159) 节点名称。

```
表达式: "player.name"
SpEL AST: PropertyOrFieldReference("name") ← 子节点
          └─ PropertyOrFieldReference("player") ← 根变量
提取结果: {player, name}
```

**保守策略**：提取所有属性引用名称（包括嵌套路径的每一段）。过度估计依赖只会导致少量不必要的重渲染，**不会漏掉**需要更新的行——正确性优先于性能。

**通配符降级**：SpEL 解析失败（非法语法）时返回 [`WILDCARD`](render/DependencyExtractor.java:62)（`"*"`），表示该行依赖所有变量，任何变更都触发重渲染（[`isAffected`](render/DependencyExtractor.java:250) 命中通配符即返回 `true`）。

> **为什么不用正则提取变量名**：SpEL AST 天然区分了节点类型——`true/false/null` 是 `Literal`、`T(Math)` 是 `TypeReference`、`and/or` 是运算符，都不会被误收；只有真正的属性/字段引用才会被收集。正则做不到这种语义区分。

### Layer 2：[`IncrementalRenderer`](render/IncrementalRenderer.java)

运行时依据 [`ChangeSet`](../../../../../../../../core/data/reactive/ChangeSet.java) 做增量渲染。核心算法（[`renderIncremental`](render/IncrementalRenderer.java:149)）：

1. **标题增量**：若 `titleDependencies ∩ changedKeys ≠ ∅` → 重新渲染标题，否则复用缓存。
2. **逐行增量**：遍历行条目，仅当 `entry.deps ∩ changedKeys ≠ ∅` 时重新渲染该条目：
   - 行数不变 → 逐行比较，收集变更索引到 [`changedLineIndices`](IncrementalRenderResult.java:68)
   - 行数变化 → 标记**结构变更**，回退全量渲染
3. **未受影响的行** → 直接复用缓存（跳过 SpEL 求值）。

### 三种变更类型

[`IncrementalRenderResult`](IncrementalRenderResult.java) 携带的变更信息驱动上层模块的输出策略：

| 变更类型 | 判定 | 上层动作 |
|----------|------|----------|
| **内容变更** | `changedLineIndices` 非空 | 只更新变化的行 |
| **结构变更** | `structureChanged = true` | 全量重建（行数变了） |
| **标题变更** | `titleChanged = true` | 更新标题 |
| **无变更** | [`hasChanges()`](IncrementalRenderResult.java:86) = false | 完全跳过，不发包 |

### 结构变更回退

当 [`DynamicLine`](ast/DynamicLine.java) 展开数变化（列表增删元素）或 [`StaticLine`](ast/StaticLine.java) 条件显隐切换导致行数变化时，逐行更新无意义，[`IncrementalRenderer`](render/IncrementalRenderer.java:218) 直接回退到 [`renderFull`](render/IncrementalRenderer.java:97) 全量渲染。这是「增量优先、全量兜底」的策略。

> **状态性**：[`IncrementalRenderer`](render/IncrementalRenderer.java) **有状态**（缓存上次渲染结果），每个视图（如每个玩家的计分板）应持有独立实例，不可跨视图共享。

---

## 六、缓存策略

[`TemplateEngine`](TemplateEngine.java) 维护两套独立的缓存，均用 `ConcurrentHashMap`：

| 缓存 | 字段 | Key | 价值 |
|------|------|-----|------|
| 按名称 | [`nameCache`](TemplateEngine.java:54) | 模板名称 | 配合 `lastModified` 实现热重载 |
| 按源码 | [`sourceCache`](TemplateEngine.java:60) | 源码 SHA-256 | 避免重复编译相同源码 |

### 按源码缓存（[`compile`](TemplateEngine.java:86)）

[`hashKey`](TemplateEngine.java:201) 计算源码的 SHA-256 作为 key，[`computeIfAbsent`](TemplateEngine.java:88) 保证相同源码只编译一次。SHA-256 失败时退化为 `length + hashCode`，保证可用性。

> **为什么按源码哈希而非源码本身做 key**：源码可能很长，作为 Map key 的 `hashCode()`/`equals()` 开销大；定长哈希作 key 更高效。

### 按名称缓存 + 热重载（[`getTemplate`](TemplateEngine.java:110)）

每次取模板都比较 [`lastModified`](loader/TemplateLoader.java:32)：

```java
long currentModified = loaderChain.lastModified(name);
if (cached != null && cachedModified == currentModified) {
    return cached;   // 缓存命中且文件未修改
}
// 否则重新加载 + 编译 + 更新缓存
```

- 文件被修改 → `lastModified` 变化 → 自动重新编译。
- classpath 模板 → `lastModified` 恒为 0 → 首次编译后一直命中。

---

## 七、Loader 链与热重载

[`CompositeTemplateLoader`](loader/CompositeTemplateLoader.java) 用 `List`（构造时 `ArrayList`）持有多个 loader，按顺序尝试，返回首个找到的模板。

### lastModified 的语义

[`CompositeTemplateLoader.lastModified`](loader/CompositeTemplateLoader.java:59) 先 `load` 找到命中的 loader，再返回**该 loader** 的修改时间——而非取所有 loader 的最大值。这保证热重载检测针对的是实际命中的来源。

### 路径穿越防护

[`FileTemplateLoader.resolveFile`](loader/FileTemplateLoader.java:76) 用 canonical path 检查确保解析后的文件在 `baseDir` 内，防止 `../` 逃逸：

```java
if (!canonicalFile.startsWith(canonicalBase)) {
    throw new SecurityException("模板路径超出基准目录: " + name);
}
```

> **为什么需要防护**：模板名称来自外部（如配置文件、命令），若不校验，`../../etc/passwd` 之类的名称可能读到基准目录外的文件。

### 跨插件类加载

[`ClasspathTemplateLoader`](loader/ClasspathTemplateLoader.java) 默认用 `new ClassPathResource(path)`（不传 ClassLoader），Spring 内部取当前线程上下文类加载器——在 Nukkit 中这只能看到**本插件 jar**。要加载其他插件 jar 内的模板，需通过新增的 `ClassLoader` 构造传入目标类加载器：

```java
// load() 内部根据 classLoader 选择构造方式
Resource resource = classLoader != null
        ? new ClassPathResource(path, classLoader)
        : new ClassPathResource(path);
```

类加载器由 [`jframe_core`](../../../../../../../../core/classloader/PluginClassLoaderFactory.java) 的 [`PluginClassLoaderFactory`](../../../../../../../../core/classloader/PluginClassLoaderFactory.java) 提供（按插件名查找），多个插件可聚合为 [`CompositeClassLoader`](../../../../../../../../core/classloader/CompositeClassLoader.java)（委托优先、父加载器兜底）。详见 core 模块文档。

---

## 八、线程安全

不同组件的线程安全策略不同，维护时需注意：

| 组件 | 线程安全 | 原因 |
|------|:--------:|------|
| [`Template`](Template.java) | ✅ 安全 | 不可变（构造后只读） |
| [`TemplateParser`](parser/TemplateParser.java) | ⚠️ 受限 | `DocumentBuilder` **非线程安全**，不可并发调用 `parseXml` |
| [`TemplateRenderer`](render/TemplateRenderer.java) | ✅ 安全 | 无状态，可作为单例 |
| [`RenderContext`](render/RenderContext.java) | ✅ 安全 | 每次渲染新建，不共享 |
| [`TemplateEngine`](TemplateEngine.java) | ✅ 安全 | `ConcurrentHashMap` + `CompositeTemplateLoader`（CopyOnWriteArrayList 风格的 `addLoader`） |
| [`IncrementalRenderer`](render/IncrementalRenderer.java) | ❌ 不安全 | 有状态（缓存上次结果），调用方需自行同步 |

### DocumentBuilder 的陷阱

[`TemplateParser`](parser/TemplateParser.java) 持有单个 `DocumentBuilder` 实例，而 JDK 的 `DocumentBuilder` **不是线程安全的**。当前 [`TemplateEngine`](TemplateEngine.java) 的缓存（`computeIfAbsent`）使并发编译相同源码时只会有一次实际 `parse` 调用，但**不同源码的并发编译仍可能竞争同一个 `DocumentBuilder`**。

> **维护提示**：若未来出现高并发编译场景（如大量不同模板首次加载），应将 `DocumentBuilder` 改为 `ThreadLocal` 或用 `DocumentBuilderFactory.newDocumentBuilder()` 每次新建。当前单线程加载模板的用法下不存在问题。

---

## 九、扩展指南

### 新增一个模板标签

以新增 `<switch>` 多分支标签为例：

1. **常量**：在 [`TemplateConstants`](TemplateConstants.java) 添加 `TAG_SWITCH = "switch"`。
2. **AST 节点**：在 [`ast/`](ast/TemplateNode.java) 新增 `SwitchNode` record，并加入 `sealed` 的 `permits` 列表。
3. **解析**：在 [`TemplateParser.parseInlineNodes`](parser/TemplateParser.java:153) 的 switch 中增加 `case TAG_SWITCH` 分支。
4. **渲染**：在 [`TemplateRenderer.renderNode`](render/TemplateRenderer.java:151) 的 switch 中增加 `case SwitchNode` 分支。
5. **依赖提取**：在 [`DependencyExtractor.extractFromNode`](render/DependencyExtractor.java:89) 的 switch 中增加分支，提取条件与各分支节点的依赖。
6. **测试**：在 [`TemplateDemoTest`](../../../../../../../../../test/java/io/github/JiangHu/jframe/content_template/TemplateDemoTest.java) 增加用例。

> 因为 AST 用 sealed 接口，编译器会在第 3、4、5 步强制你处理新节点类型，不会遗漏。

### 新增一个模板加载器

实现 [`TemplateLoader`](loader/TemplateLoader.java) 接口的 [`load`](loader/TemplateLoader.java:24) 与 [`lastModified`](loader/TemplateLoader.java:32) 即可，然后通过 [`registerLoader`](TemplateEngine.java:74) 注册：

```java
public class DatabaseTemplateLoader implements TemplateLoader {
    @Override
    public String load(String name) {
        // 从数据库查询模板源码，找不到返回 null
    }
    @Override
    public long lastModified(String name) {
        // 返回数据库记录的更新时间，不支持热重载返回 0
    }
}
```

### 新增一种求值语义

若需要不同于 SpEL 的表达式语义（如自定义函数库），可在 [`RenderContext`](render/RenderContext.java) 注入自定义的 `ExpressionParser`（已有 [`RenderContext(parser, variables)`](render/RenderContext.java:58) 构造），或注册自定义 SpEL 函数。注意同步更新 [`DependencyExtractor`](render/DependencyExtractor.java) 的依赖提取逻辑，否则增量渲染可能漏判依赖。

### 修改增量渲染策略

当前 [`IncrementalRenderer`](render/IncrementalRenderer.java) 采用「内容变更逐行比较、结构变更全量回退」。若需更细粒度的增量（如结构变更时也做行插入/删除 diff 而非全量重建），需改造 [`renderIncremental`](render/IncrementalRenderer.java:149) 的结构变更分支，并扩展 [`IncrementalRenderResult`](IncrementalRenderResult.java) 携带插入/删除操作信息。

> 任何修改请同步更新 [README.md](README.md) 的语法说明与测试覆盖（[`TemplateDemoTest`](../../../../../../../../../test/java/io/github/JiangHu/jframe/content_template/TemplateDemoTest.java) 覆盖插值 / SpEL / 条件 / 循环 / 响应式 / 缓存，[`IncrementalRendererTest`](../../../../../../../../../test/java/io/github/JiangHu/jframe/content_template/render/IncrementalRendererTest.java) 覆盖增量场景）。
