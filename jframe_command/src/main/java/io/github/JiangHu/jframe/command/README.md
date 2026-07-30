# 命令系统 (Command System)

> 基于 Nukkit + Spring 的声明式命令路由框架 —— 把 Spring MVC 的 `@Controller` / `@RequestMapping` / `@PathVariable` 体验搬到 Minecraft 命令处理。
>
> 通过 **6 个注解** 声明命令处理器，框架自动完成路径匹配、参数解析、类型转换、权限校验和 Nukkit 注册。
>
> **一个根命令下可挂载任意子命令**，无需手写 `if/else if` 分支，无需手动拆分参数字符串。

---

## 📖 这个框架是干什么的？

让玩家输入的命令文本（如 `/guild kick Steve --reason 违规`）自动找到正确的处理方法，并且：

- **路径模式匹配**：`kick {target}` 自动捕获 `target=Steve`，支持单值 `{id}`、贪婪 `{*msg}`、兼容 `#{id}`
- **参数自动绑定**：路径变量、命名参数（`--key value`）、位置参数各司其职，自动类型转换
- **特异性路由**：多条模式匹配同一输入时，静态段多的优先（`kick {target}` 优先于 `{cmd} {target}`）
- **权限与仅玩家校验**：声明式 `permission` 属性 + `@Sender Player` 类型约束

### 核心概念：6 个注解

| 注解 | Spring MVC 对应 | 作用 | 标注位置 |
|------|-----------------|------|----------|
| [`@CommandController`](annotation/CommandController.java) | `@Controller` | 声明命令控制器，指定**根命令** | 类 |
| [`@CommandMapping`](annotation/CommandMapping.java) | `@RequestMapping` | 声明命令处理方法，指定**子路径**与元数据 | 实例方法 |
| [`@PathVariable`](annotation/PathVariable.java) | `@PathVariable` | 绑定**路径变量**（从路径模式捕获） | 方法参数 |
| [`@CommandParam`](annotation/CommandParam.java) | `@RequestParam` | 绑定**命名参数**（`--key value`） | 方法参数 |
| [`@Sender`](annotation/Sender.java) | — | 注入**命令发送者**（`Player` 类型强制仅玩家） | 方法参数 |
| [`@RawArgs`](annotation/RawArgs.java) | — | 注入**原始参数数组**（自行解析） | 方法参数 |

> **与经典 `onCommand` 的区别**：经典写法在一个大方法里 `if (label.equals("kick"))` 逐分支判断、手动 `split`、手动 `parseInt`；本框架把每个子命令拆成独立方法，路径与参数声明在注解里，框架自动路由与绑定。

---

## 🏗️ 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  用户代码层                                               │
│  @CommandController + @CommandMapping + 参数注解         │
├─────────────────────────────────────────────────────────┤
│  路由引擎层                                               │
│  CommandRegistry —— 路径匹配、特异性排序、参数绑定、分发   │
│  PathPattern   —— 路径段解析、前缀匹配、特异性比较         │
│  CommandRoute  —— 方法模板 + 参数绑定策略 + 反射调用       │
├─────────────────────────────────────────────────────────┤
│  桥接层                                                   │
│  CommandEngine —— 对接 Nukkit CommandMap，注册根命令       │
│  CommandAPI    —— 公开门面（register / scan / bindPlugin）│
└─────────────────────────────────────────────────────────┘
```

### 各层职责（单向 DAG，无循环依赖）

| 层 | 类 | 职责 |
|----|----|------|
| L1 | [`CommandRegistry`](routing/CommandRegistry.java) | 注册控制器、路径匹配、特异性排序、分发执行 |
| L1 | [`PathPattern`](routing/PathPattern.java) | 路径字符串 → 段序列、前缀匹配、特异性比较器 |
| L1 | [`CommandRoute`](routing/CommandRoute.java) | 方法参数绑定策略预解析 + 反射调用 |
| L2 | [`CommandEngine`](CommandEngine.java) | 向 Nukkit `CommandMap` 注册根命令、转发分发 |
| L3 | [`CommandAPI`](CommandAPI.java) | 用户入口：`register` / `scan` / `bindPlugin` |

> **创建顺序**：`CommandRegistry`（无依赖）→ `CommandEngine`（依赖 Registry）→ `CommandAPI`（依赖两者）。Spring 按此顺序构造。

---

## 🚀 快速上手

### 前置：初始化框架

在插件主类 `onEnable()` 中绑定 Plugin 实例并注册控制器：

```java
@Override
public void onEnable() {
    ApplicationContext ctx = ...;
    CommandAPI commandAPI = ctx.getBean(CommandAPI.class);
    commandAPI.bindPlugin(this);                       // 必须！用于注册到 Nukkit
    commandAPI.scan("com.myplugin.command");           // 包扫描注册所有控制器
}
```

> 若使用 `JFrameMain`，框架会自动扫描 `PluginAware` Bean（即 `CommandEngine`）并调用 `bindPlugin`，无需手动调用。

---

### 用法一：基础子命令 + 路径变量

一个根命令 `guild` 下挂载多个子命令，每个子命令是独立方法。

```java
@CommandController("guild")   // 根命令：/guild
public class GuildController {

    // /guild create 我的公会  →  name = "我的公会"
    @CommandMapping("create {name}")
    public void create(@Sender CommandSender sender,
                       @PathVariable("name") String name) {
        sender.sendMessage("§a已创建公会: " + name);
    }

    // /guild lookup 42  →  id = "42"
    @CommandMapping("lookup #{id}")   // #{id} 是 {id} 的兼容写法
    public void lookup(@PathVariable("id") String id) {
        // ...
    }
}
```

**工作原理：**
1. 注册时，`create {name}` 与类级 `guild` 拼接为完整模式 `guild create {name}`
2. 玩家输入 `/guild create 我的公会`，Nukkit 触发 `onCommand(sender, "guild", ["create", "我的公会"])`
3. 框架构建路径 `[guild, create, 我的公会]`，匹配模式 → 捕获 `name=我的公会`
4. 反射调用 `create()`，自动注入 `sender` 和 `name`

---

### 用法二：贪婪变量 `{*msg}`（捕获剩余所有参数）

需要把"剩余所有参数"合并为一个值时，用 `{*name}` 贪婪捕获。

```java
// /guild broadcast hello world foo  →  msg = ["hello", "world", "foo"]
@CommandMapping("broadcast {*msg}")
public void broadcast(@Sender CommandSender sender,
                      @PathVariable("msg") String[] msg) {
    sender.sendMessage("§b" + String.join(" ", msg));   // 输出 "hello world foo"
}
```

> 贪婪变量绑定到 `String[]`；若方法参数是 `String`，框架自动用空格连接。

---

### 用法三：命名参数 `@CommandParam` + 默认值

可选参数用 `--key value` 形式追加在命令尾部，顺序无关。

```java
// /guild kick Steve --reason 违规     →  reason = "违规"
// /guild kick Steve                   →  reason = "无"（使用默认值）
@CommandMapping("kick {target}")
public void kick(@PathVariable("target") String target,
                 @CommandParam(value = "reason", defaultValue = "无") String reason) {
    // target 来自路径，reason 来自 --reason 命名参数
}
```

> **与 Spring MVC 一致**：声明了 `defaultValue` 的参数**隐式变为可选**，缺失时回退默认值，不会报错。

**布尔标记**：`--flag` 后不跟值时绑定为 `"true"`，适合 boolean 参数：

```java
// /shop sell sword --all    →  all = true
// /shop sell sword          →  all = false（默认值）
@CommandMapping("sell {item}")
public void sell(@PathVariable("item") String item,
                 @CommandParam(value = "all", defaultValue = "false") boolean all) {
    // ...
}
```

---

### 用法四：位置参数（无注解）+ 自动类型转换

不标注任何注解的参数按**位置顺序**绑定剩余 token，自动转换为基础类型。

```java
// /guild sethome tower 10 20 30  →  name=tower, x=10, y=20, z=30
@CommandMapping("sethome {name}")
public void sethome(@PathVariable("name") String name,
                    int x, int y, int z) {   // 无注解 → 位置参数，自动转 int
    // ...
}
```

> 位置参数 = 路径模式**未消耗**的剩余 token。`sethome {name}` 消耗 `sethome` 和 `tower`，剩余 `10 20 30` 按序绑定到 `x, y, z`。

---

### 用法五：`@Sender` 类型约束（仅玩家可用）

`@Sender` 注入命令发送者。声明为 `Player` 类型时，非玩家执行自动失败。

```java
// /shop spawn  →  仅玩家可用，控制台执行返回失败
@CommandMapping(value = "spawn", permission = "shop.spawn")
public void spawn(@Sender Player player) {
    player.sendMessage("§d已传送到商店");
}
```

- 控制台执行 → 返回 `§c该命令只能由玩家在游戏内执行`
- 无权限玩家执行 → 返回 `§c你没有权限执行此命令`

---

### 用法六：`@RawArgs` 原始参数透传

需要完全自行解析参数时，用 `@RawArgs` 注入原始 `String[]`（含子命令名）。

```java
// /shop echo foo bar baz  →  rawArgs = ["echo", "foo", "bar", "baz"]
@CommandMapping("echo")
public void echo(@Sender CommandSender sender, @RawArgs String[] rawArgs) {
    sender.sendMessage("§7" + String.join(" ", rawArgs));
}
```

---

### 💡 类上不声明根命令行不行？

**行。** `@CommandController` 的 value 可以为空，此时根命令从方法路径的首段识别：

```java
@CommandController                      // 不声明根命令
public class HomeController {
    @CommandMapping("home set {name}")  // 根命令 home 从方法路径首段识别
    public void set(@PathVariable("name") String name) { ... }
}
```

唯一约束：方法路径首段必须是**静态文本**（不能是 `{var}`），否则框架无法注册稳定的根命令。

---

### 🔤 命令名大小写（对齐 Nukkit）

框架的命令名大小写处理与 **Nukkit 命令系统完全对齐**：命令名大小写不敏感，且内部统一以小写存储。

- **注册时归一化**：`@CommandController` / `@CommandMapping` 中声明的根命令与子命令（静态段）在解析时自动转为小写。
- **匹配时大小写不敏感**：玩家输入 `/GameItem`、`/gameitem`、`/GAMEITEM` 均命中同一路由。
- **变量值保留原样**：路径变量 `{name}` 捕获的值是**数据**，保留玩家输入的原始大小写，不会被转换。

> **为什么这么做？** Nukkit 注册命令后内部恒以小写存储，运行时 `command.getName()` 回传的也是小写。
> 若框架保留原始大小写（如 `GameItem`），会导致「注册名」与「Nukkit 回传名」不一致，路由匹配失败
> （表现为「未知的子命令」）。归一化为小写后，两者始终一致。

```java
// 即使根命令用驼峰命名，也能正常工作（内部自动归一化为 gameitem）
@CommandController("GameItem")
public class GameItemCommand {

    @CommandMapping(desc = "打开管理表单")        // /gameitem、/GameItem 均可触发
    public void main(@Sender Player player) { ... }

    @CommandMapping("give")                       // /gameitem give、/gameitem GIVE 均可触发
    public void give(@Sender Player player, String id) { ... }
}
```

> 💡 虽然框架已对大小写健壮，但遵循 Minecraft 惯例，**建议根命令统一用全小写**（如 `gameitem`），更清晰规范。

---

## 🔀 路径匹配与特异性排序

当多条模式都能匹配同一输入时，按特异性选择最优（与 Spring `RequestMappingHandlerMapping` 一致）：

| 优先级 | 规则 | 示例 |
|--------|------|------|
| 1 | **静态段多**者优先 | `guild kick {target}` 优于 `guild {cmd} {target}` |
| 2 | 模式总段数多者优先 | 捕获更多 token 的更具体 |
| 3 | 变量段少者优先 | 静态比例高的更具体 |
| 4 | 贪婪段少者优先 | 精确匹配优于贪婪兜底 |

**前缀匹配语义**：模式只匹配输入的前缀，未消耗的 token 作为位置参数。这允许路径变量与位置参数共存。

---

## 📚 所有 API 详解

### `@CommandController`（类级）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `String` | `""` | 根命令名（如 `"guild"`）。为空时从方法路径首段识别 |

---

### `@CommandMapping`（方法级）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `String` | `""` | 子路径模式（如 `"create {name}"`） |
| `desc` | `String` | `""` | 命令描述（聚合到 Nukkit `/help`） |
| `usage` | `String` | `""` | 用法提示（默认用路径模式） |
| `permission` | `String` | `""` | 所需权限节点（空则不校验） |
| `aliases` | `String[]` | `{}` | 根命令别名 |

---

### `@PathVariable`（参数级）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `String` | `""` | 变量名（对应路径中的 `{name}`）。为空时回退编译参数名 |

支持的目标类型：`String`、`String[]`（贪婪变量）、`int`/`long`/`double`/`float`/`boolean` 等基础类型。

---

### `@CommandParam`（参数级）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `String` | `""` | 参数名（对应 `--name`）。为空时回退编译参数名 |
| `required` | `boolean` | `true` | 是否必需（声明 `defaultValue` 后隐式可选） |
| `defaultValue` | `String` | `""` | 默认值（非空时参数隐式可选，缺失时回退） |

---

### `@Sender` / `@RawArgs`（参数级）

| 注解 | 说明 |
|------|------|
| `@Sender` | 注入 `CommandSender`；声明 `Player` 类型时强制仅玩家可用 |
| `@RawArgs` | 注入原始 `String[]`（必须是 `String[]` 类型） |

---

### `CommandAPI`（用户入口）

| 方法 | 说明 |
|------|------|
| `register(Class<?> controllerClass)` | 注册单个控制器类 |
| `unregister(Class<?> controllerClass)` | 注销整个控制器的所有路由 |
| `scan(String... basePackages)` | 包扫描注册（使用线程上下文类加载器） |
| `scan(ClassLoader, String...)` | 包扫描注册（指定类加载器） |
| `bindPlugin(Plugin plugin)` | 绑定插件实例（框架自动调用） |

---

## 📂 完整示例

| 示例 | 场景 | 关键特性 |
|------|------|---------|
| [`GuildController`](../../../../../../../test/java/io/github/JiangHu/jframe/command/example/GuildController.java) | 公会命令（`/guild`） | `@PathVariable` 单值/贪婪/`#`、`@CommandParam` 默认值、位置参数转换、`@Sender` |
| [`ShopController`](../../../../../../../test/java/io/github/JiangHu/jframe/command/example/ShopController.java) | 商店命令（`/shop`） | 多路径变量、布尔标记 `--all`、`@RawArgs`、`@Sender Player`、`permission` |
| [`CaseController`](../../../../../../../test/java/io/github/JiangHu/jframe/command/example/CaseController.java) | 大小写回归（`/gameitem`） | 根命令大写归一化、大小写不敏感匹配、变量值保留原样 |
| [`CommandLogicTest`](../../../../../../../test/java/io/github/JiangHu/jframe/command/test/CommandLogicTest.java) | 39 项逻辑测试 | 无 Nukkit 服务器的纯 JVM 可运行测试，覆盖全部能力 |

**运行测试：**
```bash
mvn -pl jframe_command exec:java \
    -Dexec.mainClass="io.github.JiangHu.jframe.command.test.CommandLogicTest" \
    -Dexec.classpathScope=test
```

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
