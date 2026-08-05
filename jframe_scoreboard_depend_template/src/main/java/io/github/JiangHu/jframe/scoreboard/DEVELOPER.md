# jframe_scoreboard 开发者文档

> 面向贡献者和二次开发者的内部实现文档。

## 目录结构

```
jframe_scoreboard/
├── pom.xml                              ← Maven 配置（依赖 jframe_core + jframe_dependency_template）
└── src/main/
    ├── java/io/github/JiangHu/jframe/scoreboard/
    │   ├── ScoreboardAPI.java           ← 用户门面（API 入口）
    │   ├── ScoreboardManager.java       ← 管理器（模板注册表 + 玩家视图映射）
    │   ├── ScoreboardView.java          ← 玩家视图（DataContext + Nukkit IScoreboard 绑定）
    │   ├── ScoreboardTemplate.java      ← 模板配置（Template + 显示参数）
    │   ├── ScoreboardConstants.java     ← 常量（前缀、默认值、限制）
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
| [`ScoreboardManager`](ScoreboardManager.java:32) | 模板注册表 + 玩家视图映射 + 生命周期 | `TemplateEngine`, `IScoreboardManager` |
| [`ScoreboardView`](ScoreboardView.java:50) | 单玩家计分板实例，绑定 DataContext ↔ Nukkit | `ScoreboardTemplate`, `DataContext`, `IScoreboard` |
| [`ScoreboardTemplate`](ScoreboardTemplate.java:40) | 模板配置（Template + DisplaySlot + SortOrder） | `Template` |
| [`ScoreboardConstants`](ScoreboardConstants.java:14) | 常量集中管理 | — |

## 核心设计

### 1. 三层架构

```
ScoreboardAPI（门面）
    └→ ScoreboardManager（管理器）
         ├→ ScoreboardTemplate（模板配置）  ← 静态：注册一次
         └→ ScoreboardView（玩家视图）      ← 动态：每玩家一个
```

**为什么分三层？**
- **API**：对用户隐藏内部细节，提供简洁的方法签名
- **Manager**：管理共享状态（模板注册表、视图映射），可被多个 API 实例共享
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
TemplateEngine.render(template, dataContext)
    ↓
RenderResult(title, lines)
    ↓
IScoreboard.setLines(lines)
    ↓
Nukkit 自动发包给客户端

// hide() 时解绑
dataContext.removeListener(changeListener);
```

### 3. 延迟初始化 IScoreboardManager

[`ScoreboardManager`](ScoreboardManager.java:32) 使用**双重检查锁定**延迟获取 Nukkit 管理器：

```java
private volatile IScoreboardManager nukkitManager;

private IScoreboardManager getNukkitManager() {
    if (nukkitManager == null) {
        synchronized (this) {
            if (nukkitManager == null) {
                nukkitManager = Server.getInstance().getScoreboardManager();
            }
        }
    }
    return nukkitManager;
}
```

**原因**：Spring 容器在 `onEnable` 阶段初始化 Bean，此时 `Server.getInstance()` 可能尚未就绪。延迟到首次 `show()` 调用时获取，确保 Server 已完全启动。

### 4. 玩家退出清理

[`ScoreboardManager.onPlayerQuit()`](ScoreboardManager.java:288) 执行完整的资源释放：

```
onPlayerQuit(player)
    ↓
hide(player)
    ├→ view.hide(player)
    │    ├→ dataContext.removeListener(changeListener)  ← 移除监听器
    │    ├→ nukkitScoreboard.removeViewer(player)        ← 移除 viewer
    │    └→ manager.removeScoreboard(nukkitScoreboard)   ← 注销计分板
    └→ views.remove(uuid)                                 ← 移除映射
```

> **重要**：必须在 `PlayerQuitEvent` 中调用 `scoreboard.onPlayerQuit(player)`，否则会内存泄漏。

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
    │         ├→ StaticLine（纯文本行）
    │         └→ DynamicLine（含表达式/条件/循环的行）
    │              ├→ TextNode（静态文本片段）
    │              ├→ ExpressionNode（{{ }} SpEL 表达式）
    │              ├→ IfNode（条件分支）
    │              └→ EachNode（循环）
    ↓ 缓存到 TemplateEngine
Template 对象（可重复渲染）
    ↓ TemplateRenderer（SpEL 求值）
RenderResult(title, lines)
```

### AST 节点体系

| 节点 | 对应 XML | 职责 |
|------|----------|------|
| [`TemplateNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/TemplateNode.java) | `<template>` | 根节点，持有 title + lines |
| [`StaticLine`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/StaticLine.java) | `<line>纯文本</line>` | 不含表达式的行 |
| [`DynamicLine`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/DynamicLine.java) | `<line>含 {{}} 或 <if></line>` | 含动态内容的行 |
| [`ExpressionNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/ExpressionNode.java) | `{{expr}}` | SpEL 表达式 |
| [`IfNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/IfNode.java) | `<if>/<elif>/<else>` | 条件分支 |
| [`EachNode`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/ast/EachNode.java) | `<each>` | 循环遍历 |

### 渲染流程

[`TemplateRenderer`](../../jframe_dependency_template/src/main/java/io/github/JiangHu/jframe/content_template/render/TemplateRenderer.java) 使用 Spring SpEL（`SpelExpressionParser`）求值：

```java
// 简化伪代码
for (LineEntry line : template.getLines()) {
    if (line instanceof StaticLine sl) {
        result.add(sl.getText());
    } else if (line instanceof DynamicLine dl) {
        StringBuilder sb = new StringBuilder();
        for (Fragment frag : dl.getFragments()) {
            if (frag instanceof TextNode tn) {
                sb.append(tn.getText());
            } else if (frag instanceof ExpressionNode en) {
                Object value = spelParser.parseExpression(en.getExpression())
                                        .getValue(context);
                sb.append(value);
            } else if (frag instanceof IfNode ifn) {
                // 递归求值条件分支
            } else if (frag instanceof EachNode en) {
                // 遍历集合并递归渲染子节点
            }
        }
        result.add(sb.toString());
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
| `IScoreboardManager` | `cn.nukkit.scoreboard.manager` | 管理器（注册/注销计分板） |
| `DisplaySlot` | `cn.nukkit.network.protocol.types` | 显示槽位枚举 |
| `SortOrder` | `cn.nukkit.network.protocol.types` | 排序方式枚举 |

### ScoreboardView 对接流程

```java
// 创建
nukkitScoreboard = new Scoreboard(objectiveName, displayName, criteriaName, sortOrder);
manager.addScoreboard(nukkitScoreboard);
nukkitScoreboard.setLines(lines);
nukkitScoreboard.addViewer(player, displaySlot);

// 更新
nukkitScoreboard.setLines(newLines);

// 销毁
nukkitScoreboard.removeViewer(player, displaySlot);
manager.removeScoreboard(nukkitScoreboard);
```

> **注意**：`Scoreboard` 的 `displayName` 在构造时设置，`refresh()` 目前只更新 `lines`。如需动态标题，需扩展 Nukkit API 调用。
