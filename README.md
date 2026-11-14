# JFrame

> 基于 **Nukkit**（我的世界基岩版服务端）的插件开发框架，自研轻量模块容器（无 Spring IoC）。
>
> 用「声明式注解 + 模块化前置插件 + 按需加载」的方式，快速搭建结构清晰、可维护的服务端插件。

## 📑 导航

- [这是什么？](#-这是什么)
- [与传统 Nukkit 开发的对比](#️-与传统-nukkit-开发的对比)
- [技术栈](#-技术栈)
- [模块总览](#-模块总览)
- [快速开始](#-快速开始)
- [业务插件自建模块容器](#-业务插件自建模块容器)
- [构建与部署](#️-构建与部署)
- [已知限制与注意事项](#️-已知限制与注意事项)

---

## 📖 这是什么？

JFrame 是一套面向 Nukkit 插件开发的**模块化框架**。它把服务端插件开发中常见的能力——事件处理、命令路由、表单界面、箱子 GUI、异步任务、数据持久化、实体 AI、ECS 引擎、行为树、浮空字——拆分成独立模块，由自研的**模块容器** [`ModuleManager`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/ModuleManager.java) 统一管理。

框架自身以**前置插件**形式部署：[`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 启动时登记全部内置模块（零实例化），业务插件只需在 `plugin.yml` 声明 `depend: [JFrame]`，首次调用 `getXxxAPI()` 时对应模块才真正初始化——**未用到的模块不创建任何对象，启动零成本**。

核心亮点：

- **自研模块容器（替代 Spring IoC）**：一个模块 = 一个工厂类（[`JFrameModule`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/JFrameModule.java)）= 一组懒加载单例；对象间引用在工厂内用普通 Java 组装，零反射注入。支持无参 new / 工厂函数 / 工厂类 / 现成实例四种注册形式与 `@JBean` / `@JFactory` / `@JFactoryClass` 注解包扫描。
- **声明式事件系统**：用注解（`@EventHandler` / `@EventRoute` / `@KeyExtractor` / `@InstanceProvider`）声明事件处理器，框架自动完成类型过滤、身份提取、实例创建和优先级分发。
- **声明式命令路由**：把 Spring MVC 的 `@Controller` / `@RequestMapping` 体验搬到 Minecraft 命令处理——`@CommandController` + `@CommandMapping` 自动完成路径匹配、参数解析与类型转换。
- **ECS 游戏引擎 + 行为树**：YAML 数据驱动的 GameObject 定义、五类注解扫描注册、四级过滤事件分发、组件对象池；与 ECS 共享引擎的行为树决策子系统（`BtAPI`）。
- **栈式表单导航 / 组件化箱子 GUI**：`FormView` 视图栈与声明式箱子组件 + 自动布局 + 物品编解码。
- **注解驱动持久化**：`@SaveField` 标记字段，`DataSaver` 一行完成对象 ↔ JSON 序列化。
- **实体 AI 能力**：A\* / 贪心双策略寻路与七工厂行为 API（`path` / `walk` / `chase` / `wander` / `loop` / `attack` / `see`），另有单实体与团队战术。
- **浮空字**：发包假实体渲染（零服务器实体）、瞬态与常驻组、观众重连对账。
- **响应式纠缠值**：[`EntangledValue`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValue.java) 让多个值「纠缠」在一起，一处变化多处感知。

---

## ⚖️ 与传统 Nukkit 开发的对比

| 维度 | 传统 Nukkit 插件开发 | JFrame |
|------|---------------------|--------|
| **事件处理** | `implements Listener` + `Map<Player, State>` 手动管理状态 | `@EventHandler` + `@KeyExtractor` 对象级路由，自动按身份分发 |
| **命令注册** | `onCommand` + `if/else if` 分支或 args 索引解析 | `@CommandController` + `@CommandMapping` 路径匹配 + 自动参数转换 |
| **表单界面** | `FormWindow` + `switch(id)` 魔法索引 | `FormView` 视图栈 + 按钮对象回调 + 栈式导航 |
| **数据持久化** | 手写 JSON 读写 / Config 库逐字段 | `@SaveField` 注解标记，一行序列化 |
| **模块管理** | 手动 `new` 或静态单例，全部启动即初始化 | 自研模块容器统一装配，**按需懒加载**，关服按序清理 |
| **模块复用** | 各插件各自实现/打包重复代码 | 前置插件共享容器，业务插件 `depend: [JFrame]` 直接复用 |
| **实体 AI / ECS** | 自行实现 | `AiAPI` / `EcsAPI` / `BtAPI` 开箱即用 |

---

## 🧱 技术栈

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 24 | 编译目标版本 |
| Nukkit | MOT-SNAPSHOT | 基岩版服务端（`provided`） |
| spring-core / spring-expression | 6.2.1 | **纯工具库**（Resource 抽象 + SpEL，供 ECS `when` 条件与模板渲染）；IoC 容器已由自研 `ModuleManager` 取代 |
| Lombok | 1.18.42 | 样板代码消除（`provided`） |
| Gson | — | 数据模块 JSON 序列化（由服务端提供） |
| JUnit 5 | 5.11.4 | 单元测试（`test`） |

---

## 📦 模块总览

```
JFrame (父 POM，聚合 17 个模块)
├── jframe_core                        核心基础：模块容器（ModuleManager）、注解扫描器、PluginAware、纠缠值
├── jframe_main                        框架入口：JFrameMain 前置插件、ConfigEnum、config.yml 预加载
├── jframe_listener                    事件系统：声明式注解、对象级路由、优先级独占
├── jframe_command                     命令系统：6 注解声明式路由（类 Spring MVC）
├── jframe_form                        表单/GUI：FormView 视图栈、ViewManager 单玩家管理
├── jframe_inventory                   箱子界面：声明式组件 + 布局 + 物品编解码
├── jframe_data                        数据持久化：@SaveField 注解驱动 JSON 序列化
├── jframe_async                       异步任务：ThreadAPI / TaskAPI / ThreadFlow / ReleaseAPI
├── jframe_ai                          实体 AI：寻路 / 导航 / 战术 / 战斗 / 视野感知
├── jframe_template                    内容模板：XML 模板 + SpEL 渲染（计分板/标题共用）
├── jframe_scoreboard_depend_template  计分板：模板驱动动态计分板
├── jframe_title_depend_template       标题：Title/Subtitle/ActionBar 模板驱动
├── jframe_engine                      游戏引擎：ECS（注册表/引擎/调度/事件桥/对象池）+ 行为树
├── jframe_floatingtext                浮空字：发包假实体渲染、瞬态/常驻组
├── jframe_nbt                         NBT 编解码库（被动库，未接入模块体系）
├── jframe_logger                      统一日志：LoggerAPI 统一入口 + /jlog 手动启停 Web 展示（多实例聚合）
└── jframe_example                     ECS / 统一日志示例插件（依赖前置 JFrame）
```

| 模块 id | 提供的公开服务（懒加载触发点） |
|---------|------------------------------|
| `event` | `EventAPI` |
| `command` | `CommandAPI` |
| `async` | `ThreadAPI` / `TaskAPI` / `ThreadFlow` / `ReleaseAPI` |
| `form` | `ViewAPI` |
| `inventory` | `InventoryAPI` |
| `data` | `DataSaver` |
| `ai` | `AiAPI` |
| `scoreboard` | `ScoreboardAPI` |
| `title` | `TitleAPI` |
| `engine` | `EcsAPI` / `BtAPI`（共享 EcsEngine，一个模块两个服务） |
| `floatingtext` | `FloatingTextAPI` |
| `logger` | `LoggerAPI`（统一日志入口；`/jlog` 指令首次执行亦触发加载） |

每个模块由一个 `XxxModule` 工厂类一对一替代原 Spring `*SpringConfig` + `*-spring.xml`（如 [`EventModule`](jframe_listener/src/main/java/io/github/JiangHu/jframe/event/EventModule.java)、[`EngineModule`](jframe_engine/src/main/java/io/github/JiangHu/jframe/engine/EngineModule.java)），对象图在 `load(ModuleContext)` 内用普通 Java 组装。

---

## 🚀 快速开始

JFrame 采用**前置插件**模式：框架自身部署为一个 Nukkit 插件，业务插件通过 `depend` 依赖它，复用其模块容器与各模块 API。

### 1. 构建并部署前置插件

在**项目根目录**执行，把各模块安装到本地 Maven 仓库并打包主插件：

```bash
# 安装全部模块到本地仓库
mvn clean install -DskipTests

# 打包框架主插件（前置插件）
cd jframe_main
mvn clean package
# 产物：jframe_main/target/jframe_main.jar
```

把 `jframe_main.jar` 放入 Nukkit 服务端的 `plugins/` 目录。启动时 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 登记全部模块并读取 `config.yml` 的 `modules:` 预加载列表（默认为空 = 全部懒加载）。

### 2. 业务插件依赖 JFrame

业务插件的 `plugin.yml` 声明前置依赖：

```yaml
name: MyPlugin
depend: [JFrame]
```

`pom.xml` 中将 `jframe_main` 设为 `provided` 作用域（不重复打包框架类）。

### 3. 获取各模块 API 并使用

推荐用**门面方法**（首次调用触发对应模块懒加载）：

```java
public class MyPlugin extends PluginBase {

    private EventAPI eventAPI;
    private CommandAPI commandAPI;

    @Override
    public void onEnable() {
        // 1. 获取前置插件 JFrame 的单例
        JFrameMain jframe = (JFrameMain) getServer().getPluginManager().getPlugin("JFrame");

        // 2. 门面取 API——首次调用才初始化对应模块
        eventAPI = jframe.getEventAPI();
        commandAPI = jframe.getCommandAPI();
        // 还有：getViewAPI / getThreadAPI / getTaskAPI / getThreadFlow / getReleaseAPI
        //       getInventoryAPI / getDataSaver / getAiAPI / getScoreboardAPI / getTitleAPI
        //       getEcsAPI / getBtAPI / getFloatingTextAPI / getLoggerAPI
        // 或静态：JFrameMain.getApi(EventAPI.class)

        // 3. 用本插件类加载器扫描自身 jar 内的处理器并注册
        eventAPI.forPlugin(this).scan("com.example.myplugin.wrapper");
        commandAPI.forPlugin(this).scan("com.example.myplugin.command");
    }
}
```

> 💡 Nukkit `ServiceManager` 也可获取 API，但服务在模块**真实加载后**才注册
> （`getServer().getServiceManager().getProvider(EventAPI.class).getProvider()`）。
> 未加载模块返回 `null`；若其他插件需要即时可查，可在 JFrame 的 `config.yml`
> 中配置 `modules: [event, command]` 预加载。

### JFrameMain 门面方法一览

| 方法 | 返回类型 | 模块 |
|------|----------|------|
| `getEventAPI()` | `EventAPI` | 事件 |
| `getCommandAPI()` | `CommandAPI` | 命令 |
| `getViewAPI()` | `ViewAPI` | 表单 |
| `getThreadAPI()` / `getTaskAPI()` / `getThreadFlow()` / `getReleaseAPI()` | — | 异步 |
| `getInventoryAPI()` | `InventoryAPI` | 箱子界面 |
| `getDataSaver()` | `DataSaver` | 数据持久化（业务插件应再调 `forPlugin(this)` 隔离目录） |
| `getAiAPI()` | `AiAPI` | 实体 AI |
| `getScoreboardAPI()` | `ScoreboardAPI` | 计分板 |
| `getTitleAPI()` | `TitleAPI` | 标题 |
| `getEcsAPI()` / `getBtAPI()` | `EcsAPI` / `BtAPI` | 游戏引擎 |
| `getFloatingTextAPI()` | `FloatingTextAPI` | 浮空字 |
| `getLoggerAPI()` | `LoggerAPI` | 统一日志（`forPlugin(this)` 取静默日志器） |

---

## 📝 统一日志（logger 模块）

所有依赖插件的日志不再打进 Nukkit 控制台（MCSM 面板），而是写入统一日志库，按需用 **手动指令** 开启 Web 端口查看，不用时零资源占用：

```java
public class MyPlugin extends PluginBase {
    @Override
    public void onEnable() {
        JFrameMain jframe = (JFrameMain) getServer().getPluginManager().getPlugin("JFrame");
        JLogger log = jframe.getLoggerAPI().forPlugin(this);

        log.info("玩家进入经济系统");                       // 默认静默：不进 MCSM，仅入统一日志库
        log.error("扣款失败", e);                           // 异常堆栈随条目保存，Web 端可折叠查看
        log.setConsoleMirror(true);                         // 可选：镜像回控制台
    }
}
```

Web 展示端口由 `/jlog` 手动启停（op 权限；首次执行即懒加载 logger 模块）：

| 命令 | 行为 |
|------|------|
| `/jlog start` | 探测 config 端口（默认 18080）：空闲则本机监听；被本机其它 JFrame logger 占用则**自动挂靠**（不重复开端） |
| `/jlog start <端口>` | 指定端口启动（占用/冲突回报原因） |
| `/jlog start auto` | 先探测 config 端口；否则由 OS 原子分配未占用端口 |
| `/jlog stop` / `/jlog status` | 停止释放 / 查看角色与状态 |

浏览器访问：**根路由 `/` 汇总全部子服日志**（按时间交错、子服着色），**`/s/<server-id>` 分面板单独审验**；支持来源/级别/关键字筛选与 SSE 实时推送。

**同 IP 多面板**（一台机器多个 Nukkit 实例）：各实例 `/jlog start` 同端口时自动形成 host/agent——先到者监听，后来者挂靠推送，host 死亡后挂靠实例自动接管。子服标识默认 `server-<本服端口>`，可用 `config.yml` 的 `logger.web.server-id` 覆盖；可选 `persist: true` 按天/按插件落盘（默认关）、`token` 简单校验。

---

## 🧩 业务插件自建模块容器

`ModuleManager` 不是 JFrame 专属单例——任何插件都可以 `new ModuleManager(this)` 得到一份**独立注册表**（与 JFrame 及其他插件的容器互不相通），享受与内置模块同等的懒加载、`PluginAware` 绑定、以宿主名义注册 ServiceManager、按序关闭机制：

```java
public class MyPlugin extends PluginBase {

    private ModuleManager container;

    @Override
    public void onEnable() {
        container = new ModuleManager(this);   // 本插件自己的容器

        // 形式 1：无工厂 —— 首次 get 时无参 new（私有 Bean）
        container.register(ConfigService.class);

        // 形式 1b：单类型工厂函数
        container.registerFactory(DbService.class, () -> new DbService(loadConfig()));

        // 形式 2：工厂类 —— 一次产出多个对象，引用在工厂内解决
        container.register("economy", Set.of(Economy.class, Bank.class), ctx -> {
            Economy eco = new Economy();
            ctx.bind(eco);                                    // PluginAware 内部 Bean
            ctx.register(Bank.class, new Bank(eco));          // 公开服务
            ctx.register(Economy.class, eco);
        });

        // 注解 + 包扫描（@JBean 产品类 / @JFactoryClass 工厂类）
        container.forPlugin(this).scan("com.example.myplugin.beans");

        // 懒加载取用：Bank 持有的 Economy 与 get(Economy.class) 是同一单例
        Bank bank = container.get(Bank.class);
    }

    @Override
    public void onDisable() {
        container.shutdown();   // 按模块 shutdownOrder 升序清理
    }
}
```

注解声明层两种形态：

```java
// 形态 A：产品类 + 可选 @JFactory 工厂方法
@JBean
public class Sword {
    @JFactory
    public static Sword create() { return new Sword(loadConfig()); }
}

// 形态 B：工厂类 + 多产品方法（B 持有 A 经 ctx.get 显式解决，同一单例）
@JFactoryClass
public class EconomyFactory {
    @JBean public Economy economy() { return new Economy(); }
    @JBean public Bank bank(ModuleContext ctx) { return new Bank(ctx.get(Economy.class)); }
}
```

语义约定：直接注册与注解 Bean 默认**私有**（不进 ServiceManager）；工厂类 `ctx.register` 的产物为**公开服务**；`@JBean(service = true)` 可显式公开。所有歧义（多个 `@JFactory`、`@JBean` 方法 void/参数不合法、类型重复注册、循环依赖等）在注册期 fail-fast 抛出。

---

## 🛠️ 构建与部署

```bash
# 1. 在根目录安装全部模块到本地仓库
mvn clean install -DskipTests

# 2. 打包前置插件
cd jframe_main && mvn clean package
# 产物：jframe_main/target/jframe_main.jar

# 3. 打包示例插件
cd ../jframe_example && mvn clean package
# 产物：jframe_example/target/jframe_example.jar
```

把 `jframe_main.jar` 与业务插件 jar 放入 Nukkit 服务端的 `plugins/` 目录（**前置插件必须先存在**），重启服务器即可。示例插件提供 `/ecs`（ECS 引擎实机测试）与 `/logdemo`（统一日志演示，配合 `/jlog start` 网页查看）等命令。

---

## ⚠️ 已知限制与注意事项

- **模块懒加载**：未用到的模块首次调用对应 `getXxxAPI()` 前不初始化，`ServiceManager.getProvider(XxxAPI.class)` 对未加载模块返回 `null`。需要启动即可用的模块请写入 JFrame `config.yml` 的 `modules:` 预加载列表。
- **logger Web 端口手动启停**：`/jlog start` 开启、`/jlog stop` 关闭；模块加载与服务器启动均**不**自动开启端口（不用时无监听、无线程）。agent 挂靠/接管仅限同机回环，跨机器聚合需自行反代。
- **主线程约定**：模块 API 与容器 `get()` 约定在 Nukkit 主线程调用（内部有并发防御，但跨线程懒加载初始化监听器等副作用应由调用方保证时序）。
- **数据写入非原子**：`DataSaver` 的文件写入若进程崩溃可能产生半截损坏文件（写入非「临时文件 + 原子替换」模式），关键数据建议配合定期备份。
- **跨插件数据隔离**：业务插件通过 `getDataSaver()` 获取的保存器默认指向 JFrame 目录，应再调用 `forPlugin(this)` 得到以自身数据目录为根的独立保存器。
- **跨线程操作主线程 API**：`ThreadAPI` 的任务运行在异步线程，若需修改方块、传送等主线程操作，应额外调度回主线程。
- **SpEL 保留依赖**：ECS `when` 条件与计分板/标题模板渲染仍使用 `spring-expression`（纯表达式求值库，无容器语义）；事件 `@EventRoute.condition` SpEL 参数已移除，条件过滤统一走 `filter` 方法。
- **`jframe_nbt`**：被动 NBT 编解码库，未注册进模块体系（维持历史行为）。
