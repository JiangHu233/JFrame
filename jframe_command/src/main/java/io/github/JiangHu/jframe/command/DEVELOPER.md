# 命令系统 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，目的是让你 100% 理解每个类的内部实现、设计决策、线程安全模型，
> 并能在修改代码时知道**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个框架，请看 [README.md](README.md)。

---

## 目录

- [1. 设计哲学与核心问题](#1-设计哲学与核心问题)
- [2. 架构总览](#2-架构总览)
- [3. 运行时数据流（完整调用链）](#3-运行时数据流完整调用链)
- [4. 类逐一剖析](#4-类逐一剖析)
- [5. 线程安全分析](#5-线程安全分析)
- [6. 生命周期管理](#6-生命周期管理)
- [7. 扩展点](#7-扩展点)
- [8. 已知限制与陷阱](#8-已知限制与陷阱)
- [9. 修改指南（改代码前必读）](#9-修改指南改代码前必读)

---

## 1. 设计哲学与核心问题

### 1.1 与经典 `onCommand` 的对比

Nukkit 原生的命令处理是**面向过程**的：实现 `CommandExecutor` 接口，在一个 `onCommand` 方法里用 `if/else if` 逐分支判断子命令、手动拆分参数、手动类型转换。

```java
// 经典写法：一个方法处理所有子命令
public class GuildCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return false;
        String sub = args[0];
        if (sub.equals("create")) {
            if (args.length < 2) { sender.sendMessage("用法: /guild create <名字>"); return false; }
            String name = args[1];                    // 手动取参数
            // ... 创建逻辑
        } else if (sub.equals("kick")) {
            if (args.length < 2) return false;
            String target = args[1];
            String reason = "无";                      // 手动处理默认值
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--reason") && i + 1 < args.length) reason = args[i + 1];
            }
            // ... 踢人逻辑
        } else if (sub.equals("sethome")) {
            // 又一坨手动 parseInt ...
        }
        // ... 几十个 else if
        return true;
    }
}
```

本框架是**声明式路由**的：每个子命令是独立方法，路径模式与参数绑定声明在注解里，框架自动完成匹配、绑定、转换。

#### 架构与编程模型对比

| 维度 | 经典 `onCommand` | 本框架 |
|------|------------------|--------|
| 编程范式 | 面向过程（巨型 if/else） | 声明式（注解 + 方法） |
| 子命令组织 | 一个方法内分支 | 每个子命令一个方法 |
| 参数解析 | 手动 `args[i]` + `split` | `@PathVariable` / `@CommandParam` 自动绑定 |
| 类型转换 | 手动 `Integer.parseInt` + try/catch | 自动转换，失败自动提示 |
| 默认值 | 手动 `if (reason == null) reason = "无"` | `defaultValue = "无"` 声明式 |
| 路径匹配 | 手动 `equals` 逐段 | `PathPattern` 模式匹配 + 特异性排序 |
| 权限校验 | 手动 `sender.hasPermission()` | `permission` 属性声明式 |
| 仅玩家限制 | 手动 `instanceof Player` | `@Sender Player` 类型约束 |

### 1.2 本框架的核心设计思路

**"路径即路由"** —— 把命令文本视为 URL 路径，用 Spring MVC 的 `@RequestMapping` 模式匹配。`guild kick Steve` 等价于 HTTP 请求 `/guild/kick/Steve`，`{target}` 等价于 `{id}` 路径变量。

**"注册时预解析，分发时零解析"** —— 方法参数的绑定策略（哪个参数从哪取值）在注册时一次性解析为 `ParamBinding[]`，分发时只做取值与类型转换，避免每次命令调用都反射扫描注解。

**"单向 DAG，无循环依赖"** —— `CommandRegistry`（无依赖）← `CommandEngine`（依赖 Registry）← `CommandAPI`（依赖两者）。三层职责清晰，Spring 可按顺序构造。

### 1.3 为什么是 6 个注解？

| 注解 | 职责 | 为什么独立 |
|------|------|-----------|
| [`@CommandController`](annotation/CommandController.java) | 声明根命令 | 类级元数据，与方法级正交 |
| [`@CommandMapping`](annotation/CommandMapping.java) | 声明子路径 + 元数据 | 每个子命令独立路由 |
| [`@PathVariable`](annotation/PathVariable.java) | 路径变量绑定 | 从路径捕获，与命名参数来源不同 |
| [`@CommandParam`](annotation/CommandParam.java) | 命名参数绑定 | 从 `--key` 取值，可选/默认值语义 |
| [`@Sender`](annotation/Sender.java) | 发送者注入 | 特殊注入，非用户输入 |
| [`@RawArgs`](annotation/RawArgs.java) | 原始参数透传 | 逃生舱，完全自行解析 |

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户代码层                                    │
│                                                                     │
│  @CommandController("guild")                                        │
│  @CommandMapping("kick {target}") + @PathVariable/@CommandParam/... │
│     ↓ CommandAPI.register(Class)                                     │
├─────────────────────────────────────────────────────────────────────┤
│                         路由引擎层 (L1)                               │
│                                                                     │
│  CommandRegistry                                                    │
│  ├── routes: List<CommandRoute> (CopyOnWriteArrayList)              │
│  ├── register(): 扫描 @CommandMapping → 创建 CommandRoute           │
│  └── dispatch(): 路径匹配 → 特异性排序 → 权限校验 → 调用             │
│                                                                     │
│  PathPattern          CommandRoute                                  │
│  ├── parse(): 路径→段    ├── parseBindings(): 参数绑定策略预解析      │
│  ├── match(): 前缀匹配   └── invoke(): 取值+转换+反射调用            │
│  └── SPECIFICITY_COMPARATOR                                         │
│     ↓ dispatch() 返回 InvokeResult                                   │
├─────────────────────────────────────────────────────────────────────┤
│                         桥接层 (L2/L3)                                │
│                                                                     │
│  CommandEngine (implements PluginAware, CommandExecutor)            │
│  ├── onCommand(): Nukkit 回调 → registry.dispatch()                 │
│  ├── syncRoots(): 向 CommandMap 注册根命令 (PluginCommand)           │
│  └── bindPlugin(): 刷新待注册队列                                    │
│                                                                     │
│  CommandAPI (门面)                                                   │
│  └── register/scan/bindPlugin → 委托转发                             │
│     ↓                                                                │
│  Nukkit CommandMap (Server.getCommandMap())                         │
└─────────────────────────────────────────────────────────────────────┘
```

### 依赖关系图

```
CommandRegistry (无依赖)
       ↑
CommandEngine (依赖 Registry：分发委托 + 根命令同步)
       ↑
CommandAPI (依赖 Registry + Engine：门面转发)
```

> **关键：** 依赖是单向的。L1（Registry）不知道 L2（Engine）的存在。Engine 持有 Registry 引用，在 `onCommand` 回调中调用 `registry.dispatch()`。

---

## 3. 运行时数据流（完整调用链）

### 3.1 启动阶段（Spring 容器初始化）

```
Spring 容器启动
    │
    ├── 创建 CommandRegistry Bean（无依赖，最先创建）
    │
    ├── 创建 CommandEngine Bean
    │     └── 构造注入 CommandRegistry
    │     └── plugin == null，syncRoots() 的根命令暂存到 pendingRoots
    │
    └── 创建 CommandAPI Bean
          └── 构造注入 CommandRegistry + CommandEngine
```

### 3.2 控制器注册阶段（手动调用 register(Class)）

```
用户调用 commandAPI.register(GuildController.class)
    │
    ↓ 委托给 CommandRegistry.register()
    │
    ├── ① 读取 @CommandController value → basePath = "guild"
    │
    ├── ② createInstance(GuildController.class) → 无参构造创建单例
    │
    └── ③ 遍历 getDeclaredMethods()：
        └── 对每个 @CommandMapping 方法：
            ├── joinPath("guild", "kick {target}") → "guild kick {target}"
            ├── new PathPattern("guild kick {target}")
            │     └── parse() → [STATIC:guild, STATIC:kick, VARIABLE:target]
            ├── pattern.rootCommand() → "guild"（首段静态）
            │     └── 若首段是变量 → rootCommand() 返回 null → 跳过并警告
            ├── new CommandRoute(instance, method, pattern, mapping)
            │     └── parseBindings() → 预解析每个参数的绑定策略 (ParamBinding[])
                       └── @Sender → Kind.SENDER
		            └── @PathVariable → Kind.PATH_VARIABLE
		            └── @CommandParam → Kind.COMMAND_PARAM (含 required/defaultValue)
		            └── @RawArgs → Kind.RAW_ARGS
		            └── 无注解 → Kind.POSITIONAL
            ├── 收集别名 → registerAliases(root, mapping.aliases())
            └── 加入 routes 列表
    │
    ↓ commandAPI.register() 内部继续调用 engine.syncRoots()
    │
    engine.syncRoots()
    └── 遍历 registry.getRootCommands()
        └── plugin == null → 暂存到 pendingRoots
        └── plugin != null → doRegister(root) 立即注册
```

### 3.3 插件启用阶段（onEnable）

```
插件主类 onEnable()
    └── commandAPI.bindPlugin(this)
        └── engine.bindPlugin(plugin)
            ├── 设置 this.plugin
            └── 遍历 pendingRoots → doRegister(root)
                └── new PluginCommand<>(root, plugin)
                └── cmd.setExecutor(this)   ← CommandEngine 自身作为执行器
                └── 聚合元数据 (desc/usage/aliases)
                └── Server.getCommandMap().register(prefix, cmd)
```

### 3.4 命令执行阶段（运行时热路径）

```
玩家输入 /guild kick Steve --reason 违规
    │
    ↓ Nukkit 解析：command="guild", args=["kick", "Steve", "--reason", "违规"]
    │
    ↓ Nukkit 查找已注册的 PluginCommand → 调用其 CommandExecutor
    │
    CommandEngine.onCommand(sender, command, label, args)
    │
    └── registry.dispatch(sender, "guild", args)
        │
        ├── ① 别名解析：aliasToRoot.getOrDefault("guild", "guild")
        │
        ├── ② ArgumentResolver.parseArgs(args)
        │     └── ["kick", "Steve", "--reason", "违规"]
        │     └── → positional=["kick", "Steve"], named={reason=违规}
        │
        ├── ③ 构建完整路径 fullPath = ["guild"] + positional
        │     └── ["guild", "kick", "Steve"]
        │
        ├── ④ 遍历所有 CommandRoute，pattern.match(fullPath)
        │     └── "guild kick {target}".match(["guild","kick","Steve"])
        │     └── guild==guild ✓, kick==kick ✓, Steve→{target} ✓
        │     └── consumed=3, pathVars={target=Steve}
        │
        ├── ⑤ 收集所有匹配项，按 SPECIFICITY_COMPARATOR 排序，取最优
        │
        ├── ⑥ 权限校验：mapping.permission() → sender.hasPermission()
        │
        ├── ⑦ 计算剩余位置参数：remainder = fullPath[consumed..]
        │     └── consumed=3 == fullPath.size()=3 → remainder=[]
        │
        ├── ⑧ 构建 CommandContext(sender, root, remainder, named, pathVars, rawArgs)
        │
        └── ⑨ best.route.invoke(ctx)
            │
            ├── 遍历 ParamBinding[]，按 Kind 取值：
            │   ├── SENDER → ctx.sender() (Player 类型校验)
            │   ├── PATH_VARIABLE "target" → ctx.pathVars().get("target")="Steve"
            │   └── COMMAND_PARAM "reason" → ctx.named().get("reason")="违规"
            │
            ├── 类型转换：ArgumentResolver.convert(value, type, sender)
            │
            └── method.invoke(controller, args)  ← 反射调用 kick()
```

---

## 4. 类逐一剖析

### 4.1 [`PathPattern`](routing/PathPattern.java) — 路径模式核心

**职责**：把路径字符串解析为段序列，提供前缀匹配与特异性比较。

**三种段类型**（[`Segment.Kind`](routing/PathPattern.java:215)）：
- `STATIC` — 字面量，解析时**归一化为小写**，匹配时**大小写不敏感**（如 `guild`、`kick`）
- `VARIABLE` — `{name}`，捕获单个 token（**保留玩家输入的原始大小写**）
- `GREEDY` — `{*name}`，捕获剩余所有 token（0 个或多个）

**匹配算法**（[`match()`](routing/PathPattern.java:95)）：
- 逐段匹配，静态段用 `equalsIgnoreCase` 比较（对齐 Nukkit 命令大小写不敏感），变量段吃一个 token，贪婪段吃掉剩余全部
- 返回 `MatchResult(consumed, pathVars)`，`consumed` = 模式消耗的 token 数
- **前缀匹配**：输入可以比模式长，多余 token 成为位置参数

> **大小写对齐 Nukkit**：静态段（根命令 + 子命令）在 [`parse()`](routing/PathPattern.java:69) 时 `toLowerCase(Locale.ROOT)` 归一化，
> 使「注册名 / 日志 / Nukkit 回传名（恒小写）」三者一致。变量捕获值是数据，保留原样大小写不转换。

**特异性比较器**（[`SPECIFICITY_COMPARATOR`](routing/PathPattern.java:178)）：
```
1. 静态段数多者优先（降序）
2. 总段数多者优先（降序）
3. 变量段数少者优先（升序）
4. 贪婪段数少者优先（升序）
```

**`rootCommand()`**：返回首段（若为静态段，**已归一化为小写**），用于 Nukkit 注册。首段是变量则返回 null。

> **不可变**：解析后 `segments` 不再变化，线程安全。

---

### 4.2 [`CommandRoute`](routing/CommandRoute.java) — 方法模板 + 参数适配器

**职责**：封装一个 `@CommandMapping` 方法的全部调用信息。

**两层职责合一**（命令场景无需事件模块的实例工厂复杂度）：
1. **模板元数据**：controller 实例、Method、PathPattern、CommandMapping
2. **参数适配器**：`ParamBinding[]` 预解析的参数绑定策略

**参数绑定优先级**（[`parseBindings()`](routing/CommandRoute.java:79)）：
```
@Sender > @RawArgs > @PathVariable > @CommandParam > 无注解(POSITIONAL)
```

**`invoke()` 分发逻辑**（[`invoke()`](routing/CommandRoute.java:116)）：
- 按绑定策略从 `CommandContext` 取值
- `SENDER` + `Player` 类型 → 非玩家直接返回失败
- `PATH_VARIABLE` → `resolvePathVariable()`，贪婪变量适配 `String[]`/`String`
- `COMMAND_PARAM` → `resolveCommandParam()`，有 defaultValue 则隐式可选
- `POSITIONAL` → 先尝试按名匹配路径变量，否则按序消费剩余 token

**失败传播**：用 `BindingFailure` 记录（内部 record）作为哨兵返回值，`invoke()` 检测到即转为 `InvokeResult.failure()`。

> **不可变**：构造后 `bindings` 不再变化，同一 CommandRoute 可被多线程并发调用。

---

### 4.3 [`CommandRegistry`](routing/CommandRegistry.java) — 路由引擎

**职责**：注册控制器、模式匹配、特异性排序、分发执行。

**核心数据结构**：
- `routes: List<CommandRoute>` — `CopyOnWriteArrayList`（读多写少）
- `classToRoutes: Map<Class, List<CommandRoute>>` — 用于注销
- `rootAliases` / `aliasToRoot` — 别名双向映射

**`dispatch()` 八步流程**（见 [3.4 节](#34-命令执行阶段运行时热路径)）：
1. 别名解析 → 2. 参数解析 → 3. 构建路径 → 4. 匹配路由 → 5. 特异性排序 → 6. 权限校验 → 7. 计算剩余 → 8. 调用

**日志空安全**（[`nukkitLogger()`](routing/CommandRegistry.java:281)）：
测试环境无 `Server` 实例时，`logInfo/logWarn/logError` 回退到 `System.out/err`，使纯 JVM 单元测试可运行。

---

### 4.4 [`CommandEngine`](CommandEngine.java) — Nukkit 桥接层

**职责**：向 Nukkit `CommandMap` 注册根命令，转发命令分发。

**双重身份**：
- `PluginAware` — `JFrameMain` 自动调用 `bindPlugin()`
- `CommandExecutor` — 作为所有 `PluginCommand` 的执行器

**根命令注册机制**：
- `syncRoots()` 由 `CommandAPI.register()` 每次注册后调用
- plugin 未绑定时暂存到 `pendingRoots`，`bindPlugin()` 后统一刷新
- `doRegister()` 创建 `PluginCommand`，聚合 desc/usage/aliases，注册到 `CommandMap`

**`onCommand()` 回调**：委托 `registry.dispatch()`，失败时向发送者回显错误消息。

---

### 4.5 [`CommandAPI`](CommandAPI.java) — 公开门面

**职责**：轻量门面，自身无业务逻辑，仅委托转发：
- `register/unregister` → `CommandRegistry` + 触发 `engine.syncRoots()`
- `scan` → `CommandScanner`
- `bindPlugin` → `CommandEngine`

---

### 4.6 [`ArgumentResolver`](resolve/ArgumentResolver.java) — 参数解析与转换

**`parseArgs()`**：把 `String[]` 拆分为位置参数 + 命名参数：
- `--key value` / `--key=value` → 长选项（分离/内联两种格式；内联可传 `-` 开头的值）
- `--key`（后跟选项或末尾）→ 布尔标记 `"true"`
- `-k value` / `-k=value` → 短选项（排除负数 `-3`）
- 其余 → 位置参数

**`convert()`**：字符串 → 目标类型（String/基础类型/Player）。

---

### 4.7 [`CommandScanner`](scan/CommandScanner.java) — 包扫描器

基于 Spring `ClassPathScanningCandidateComponentProvider`，扫描 `@CommandController` 类。
类加载器策略：TCCL → 本类类加载器。接口/抽象类自动跳过。

---

## 5. 线程安全分析

| 组件 | 可变性 | 并发策略 |
|------|--------|----------|
| `PathPattern` | 不可变 | 解析后只读，天然线程安全 |
| `CommandRoute` | 不可变 | `bindings` 构造后不变，`invoke()` 无状态 |
| `CommandContext` | 不可变 | 快照对象，集合以不可变视图暴露 |
| `CommandRegistry.routes` | 读多写少 | `CopyOnWriteArrayList`，注册时复制，分发无锁 |
| `CommandRegistry` 别名表 | 读多写少 | `ConcurrentHashMap` |
| `CommandEngine` 注册集合 | 读多写少 | `ConcurrentHashMap.newKeySet()` |
| `ArgumentResolver` | 无状态 | 纯静态方法，天然线程安全 |

**结论**：分发热路径（`dispatch` → `match` → `invoke`）完全无锁并发安全。注册（`register`）通过 CopyOnWrite 保证读一致性。

---

## 6. 生命周期管理

```
Spring 启动
  │
  ├── Bean 创建：Registry → Engine → API
  │
  ├── register(Class)  ← 可在任意时机调用（插件 onEnable 或运行时热加载）
  │     └── syncRoots() → plugin 已绑定则立即注册到 Nukkit，否则暂存
  │
  ├── bindPlugin(plugin)  ← JFrameMain 自动调用
  │     └── 刷新 pendingRoots
  │
  ├── 运行时：Nukkit onCommand → dispatch → invoke
  │
  └── unregister(Class)  ← 运行时热卸载（从 routes 移除，Nukkit 命令保留但无路由匹配）
```

> **注意**：`unregister` 只移除路由，不注销 Nukkit 已注册的 `PluginCommand`。Nukkit 命令会保留但分发时返回"未知子命令"。

---

## 7. 扩展点

### 7.1 新增参数注解

在 [`CommandRoute.parseBindings()`](routing/CommandRoute.java:79) 添加新的 `Kind` 分支，在 `invoke()` 的 `switch` 中添加取值逻辑。

### 7.2 新增类型转换

在 [`ArgumentResolver.convert()`](resolve/ArgumentResolver.java:134) 添加新的 `if (targetType == ...)` 分支。

### 7.3 自定义匹配策略

修改 [`PathPattern.SPECIFICITY_COMPARATOR`](routing/PathPattern.java:178) 调整优先级规则。

---

## 8. 已知限制与陷阱

### 8.1 路径首段必须是静态

[`CommandRegistry.register()`](routing/CommandRegistry.java:107) 校验 `pattern.rootCommand() != null`。若方法路径首段是变量（如 `{cmd} set`），无法注册稳定根命令，会被跳过并警告。

### 8.2 `-parameters` 编译选项

`@PathVariable` / `@CommandParam` 的 `value()` 为空时回退编译参数名，需要 `-parameters` 编译选项。**生产环境建议显式指定 value**，不依赖编译选项。

### 8.3 位置参数与路径变量的边界

位置参数 = 路径模式**未消耗**的剩余 token。若路径模式 `sethome {name}` 消耗了 `sethome tower`，则 `10 20 30` 是位置参数。设计路径时要明确哪些是路径变量、哪些是位置参数。

### 8.4 贪婪变量必须放最后

`{*msg}` 匹配剩余所有 token，其后不能再有其他段（框架会在贪婪段处立即返回）。

### 8.5 `@CommandParam` 默认值隐式可选

声明了非空 `defaultValue` 的参数**隐式变为可选**（与 Spring MVC 一致），即使 `required=true`（默认）也不会因缺失而报错。这是有意设计，避免用户忘记写 `required=false`。

### 8.6 `unregister` 不注销 Nukkit 命令

注销控制器只移除框架内部路由，Nukkit `CommandMap` 中已注册的 `PluginCommand` 不会被移除。玩家仍能 Tab 补全该命令，但执行时返回"未知子命令"。

---

## 9. 修改指南（改代码前必读）

### 改路径匹配逻辑 → [`PathPattern`](routing/PathPattern.java)

- `parse()` 改段解析规则（如支持新语法）
- `match()` 改匹配算法（如支持通配符）
- `SPECIFICITY_COMPARATOR` 改优先级

> ⚠️ 改 `match()` 的 `consumed` 计算会影响位置参数的边界，连锁影响 `CommandRoute` 的 `POSITIONAL` 绑定。

### 改参数绑定 → [`CommandRoute`](routing/CommandRoute.java)

- `parseBindings()` 改绑定策略识别
- `invoke()` 的 `switch` 改取值逻辑
- `resolveCommandParam()` 改命名参数语义（如默认值处理）

### 改分发流程 → [`CommandRegistry.dispatch()`](routing/CommandRegistry.java:159)

八步流程的任何一步改动都要考虑：
- 改步骤 ③（构建路径）会影响路径匹配
- 改步骤 ⑦（剩余计算）会影响位置参数
- 改步骤 ⑥（权限）要同步 `CommandRoute.invoke()` 中的 Player 类型校验

### 改 Nukkit 注册 → [`CommandEngine`](CommandEngine.java)

- `doRegister()` 改 `PluginCommand` 创建/注册
- `syncRoots()` 改根命令同步时机
- `onCommand()` 改分发回调

### 改参数解析 → [`ArgumentResolver`](resolve/ArgumentResolver.java)

- `parseArgs()` 改 `--key`/`-k` 解析规则
- `convert()` 改类型转换

> ⚠️ `parseArgs()` 的改动会影响位置参数与命名参数的划分，连锁影响路径匹配（位置参数参与路径构建）。

---

## 📂 源码导航

| 包 | 类 | 职责 |
|----|----|------|
| `command` | [`CommandEngine`](CommandEngine.java) | Nukkit 桥接（注册 + 分发回调） |
| `command` | [`CommandAPI`](CommandAPI.java) | 公开门面 |
| `command.annotation` | [`CommandController`](annotation/CommandController.java) | 类级根命令 |
| `command.annotation` | [`CommandMapping`](annotation/CommandMapping.java) | 方法级子路径 + 元数据 |
| `command.annotation` | [`PathVariable`](annotation/PathVariable.java) | 路径变量绑定 |
| `command.annotation` | [`CommandParam`](annotation/CommandParam.java) | 命名参数绑定 |
| `command.annotation` | [`Sender`](annotation/Sender.java) | 发送者注入 |
| `command.annotation` | [`RawArgs`](annotation/RawArgs.java) | 原始参数透传 |
| `command.routing` | [`PathPattern`](routing/PathPattern.java) | 路径模式解析/匹配/特异性 |
| `command.routing` | [`CommandRoute`](routing/CommandRoute.java) | 方法模板 + 参数绑定 |
| `command.routing` | [`CommandRegistry`](routing/CommandRegistry.java) | 路由引擎（注册/匹配/分发） |
| `command.resolve` | [`CommandContext`](resolve/CommandContext.java) | 调用上下文快照 |
| `command.resolve` | [`ArgumentResolver`](resolve/ArgumentResolver.java) | 参数解析 + 类型转换 |
| `command.resolve` | [`ArgumentConversionException`](resolve/ArgumentConversionException.java) | 转换失败异常 |
| `command.scan` | [`CommandScanner`](scan/CommandScanner.java) | 包扫描器 |
| `command.config` | [`CommandSpringConfig`](config/CommandSpringConfig.java) | Spring 配置入口 |

> 用户使用文档请看 [README.md](README.md)。
