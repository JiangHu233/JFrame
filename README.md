# JFrame

> 基于 **Nukkit**（我的世界基岩版服务端）+ **Spring** 的插件开发框架。
>
> 用「声明式注解 + 模块化前置插件」的方式，快速搭建结构清晰、可维护的服务端插件。

## 📑 导航

- [这是什么？](#-这是什么)
- [与传统 Nukkit 开发的对比](#️-与传统-nukkit-开发的对比)
- [技术栈](#-技术栈)
- [模块总览](#-模块总览)
- [快速开始](#-快速开始)
- [各模块速览](#-各模块速览)
- [构建与部署](#️-构建与部署)
- [目录结构](#-目录结构)
- [文档导航](#-文档导航)
- [已知限制与注意事项](#️-已知限制与注意事项)

---

## 📖 这是什么？

JFrame 是一套面向 Nukkit 插件开发的**模块化框架**。它把服务端插件开发中常见的能力——事件处理、命令路由、表单界面、箱子 GUI、异步任务、数据持久化、实体 AI——拆分成独立模块，用 Spring 容器统一装配。

框架自身以**前置插件**形式部署：[`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 在启动时创建完整的 Spring 容器并加载全部模块，业务插件只需在 `plugin.yml` 声明 `depend: [JFrame]`，即可共享容器、复用现成的各模块 API，无需各自打包框架代码。

核心亮点：

- **声明式事件系统**：用注解（[`@EventHandler`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventHandler.java) / [`@EventRoute`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventRoute.java) / [`@KeyExtractor`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/KeyExtractor.java) / [`@InstanceProvider`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/InstanceProvider.java)）声明事件处理器，框架自动完成类型过滤、身份提取、实例创建和优先级分发。
- **声明式命令路由**：把 Spring MVC 的 `@Controller` / `@RequestMapping` 体验搬到 Minecraft 命令处理——[`@CommandController`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandController.java) + [`@CommandMapping`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandMapping.java) 自动完成路径匹配、参数解析与类型转换，无需手写 `if/else if` 分支。
- **栈式表单导航**：基于 [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java) 的视图栈，原生支持「进入子菜单 / 返回上一级」。
- **组件化箱子 GUI**：[`jframe_inventory`](jframe_inventory) 提供声明式组件（Button / StorageBox / Filler / Panel）+ 自动布局 + 假方块延迟打开，并内置物品栏编解码（[`InventoryCodec`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/InventoryCodec.java)）。
- **注解驱动持久化**：[`@SaveField`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/annotation/SaveField.java) 标记字段，[`DataSaver`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) 一行完成对象 ↔ JSON 文件的序列化，支持别名、必需校验、字段级适配器、跨插件隔离。
- **实体 AI 能力**：[`jframe_ai`](jframe_ai) 提供 A\* / 贪心寻路、实体导航（追逐 / 续算 / 游荡）、视野感知、单实体与团队战术、战斗行为，可驱动任意 `cn.nukkit.entity.Entity`。
- **响应式纠缠值**：[`EntangledValue`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValue.java) 让多个值「纠缠」在一起，一处变化多处感知（如同一份血量同时驱动 HUD、Boss 血条、计分板）。
- **Spring IoC 装配**：各模块提供 `*SpringConfig`，由 [`MainSpringConfig`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 统一 `@Import` 全量装配；各 API 自动注册到 Nukkit `ServiceManager`，供其他插件发现。

---

## ⚖️ 与传统 Nukkit 开发的对比

| 维度 | 传统 Nukkit 插件开发 | JFrame |
|------|---------------------|--------|
| **事件处理** | `implements Listener` + `Map<Player, State>` 手动管理状态 | [`@EventHandler`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventHandler.java) + [`@KeyExtractor`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/KeyExtractor.java) 对象级路由，自动按身份分发 |
| **命令注册** | `onCommand` + `if/else if` 分支或 args 索引解析 | [`@CommandController`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandController.java) + [`@CommandMapping`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandMapping.java) 路径匹配 + 自动参数转换 |
| **表单界面** | `FormWindow` + `switch(id)` 魔法索引 | [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java) 视图栈 + 按钮对象回调 + 栈式导航 |
| **数据持久化** | 手写 JSON 读写 / Config 库逐字段 | [`@SaveField`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/annotation/SaveField.java) 注解标记，一行序列化 |
| **依赖注入** | 手动 `new` 或静态单例 | Spring IoC 容器统一装配，`@Autowired` 即用 |
| **模块复用** | 各插件各自实现/打包重复代码 | 前置插件共享容器，业务插件 `depend: [JFrame]` 直接复用 |
| **实体 AI** | 自行实现寻路与行为 | [`AiAPI`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/AiAPI.java) 提供寻路 / 导航 / 战术 / 战斗开箱即用 |

---

## 🧱 技术栈

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 24 | 编译目标版本 |
| Nukkit | MOT-SNAPSHOT | 基岩版服务端（`provided`） |
| Spring | 6.2.1 | IoC 容器（core / beans / context / expression） |
| Lombok | 1.18.42 | 样板代码消除（`provided`） |
| Gson | — | 数据模块 JSON 序列化（由服务端提供） |

---

## 📦 模块总览

```
JFrame (父 POM，聚合 11 个模块)
├── jframe_core        核心基础：PluginAware 绑定、响应式纠缠值、功能 Map
├── jframe_main        框架入口：JFrameMain 前置插件、MainSpringConfig 全量装配
├── jframe_event       事件系统：声明式注解、对象级路由、优先级独占
├── jframe_command     命令系统：6 注解声明式路由（类 Spring MVC）
├── jframe_form        表单/GUI：FormView 视图栈、ViewManager 单玩家管理
├── jframe_inventory   箱子界面：声明式组件 + 布局 + 物品编解码
├── jframe_data        数据持久化：@SaveField 注解驱动 JSON 序列化
├── jframe_thread      异步任务：命名线程池、串行队列
├── jframe_ai          实体 AI：寻路 / 导航 / 战术 / 战斗 / 视野感知
├── jframe_example     完整示例插件（依赖前置 JFrame，演示全模块）
└── jframe_title       标题模块（规划中）
```

| 模块 | 作用 | 关键类 / API |
|------|------|--------|
| [`jframe_core`](jframe_core) | 框架公共基础 | [`PluginAware`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java)、[`EntangledValue`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValue.java)、[`EntangledChannel`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledChannel.java)、[`LruCacheMap`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/map/LruCacheMap.java)、[`PlayerDataMap`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/map/PlayerDataMap.java) |
| [`jframe_main`](jframe_main) | 框架入口与模块装配 | [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)、[`MainSpringConfig`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java)、[`ConfigEnum`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java) |
| [`jframe_event`](jframe_event) | 声明式事件路由 | [`EventAPI`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventAPI.java)、[`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java)、[`HandlerRegistry`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/routing/HandlerRegistry.java) |
| [`jframe_command`](jframe_command) | 声明式命令路由 | [`CommandAPI`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/CommandAPI.java)、[`CommandEngine`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/CommandEngine.java)、[`CommandRegistry`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/routing/CommandRegistry.java) |
| [`jframe_form`](jframe_form) | 表单界面与栈式导航 | [`ViewAPI`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewAPI.java)、[`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java)、[`ViewManager`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewManager.java) |
| [`jframe_inventory`](jframe_inventory) | 箱子界面与物品编解码 | [`InventoryAPI`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/InventoryAPI.java)、[`InventoryView`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/view/InventoryView.java)、[`InventoryCodec`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/InventoryCodec.java) |
| [`jframe_data`](jframe_data) | 注解驱动 JSON 持久化 | [`DataSaver`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/DataSaver.java)、[`@SaveField`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/annotation/SaveField.java)、[`SaveFieldAdapter`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/adapter/SaveFieldAdapter.java) |
| [`jframe_thread`](jframe_thread) | 异步任务队列 | [`ThreadAPI`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadAPI.java) |
| [`jframe_ai`](jframe_ai) | 实体 AI 能力 | [`AiAPI`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/AiAPI.java)、[`PathFinder`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/pathfinding/PathFinder.java)、[`NavigatorManager`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/navigation/NavigatorManager.java) |
| [`jframe_example`](jframe_example) | 端到端示例 | [`ExamplePlugin`](jframe_example/src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) |
| [`jframe_title`](jframe_title) | 标题模块（规划中） | — |

### 模块依赖关系

```
                    jframe_core（基础）
                    ▲   ▲   ▲   ▲   ▲   ▲
                    │   │   │   │   │   │
  jframe_event  command │ form │ inventory │ thread │ data │ ai
                    │       │       │       │     │     │
                    └───────┴───────┴───┬───┴─────┴─────┘
                                        │
                            jframe_main（聚合入口 / 前置插件）
                                        │
                            jframe_example（示例，depend: JFrame）
```

---

## 🚀 快速开始

JFrame 采用**前置插件**模式：框架自身部署为一个 Nukkit 插件，业务插件通过 `depend` 依赖它，直接复用其已创建好的 Spring 容器与各模块 API。

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

把 `jframe_main.jar` 放入 Nukkit 服务端的 `plugins/` 目录。启动时 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 会自动创建 Spring 容器、加载全部模块、绑定插件实例，并把各 API 注册到 Nukkit `ServiceManager`。

### 2. 业务插件依赖 JFrame

业务插件的 `plugin.yml` 声明前置依赖：

```yaml
name: MyPlugin
depend: [JFrame]
```

`pom.xml` 中将 `jframe_main` 设为 `provided` 作用域（不重复打包框架类）：

```xml
<dependency>
    <groupId>io.github.JiangHu.jframe</groupId>
    <artifactId>jframe_main</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 3. 获取各模块 API 并使用

业务插件在 `onEnable()` 中获取前置插件单例，直接复用其门面方法拿到现成的 API：

```java
public class MyPlugin extends PluginBase {

    private EventAPI eventAPI;
    private CommandAPI commandAPI;
    private AiAPI aiAPI;

    @Override
    public void onEnable() {
        // 1. 获取前置插件 JFrame 的单例（其 onEnable 时已创建好 Spring 容器）
        Plugin jframePlugin = getServer().getPluginManager().getPlugin("JFrame");
        JFrameMain jframe = (JFrameMain) jframePlugin;

        // 2. 直接复用 JFrame 已装配好的各模块 API（无需自己创建容器）
        eventAPI = jframe.getEventAPI();
        commandAPI = jframe.getCommandAPI();
        aiAPI = jframe.getAiAPI();
        // 还有：getViewAPI() / getThreadAPI() / getInventoryAPI() / getDataSaver()

        // 3. 用本插件类加载器扫描自身 jar 内的处理器并注册到共享容器
        eventAPI.scan(getClass().getClassLoader(), "com.example.myplugin.wrapper");
        commandAPI.scan(getClass().getClassLoader(), "com.example.myplugin.command");
    }
}
```

> 💡 也可通过 Nukkit `ServiceManager` 获取 API（等价）：
> `getServer().getServiceManager().getProvider(EventAPI.class).getProvider()`。
> [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 在 [`onEnable()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:232) 时已自动注册全部模块服务。

### JFrameMain 门面方法一览

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| [`getEventAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:128) | [`EventAPI`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventAPI.java) | 事件模块 |
| [`getCommandAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:155) | [`CommandAPI`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/CommandAPI.java) | 命令模块 |
| [`getViewAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:137) | [`ViewAPI`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewAPI.java) | 表单模块 |
| [`getInventoryAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:164) | [`InventoryAPI`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/InventoryAPI.java) | 箱子界面模块 |
| [`getThreadAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:146) | [`ThreadAPI`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadAPI.java) | 异步任务模块 |
| [`getAiAPI()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:175) | [`AiAPI`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/AiAPI.java) | AI 模块 |
| [`getDataSaver()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:191) | [`DataSaver`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) | 数据持久化（业务插件应再调 `forPlugin(this)` 隔离目录） |

---

## 🧩 各模块速览

### jframe_core · 核心基础

- [`PluginAware`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java)：模块插件绑定接口。实现该接口的 Spring Bean 会在框架启动时由 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 自动调用 [`bindPlugin()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:110) 注入插件实例——这样需要 `Plugin` 才能注册监听 / 调度任务的 Bean（如 [`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java)）无需手动赋值。
- **响应式纠缠值**：[`EntangledValue`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValue.java) 灵感取自量子纠缠，多个值「纠缠」后任一处更新都会通知其余；[`EntangledChannel`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/reactive/EntangledChannel.java) 是协调中枢，支持成员角色（SOURCE/SINK/BOTH/MUTE）与通道级监听。适合「一处变化、多处感知」。
- **功能 Map**：[`LruCacheMap`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/map/LruCacheMap.java)（线程安全 LRU 缓存）、[`PlayerDataMap`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/map/PlayerDataMap.java)（以玩家为键、退出自动清理）。

> 📖 详细文档见 [`core/data/README.md`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/README.md)。

### jframe_main · 框架入口

- [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)：框架主类，继承 `PluginBase`，作为**前置插件**部署。构造时通过 [`MainSpringConfig`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 创建 `AnnotationConfigApplicationContext`，一次性 `@Import` 全部模块配置；[`onEnable()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:232) 时绑定插件并把各 API 注册到 `ServiceManager`。
- **类加载器修正**：创建容器期间临时切换线程上下文类加载器为插件类加载器，解决 Nukkit 插件隔离导致 Spring 找不到 `classpath:` 资源的经典问题。
- [`ConfigEnum`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java)：模块枚举（`CORE` / `THREAD` / `FORM` / `EVENT` / `COMMAND` / `INVENTORY` / `AI`），映射各模块的配置类。

### jframe_event · 事件系统

基于「**身份标识的对象处理器**」模型，通过注解声明处理器：

| 注解 | 作用 |
|------|------|
| [`@KeyExtractor`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/KeyExtractor.java) | 标记 static 方法为身份提取器（从事件提取身份标识） |
| [`@EventHandler`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventHandler.java) | 标记方法为处理器，声明优先级与独占 |
| [`@EventRoute`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventRoute.java) | 声明处理什么事件、什么条件下处理（SpEL `condition` 或 `filter`） |
| [`@InstanceProvider`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/InstanceProvider.java) | 标记 static 方法为实例工厂（自定义创建/查找逻辑） |
| [`@Wrapper`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/Wrapper.java) | 标记事件包装器类（包扫描入口） |

每个玩家/方块/实体拥有独立实例，只收到属于自己的事件；高优先级处理器可**独占**事件，阻止低优先级执行。[`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java) 以固定 `LOWEST` 优先级向 Nukkit 注册，确保所有事件只触发一次分发。

> 📖 详细文档见 [`event/README.md`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/README.md)，维护者文档见 [`DEVELOPER.md`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/DEVELOPER.md)。

### jframe_command · 命令系统

把 Spring MVC 的 `@Controller` / `@RequestMapping` / `@PathVariable` 体验搬到 Minecraft 命令处理，通过 **6 个注解**声明命令处理器：

| 注解 | 作用 |
|------|------|
| [`@CommandController`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandController.java) | 声明命令控制器，指定**根命令** |
| [`@CommandMapping`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandMapping.java) | 声明命令处理方法，指定**子路径**与权限 |
| [`@PathVariable`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/PathVariable.java) | 绑定**路径变量**（`{name}` / `{*msg}` 贪婪） |
| [`@CommandParam`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/CommandParam.java) | 绑定**命名参数**（`--key value` / `--key=value`） |
| [`@Sender`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/Sender.java) | 注入**命令发送者**（`Player` 类型强制仅玩家） |
| [`@RawArgs`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/annotation/RawArgs.java) | 注入**原始参数数组** |

一个根命令下可挂载任意子命令，框架自动完成路径匹配、特异性排序（静态段多的优先）、参数类型转换与 Nukkit 注册。

```java
@CommandController("guild")   // 根命令：/guild
public class GuildController {

    // /guild create 我的公会  →  name = "我的公会"
    @CommandMapping("create {name}")
    public void create(@Sender CommandSender sender,
                       @PathVariable("name") String name) {
        sender.sendMessage("§a已创建公会: " + name);
    }
}
```

> 📖 详细文档见 [`command/README.md`](jframe_command/src/main/java/io/github/JiangHu/jframe/command/README.md)。

### jframe_form · 表单界面

- [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java)：界面抽象基类。子类构建表单、处理点击；通过栈式导航支持「进入子菜单 / 返回上一级 / 同层替换」。
- [`ViewManager`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewManager.java)：为单个玩家维护视图栈，栈顶即当前界面，并系统性修复了 Nukkit 表单的多个已知 Bug（回调异常卡死、发送异常吞掉、formId=-1 静默失败、网络线程并发等）。
- [`ViewAPI`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewAPI.java)：全局入口，为玩家打开界面（自动创建管理器）。

> 📖 详细文档见 [`form/README.md`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/README.md)。

### jframe_inventory · 箱子界面

- **声明式组件**：[`Button`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/component/Button.java) / [`StorageBox`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/component/StorageBox.java) / [`Filler`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/component/Filler.java) / [`Panel`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/component/Panel.java) 等，配合布局（Border / Grid / Manual）自动渲染。
- [`InventoryView`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/view/InventoryView.java)：箱子视图基类，支持 SlotType 交易控制（BUTTON / DISPLAY / LOCKED / STORAGE）与假方块延迟打开。
- [`InventoryAPI`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/InventoryAPI.java)：全局入口，[`openView()`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/InventoryAPI.java) 为玩家打开界面。
- **物品编解码**：[`InventoryCodec`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/InventoryCodec.java) / [`ItemCodec`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/ItemCodec.java) 将物品栏与物品在 NBT / JSON 间互转，便于持久化。

> 📖 详细文档见 [`inventory/ui/README.md`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/README.md) 与 [`codec/README.md`](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/README.md)。

### jframe_data · 数据持久化

通过 [`@SaveField`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/annotation/SaveField.java) 注解标记需保存的字段，[`DataSaver`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) 将对象序列化为 JSON 文件或反向加载：

```java
public class PlayerData {
    @SaveField(value = "player_name", required = true)
    private String name;
    @SaveField
    private int level;
}

// 保存 → rootDir/players/steve.json
saver.save(data, "players/steve");
// 加载
PlayerData loaded = saver.load(PlayerData.class, "players/steve");
```

支持别名、必需校验、容器与嵌套对象递归、字段级自定义适配器（[`SaveFieldAdapter`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/adapter/SaveFieldAdapter.java)）、子路径导航（`sub` / `parent` / `root`）、跨插件隔离（`forPlugin`）、加载或新建（`loadOrSave`）。

> 📖 详细文档见 [`data/README.md`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/README.md)。

### jframe_thread · 异步任务

- [`ThreadAPI`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadAPI.java)：基于「命名线程池」的异步任务管理。每个队列内部为单线程执行器，保证同队列任务串行执行；工作线程命名为 `jframe-thread-{name}-{n}` 且为守护线程，便于调试、不阻塞 JVM 退出。支持按名创建 / 移除队列、全局异常处理器。

### jframe_ai · 实体 AI

为 Nukkit MOT 服务端上的实体提供开箱即用的 AI 能力，自行实现面向方块世界的寻路与行为层（不依赖内置实体 AI），可驱动任意 `Entity`：

- **寻路**：A\*（[`PathFinder`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/pathfinding/PathFinder.java)，全局最优）与贪心（[`GreedyPathFinder`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/pathfinding/GreedyPathFinder.java)，低开销）双策略，支持外接评分器（[`StepCostFunction`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/pathfinding/StepCostFunction.java)）。
- **导航**：实体路径跟随、多实体调度（[`NavigatorManager`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/navigation/NavigatorManager.java)）、追逐移动目标（[`AnytimePathFinder`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/navigation/AnytimePathFinder.java)）、走完续算（[`ContinuousNavigator`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/navigation/ContinuousNavigator.java)）、游荡（[`WanderBehavior`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/navigation/WanderBehavior.java)）。
- **战术**：找掩体 / 远离 / 包抄 / 寻找高地 / 占据视野点，以及团队协同（包抄 / 包围 / 集结阵型）。
- **战斗**：近战攻击 / 射箭 / 投掷抛射物 / 使用物品。
- **感知**：[`VisionSensor`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/util/VisionSensor.java) 综合距离 + FOV 视野角度 + 视线遮挡判断。

```java
AiAPI ai = jframeMain.getAiAPI();
ai.navigateTo(zombie, player);   // 三行代码让实体走向目标
```

> 📖 详细文档见 [`ai/README.md`](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/README.md)。

### jframe_example · 示例插件

一个完整的 Nukkit 插件，演示「依赖前置 JFrame」的用法，覆盖事件（对象级路由）、命令（`/ai` AI 测试）、表单（`/jframe` 菜单）、箱子界面（`/shop` / `/invtest`）、线程（异步任务）等模块的协同使用。

> 📖 详细说明见 [`ExamplePlugin`](jframe_example/src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) 源码注释。

---

## 🛠️ 构建与部署

### 构建前置插件与示例

```bash
# 1. 在根目录安装全部模块到本地仓库
mvn clean install -DskipTests

# 2. 打包前置插件
cd jframe_main && mvn clean package
# 产物：jframe_main/target/jframe_main.jar

# 3. 打包示例插件（含 shade 打 fat jar）
cd ../jframe_example && mvn clean package
# 产物：jframe_example/target/jframe_example.jar
```

### 部署

把 `jframe_main.jar` 与业务插件 jar 放入 Nukkit 服务端的 `plugins/` 目录（**前置插件必须先存在**），重启服务器即可。示例插件提供 `/jframe`（菜单）、`/shop`（商店箱子）、`/invtest`（箱子全特性测试）、`/ai`（AI 测试）、`/sword`（闪电剑）等命令。

---

## 📂 目录结构

```
JFrame/
├── pom.xml                          # 父 POM（聚合 11 个模块）
├── README.md                        # 本文件
├── CODE_REVIEW.md                   # 全模块代码审查报告
├── plans/                           # 设计 / 重构方案文档
├── jframe_core/                     # 核心基础（PluginAware、纠缠值、功能 Map）
├── jframe_main/                     # 框架入口（前置插件）
├── jframe_event/                    # 事件系统
├── jframe_command/                  # 命令系统
├── jframe_form/                     # 表单 / GUI
├── jframe_inventory/                # 箱子界面 + 物品编解码
├── jframe_data/                     # 数据持久化
├── jframe_thread/                   # 异步任务
├── jframe_ai/                       # 实体 AI
├── jframe_example/                  # 示例插件
└── jframe_title/                    # 标题模块（规划中）
```

---

## 📚 文档导航

| 文档 | 内容 |
|------|------|
| [事件系统 README](jframe_event/src/main/java/io/github/JiangHu/jframe/event/README.md) | 事件模块完整用法、注解详解、用法示例 |
| [事件系统 DEVELOPER](jframe_event/src/main/java/io/github/JiangHu/jframe/event/DEVELOPER.md) | 事件模块内部实现与维护者文档 |
| [命令系统 README](jframe_command/src/main/java/io/github/JiangHu/jframe/command/README.md) | 命令模块 6 注解用法、路径匹配、参数绑定 |
| [表单 README](jframe_form/src/main/java/io/github/JiangHu/jframe/form/README.md) | 表单视图栈、导航、元素 |
| [箱子界面 README](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/ui/README.md) | 声明式组件、布局、SlotType |
| [物品编解码 README](jframe_inventory/src/main/java/io/github/JiangHu/jframe/inventory/codec/README.md) | 物品栏 / 物品 NBT ↔ JSON 互转 |
| [数据持久化 README](jframe_data/src/main/java/io/github/JiangHu/jframe/data/README.md) | `@SaveField` 注解、DataSaver 全 API |
| [AI README](jframe_ai/src/main/java/io/github/JiangHu/jframe/ai/README.md) | 寻路 / 导航 / 战术 / 战斗 / 感知 |
| [core 数据类型 README](jframe_core/src/main/java/io/github/JiangHu/jframe/core/data/README.md) | 响应式纠缠值、功能 Map |
| [CODE_REVIEW.md](CODE_REVIEW.md) | 全模块代码审查报告（问题分级与修复记录） |

---

## ⚠️ 已知限制与注意事项

- **Nukkit 类加载器隔离**：Spring 默认用线程上下文类加载器查找 `classpath:` 资源，而 Nukkit 主线程的上下文类加载器看不到插件 jar 内部资源。[`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 创建容器时已自动处理（临时切换为插件类加载器并 try/finally 恢复）；若业务插件自行创建 Spring 上下文，需同样处理。
- **数据写入非原子**：[`DataSaver`](jframe_data/src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) 的文件写入若进程崩溃可能产生半截损坏文件，关键数据建议配合备份（详见 [CODE_REVIEW.md](CODE_REVIEW.md) 问题 #1）。
- **跨插件数据隔离**：业务插件通过 [`getDataSaver()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:191) 获取的保存器默认指向 JFrame 目录，应再调用 `forPlugin(this)` 得到以自身数据目录为根的独立保存器，避免污染 JFrame 目录。
- **跨线程操作主线程 API**：[`ThreadAPI`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadAPI.java) 的任务运行在异步线程，若需修改方块、传送等主线程操作，应额外调度回主线程。
- **`jframe_title` 尚未实现**：该模块目前为空占位，处于规划阶段。
