# JFrame 示例插件（jframe_example）

这是一个完整的 **Nukkit（我的世界基岩版服务端）插件**样例，演示如何使用 JFrame 框架的 **事件 / 表单 / 线程 / 命令** 四大模块搭建一个可运行、可测试的插件。

> 示例源码都在 `jframe_example` 内。为消除事件模块的 Spring 循环依赖，本次对 `jframe_event` 做了重构（拆分出内部 `EventEngine` + 公开 `EventService` 门面），详见根目录《建议的框架改进.txt》建议 6。

---

## 一、目录结构

```
jframe_example/
├── pom.xml                                   # Maven 构建（含 shade 打 fat jar）
└── src/main/
    ├── java/io/github/JiangHu/jframe/example/
    │   ├── ExamplePlugin.java                # 插件主类：引导 Spring、绑定插件、注册包装类与命令
    │   ├── command/
    │   │   └── KitController.java              # 命令模块示例：声明式 /kit 子命令（路径变量/命名参数/权限）
    │   ├── wrapper/
    │   │   ├── PlayerStatWrapper.java        # 示例1：每个玩家独立的统计实例
    │   │   └── ChatGuardWrapper.java         # 示例2：全局单例聊天审核
    │   └── view/
    │       ├── MainMenuView.java             # 主菜单表单
    │       └── StatsView.java                # 子菜单表单（演示栈式导航）
    └── resources/
        └── plugin.yml                        # Nukkit 插件描述文件
```

---

## 二、构建与安装

### 1. 前置条件
- JDK 24（与框架 `pom.xml` 一致）
- 已在本地 Maven 仓库安装好框架各模块：在**项目根目录**执行
  ```bash
  mvn clean install -DskipTests
  ```
  这会把 `jframe_core` / `jframe_event` / `jframe_form` / `jframe_thread` / `jframe_main` 安装到本地仓库。

### 2. 打包示例插件
```bash
cd jframe_example
mvn clean package
```
产物：`jframe_example/target/jframe_example.jar`

### 3. 关于 GUI 库（`libs/gui-1.15.1.jar`）
表单模块依赖的 `moe.him188:GUI` 在框架中是 **system 作用域**，不会被 shade 打包。
默认假设：**GUI 由服务端或单独的 GUI 插件提供**（运行时在 classpath 中）。

如果你希望把 GUI 一并打进 fat jar，请：
1. 先把 jar 安装到本地仓库：
   ```bash
   mvn install:install-file -Dfile=libs/gui-1.15.1.jar -DgroupId=moe.him188.gui -DartifactId=GUI -Dversion=1.15.1 -Dpackaging=jar
   ```
2. 在本模块 `pom.xml` 的 GUI 依赖上加上 `<scope>compile</scope>`；
3. 删除 shade 配置中的 `<exclude>moe.him188.gui:GUI</exclude>`。

### 4. 部署
把 `jframe_example.jar` 放入 Nukkit 服务端的 `plugins/` 目录，重启服务器即可。

---

## 三、示例覆盖的能力点

| 能力 | 演示文件 | 关键 API / 注解 |
|------|----------|-----------------|
| Spring 容器引导（event/form/thread 三模块 XML 装配） | [`ExamplePlugin`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) | `ClassPathXmlApplicationContext` |
| 绑定插件实例到 `PluginAware` Bean | [`ExamplePlugin`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) | `PluginAware.bindPlugin()` |
| 注册事件包装类 | [`ExamplePlugin`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) | `EventService.register(Class)` |
| 对象级路由（每玩家一实例） | [`PlayerStatWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/PlayerStatWrapper.java) | `@KeyExtractor`（4 种事件） |
| 自定义实例工厂 + 外部可读注册表 | [`PlayerStatWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/PlayerStatWrapper.java) | `@InstanceProvider` |
| SpEL 条件处理器（仅 OP 触发） | [`PlayerStatWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/PlayerStatWrapper.java) | `@EventRoute(condition=...)` |
| 全局单例处理器 | [`ChatGuardWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/ChatGuardWrapper.java) | 恒定 `@KeyExtractor` + 固定 `@InstanceProvider` |
| 优先级 + 独占（拦截后阻止低优先级） | [`ChatGuardWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/ChatGuardWrapper.java) | `@EventHandler(priority, exclusive)` |
| filter 方法引用（编译期类型检查） | [`ChatGuardWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/ChatGuardWrapper.java) | `@EventRoute(filter=...)` |
| 表单构建与按钮回调 | [`MainMenuView`](src/main/java/io/github/JiangHu/jframe/example/view/MainMenuView.java) | `FormView.buildForm()` / `onClicked()` |
| 栈式导航（进入子菜单） | [`MainMenuView`](src/main/java/io/github/JiangHu/jframe/example/view/MainMenuView.java) | `FormView.addStack()` |
| 同层替换（返回主菜单） | [`StatsView`](src/main/java/io/github/JiangHu/jframe/example/view/StatsView.java) | `FormView.replaceThis()` |
| 异步任务队列 | [`MainMenuView`](src/main/java/io/github/JiangHu/jframe/example/view/MainMenuView.java) | `ThreadService.pushTask()` |
| 声明式命令路由（替代 onCommand 的 if/else） | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | `@CommandController` / `@CommandMapping` |
| 路径变量 + 自动类型转换 | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | `@PathVariable`（`give {item} {count}`） |
| 命名参数 + 默认值 / 布尔标记 | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | `@CommandParam`（`heal --amount` / `fly --on`） |
| 贪婪变量（捕获剩余参数） | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | `{*msg}`（`broadcast {*msg}`） |
| 仅玩家可用 + 权限校验 | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | `@Sender Player` + `permission` |
| 根命令动态注册到 Nukkit | [`ExamplePlugin`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) | `CommandAPI.scan()`（无需 plugin.yml） |

---

## 四、游戏内测试步骤

服务器启动后，用玩家账号进服，按以下步骤逐项验证：

### ① 事件 - 玩家进服欢迎
- **操作**：玩家进入服务器
- **预期**：收到 `§a欢迎来到 JFrame 示例服务器！`
- **OP 额外**：若该玩家是 OP，还会收到 `§6[管理员] 欢迎管理员上线！`（验证 SpEL 条件）

### ② 事件 - 移动计数
- **操作**：玩家四处移动
- **预期**：每移动 100 次收到 `§7你已经累计移动 §eN §7次。`

### ③ 表单 + 实时统计
- **操作**：输入 `/jframe`
- **预期**：弹出主菜单，显示当前的「移动次数」「聊天次数」（实时读取事件系统维护的数据）

### ④ 表单 - 栈式导航
- **操作**：在主菜单点击「📊 查看详细统计」
- **预期**：进入统计子界面；点击「↩ 返回主菜单」回到主菜单（验证 `addStack` / `replaceThis`）

### ⑤ 线程 - 异步任务
- **操作**：在主菜单点击「⚡ 执行异步任务」
- **预期**：立即收到 `§e已提交异步任务...`，约 1 秒后收到 `§b[异步任务] 耗时计算完成，结果 = N`

### ⑥ 事件 - 聊天审核（优先级 + 独占）
- **操作**：玩家发送包含违禁词（`fuck` / `shit` / `idiot`，不区分大小写）的消息
- **预期**：消息被拦截，收到 `§c请文明发言！...`；且该条消息**不计入聊天次数**（验证 HIGH 独占阻止了 NORMAL 的统计处理器）
- **对照**：发送正常消息时，聊天次数 +1（两个处理器都执行）

### ⑦ 命令 - 声明式命令路由（`/kit`）

命令模块用注解声明子命令，框架自动完成路由匹配、参数解析与类型转换，**无需手写 `onCommand` 的 `if/else`**。根命令 `/kit` 由 `CommandEngine` 在插件启用时动态注册到 Nukkit，**无需在 `plugin.yml` 声明**。

| 操作 | 预期 | 验证能力 |
|------|------|----------|
| `/kit` | 显示帮助菜单 | 空路径匹配根命令本身 |
| `/kit give 264 64` | 获得 64 个对应物品 | 路径变量 `{item}` + `{count}` 自动转 int |
| `/kit give 264 abc` | 返回 `§c参数错误...` | 类型转换失败提示 |
| `/kit sword` | 获得闪电钻石剑 | 与事件模块工具类结合 |
| `/kit heal` | 生命值恢复至 20 | 命名参数默认值 |
| `/kit heal --amount 10` | 生命值恢复至 10 | 命名参数 `--amount` |
| `/kit fly --on` | 提示飞行已启用 | 布尔标记 |
| `/kit broadcast 你好 世界` | 全服广播 `[广播] ...` | 贪婪变量 `{*msg}` 捕获剩余参数 |
| `/kit whoami` | 显示名字/坐标/生命 | `@Sender Player`（仅玩家）+ 权限 |
| 控制台执行 `/kit whoami` | 返回 `§c该命令只能由玩家在游戏内执行` | `@Sender Player` 类型约束 |
| `/kit xyz`（未知子命令） | 返回 `§c未知的子命令...` | 路由未命中兜底 |

> **特异性路由**：输入 `/kit give ...` 时，`give {item} {count}`（静态段多）会优先于 `/kit`（根命令帮助）命中，因此不会误触发帮助。

---

## 五、两个示例的设计对比

| 维度 | PlayerStatWrapper（对象级） | ChatGuardWrapper（全局单例） |
|------|----------------------------|------------------------------|
| `@KeyExtractor` 返回 | `Player`（每玩家不同身份） | `Boolean.TRUE`（恒定身份） |
| `@InstanceProvider` | 从 `ConcurrentHashMap` 查/建 | 始终返回同一个 `INSTANCE` |
| 实例数量 | 每个在线玩家 1 个 | 全局 1 个 |
| 优先级 | 默认 NORMAL | HIGH |
| 是否独占 | 否（多处理器共存） | 是（拦截后阻止低优先级） |
| 条件判断 | SpEL `condition` | Java `filter` 方法 |
| 清理方式 | 退服时 `remove()` | 无需清理 |

---

## 六、备注

- **独立使用 Spring（不继承 JFrameMain）**：框架自带的 `JFrameMain` 主类构造器为 `private`，无法被插件继承。因此本示例的 `ExamplePlugin` 直接继承 `PluginBase`，用 `ClassPathXmlApplicationContext` 一次性加载 `event-spring.xml` / `form-spring.xml` / `thread-spring.xml` 三个模块的 Bean 定义。这是一种「脱离 `JFrameMain`、独立装配」的用法。
- **事件模块已无循环依赖**：早期版本 `EventService ↔ HandlerRegistry` 存在构造器循环依赖，Spring 无法加载。现已拆分为 `EventEngine`（内部 Nukkit 桥接，无依赖）→ `HandlerRegistry`（依赖 Engine）→ `EventService`（公开门面，依赖两者）的单向 DAG，三者均可构造器注入。详见根目录《建议的框架改进.txt》建议 6。
- **Nukkit 类加载器注意**：Spring 默认用「线程上下文类加载器」查找 `classpath:` 资源，而 Nukkit 主线程的上下文类加载器是服务器类加载器，看不到插件 jar 内的 `*-spring.xml`。`ExamplePlugin.onEnable` 在创建 Spring 上下文前后临时切换/还原了线程上下文类加载器（`getClass().getClassLoader()`）来规避此问题。详见根目录《建议的框架改进.txt》建议 4。
- 跨线程任务中只发送了简单文本消息。若需在异步线程里操作主线程 API（如修改方块、传送），应额外调度回主线程。
