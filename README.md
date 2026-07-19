# JFrame

> 基于 **Nukkit**（我的世界基岩版服务端）+ **Spring** 的插件开发框架。
>
> 用「声明式注解 + 按需导入模块」的方式，快速搭建结构清晰、可维护的服务端插件。

---

## 📖 这是什么？

JFrame 是一套面向 Nukkit 插件开发的**模块化框架**。它把服务端插件开发中常见的能力——事件处理、表单界面、异步任务——拆分成独立模块，用 Spring 容器统一装配，让你**只引入需要的能力**，并通过少量注解完成大部分样板代码。

核心亮点：

- **声明式事件系统**：用 4 个注解（[`@EventHandler`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventHandler.java) / [`@EventRoute`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventRoute.java) / [`@KeyExtractor`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/KeyExtractor.java) / [`@InstanceProvider`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/InstanceProvider.java)）声明事件处理器，框架自动完成类型过滤、身份提取、实例创建和优先级分发。
- **对象级事件路由**：每个玩家/方块/实体/物品拥有独立实例，只收到属于自己的事件；高优先级处理器可**独占**事件，阻止低优先级处理器执行。
- **栈式表单导航**：基于 [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java) 的视图栈，原生支持「进入子菜单 / 返回上一级」。
- **按需动态加载**：通过 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 的 [`satisfyRequired()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:53) 按需导入模块，并自动把插件实例注入到需要的 Bean。
- **Spring IoC 装配**：各模块提供 `*-spring.xml` 与 `*SpringConfig`，可独立装配，也可统一托管。

---

## 🧱 技术栈

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 24 | 编译目标版本 |
| Nukkit | MOT-SNAPSHOT | 基岩版服务端（`provided`） |
| Spring | 6.2.1 | IoC 容器（core / beans / context / expression） |
| Lombok | 1.18.42 | 样板代码消除（`provided`） |
| moe.him188:GUI | 1.15.1 | 表单库（`system` 作用域，由服务端/单独插件提供） |

---

## 📦 模块总览

```
JFrame (父 POM)
├── jframe_core       核心基础：PluginAware 绑定接口、数据标记注解
├── jframe_main       框架入口：JFrameMain 主类、动态模块加载、ConfigEnum
├── jframe_event      事件系统：4 注解声明式、对象级路由、优先级独占
├── jframe_form       表单/GUI：FormView 视图栈、ViewService 单玩家管理
├── jframe_thread     异步任务：命名线程池、串行队列
└── jframe_example    完整示例插件（事件/表单/线程三合一）
```

| 模块 | 作用 | 关键类 |
|------|------|--------|
| [`jframe_core`](jframe_core) | 框架公共基础 | [`PluginAware`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java)、[`@RuntimeData`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/annotation/RuntimeData.java)、[`@SerializableData`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/annotation/SerializableData.java) |
| [`jframe_main`](jframe_main) | 框架入口与模块装配 | [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)、[`ConfigEnum`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java) |
| [`jframe_event`](jframe_event) | 声明式事件路由 | [`EventService`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventService.java)、[`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java)、[`HandlerRegistry`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/routing/HandlerRegistry.java) |
| [`jframe_form`](jframe_form) | 表单界面与栈式导航 | [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java)、[`ViewService`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewService.java)、[`SinglePlayerViewManager`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/SinglePlayerViewManager.java) |
| [`jframe_thread`](jframe_thread) | 异步任务队列 | [`ThreadService`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadService.java) |
| [`jframe_example`](jframe_example) | 端到端示例 | [`ExamplePlugin`](jframe_example/src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java) |

### 模块依赖关系

```
        jframe_core（基础）
        ▲   ▲   ▲   ▲
        │   │   │   │
  jframe_event │ jframe_form │ jframe_thread
        │       │       │
        └───────┼───────┘
                │
           jframe_main（聚合入口）
                │
           jframe_example（示例）
```

---

## 🚀 快速开始

### 1. 安装框架到本地仓库

在**项目根目录**执行，把各模块安装到本地 Maven 仓库：

```bash
mvn clean install -DskipTests
```

### 2. 两种使用方式

#### 方式 A：继承 `JFrameMain`（推荐，按需导入模块）

```java
public class MyPlugin extends JFrameMain {

    @Override
    public void onEnable() {
        // 按需导入 event / form / thread 模块，框架自动装配并绑定插件实例
        satisfyRequired(ConfigEnum.EVENT, ConfigEnum.FORM, ConfigEnum.THREAD);

        EventService eventAPI = getApplicationContext().getBean(EventService.class);
        eventAPI.scan("com.example.myplugin.wrapper"); // 包扫描注册事件处理器
    }
}
```

> ⚠️ 当前 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 的构造器与 [`bindPlugin()`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:75) 为 `private`，暂无法直接继承。详见 [《建议的框架改进.txt》](建议的框架改进.txt) 建议 1。在此之前可使用方式 B。

#### 方式 B：独立装配 Spring（不依赖 `JFrameMain`）

直接继承 `PluginBase`，用 `ClassPathXmlApplicationContext` 加载各模块的 `*-spring.xml`：

```java
public class MyPlugin extends PluginBase {

    @Override
    public void onEnable() {
        // 【关键】切换线程上下文类加载器，确保 Spring 能找到 jar 内的 *-spring.xml
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            var ctx = new ClassPathXmlApplicationContext(
                    "event-spring.xml", "form-spring.xml", "thread-spring.xml");

            EventService eventAPI = ctx.getBean("eventAPI", EventService.class);
            ViewService viewService    = ctx.getBean("viewService", ViewService.class);
            ThreadService threadAPI = ctx.getBean("threadAPI", ThreadService.class);

            // 绑定插件实例到所有 PluginAware Bean（如 EventEngine）
            for (PluginAware aware : ctx.getBeansOfType(PluginAware.class).values()) {
                aware.bindPlugin(this);
            }

            eventAPI.scan("com.example.myplugin.wrapper");
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }
}
```

> 完整可运行示例见 [`jframe_example`](jframe_example/src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java)。

### 3. 声明一个事件处理器

```java
public class PlayerWrapper {

    private final Player player;

    public PlayerWrapper(Player player) { this.player = player; }

    // ① 身份提取器：从事件提取 Player（必需）
    @KeyExtractor
    public static Player extractPlayer(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    // ② 只会收到【这个玩家】的移动事件
    @EventHandler
    @EventRoute
    public void onMove(PlayerMoveEvent event) {
        player.sendMessage("你走到了 " + event.getTo());
    }
}
```

注册后，玩家 A 移动 → 提取 `playerA` → 自动创建并缓存 `PlayerWrapper(playerA)` → 调用 `onMove`；玩家 B 移动同理，互不影响。

---

## 🧩 各模块速览

### jframe_core · 核心基础

- [`PluginAware`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java)：模块插件绑定接口。实现该接口的 Spring Bean 会在模块导入时由 [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 自动调用 [`bindPlugin()`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java:58) 注入插件实例——这样需要 `Plugin` 才能注册事件监听的 Bean（如 [`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java)）无需手动赋值。
- [`@RuntimeData`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/annotation/RuntimeData.java) / [`@SerializableData`](jframe_core/src/main/java/io/github/JiangHu/jframe/core/annotation/SerializableData.java)：数据标记注解，区分「运行时临时数据」与「需持久化数据」，供注解处理器使用。

### jframe_main · 框架入口

- [`JFrameMain`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)：框架主类，继承 `PluginBase`。构造时加载核心模块，通过 [`satisfyRequired(ConfigEnum...)`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:53) 动态注册其他模块的 `SpringConfig` 并刷新容器，随后自动绑定插件。
- [`ConfigEnum`](jframe_main/src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java)：模块枚举（`CORE` / `THREAD` / `FORM` / `EVENT`），每个值映射到对应模块的配置类。

### jframe_event · 事件系统（核心特性）

基于「**身份标识的对象处理器**」模型，通过 4 个注解声明处理器：

| 注解 | 作用 | 必填 |
|------|------|------|
| [`@KeyExtractor`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/KeyExtractor.java) | 标记 static 方法为身份提取器（从事件提取身份标识） | ✅ |
| [`@EventHandler`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventHandler.java) | 标记方法为处理器，声明优先级与独占 | ✅ |
| [`@EventRoute`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/EventRoute.java) | 声明处理什么事件、什么条件下处理（SpEL `condition` 或 `filter` 方法引用） | ✅（可省略，用默认） |
| [`@InstanceProvider`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/annotation/InstanceProvider.java) | 标记 static 方法为实例工厂（自定义创建/查找逻辑） | ❌ |

三层架构（单向 DAG，无循环依赖）：

```
用户层   @EventHandler + @EventRoute + @KeyExtractor + @InstanceProvider
  ▲
路由层   HandlerRegistry —— 身份提取、实例创建、优先级排序、跨优先级独占
  ▲
引擎层   EventEngine（对接 Nukkit，固定 LOWEST）→ EventService（公开门面）
```

- [`EventService`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventService.java)：用户入口，提供 `register` / `unregister` / `evict` / `scan` / `bindPlugin`。
- [`EventEngine`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/EventEngine.java)：内部引擎，以固定 `LOWEST` 优先级向 Nukkit 注册，确保所有事件只触发一次分发，由 `HandlerRegistry` 内部完成优先级排序与独占判断。
- 支持 SpEL 条件（`condition`）、Java 方法引用过滤（`filter`）、多槽位身份提取（OR 语义）、全局单例语义等高级用法。

> 📖 详细文档见 [`jframe_event/.../event/README.md`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/README.md)，维护者文档见 [`DEVELOPER.md`](jframe_event/src/main/java/io/github/JiangHu/jframe/event/DEVELOPER.md)。

### jframe_form · 表单界面

- [`FormView`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java)：界面抽象基类。子类实现 [`buildForm()`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java:45) 构建表单、重写 `onClicked(int)` 处理点击；通过 [`addStack()`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java:75) 进入子菜单、[`replaceThis()`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/FormView.java:63) 同层替换。
- [`SinglePlayerViewManager`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/SinglePlayerViewManager.java)：为单个玩家维护一个视图栈，栈顶即当前显示界面，点击后自动刷新。
- [`ViewService`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewService.java)：全局入口，通过 [`sendForm()`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewService.java:70) 为玩家打开界面（自动创建管理器）。

> 依赖 `moe.him188:GUI`（`system` 作用域），默认由服务端或单独的 GUI 插件提供。

### jframe_thread · 异步任务

- [`ThreadService`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadService.java)：基于「命名线程池」的异步任务管理。每个队列用 [`createThreadTask()`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadService.java:63) 创建，内部为单线程执行器，保证同队列任务串行执行；通过 [`pushTask()`](jframe_thread/src/main/java/io/github/JiangHu/jframe/thread/ThreadService.java:88) 提交任务，支持全局异常处理器。

### jframe_example · 示例插件

一个完整的 Nukkit 插件，演示事件（对象级路由 + 全局单例 + 优先级独占）、表单（栈式导航）、线程（异步任务）三模块的协同使用，并附带游戏内测试步骤。

> 📖 详细说明见 [`jframe_example/README.md`](jframe_example/README.md)。

---

## 🛠️ 构建与部署

### 构建示例插件

```bash
# 1. 先在根目录安装框架各模块
mvn clean install -DskipTests

# 2. 打包示例插件（含 shade 打 fat jar）
cd jframe_example
mvn clean package
# 产物：jframe_example/target/jframe_example.jar
```

### 部署

把生成的 jar 放入 Nukkit 服务端的 `plugins/` 目录，重启服务器即可。示例插件提供 `/jframe`（打开菜单）和 `/sword`（发放闪电剑）两个命令。

### 关于 GUI 库

表单模块依赖的 `moe.him188:GUI` 在框架中是 `system` 作用域，**不会被 shade 打包**，默认假设由服务端或单独的 GUI 插件提供。若需一并打进 fat jar，参见 [`jframe_example/README.md`](jframe_example/README.md) 的说明。

---

## 📂 目录结构

```
JFrame/
├── pom.xml                          # 父 POM（聚合 6 个模块）
├── README.md                        # 本文件
├── 建议的框架改进.txt                # 框架改进建议与已修复记录
├── libs/gui-1.15.1.jar              # 表单库（system 作用域）
├── plans/                           # 设计/重构方案文档
├── jframe_core/                     # 核心基础
├── jframe_main/                     # 框架入口
├── jframe_event/                    # 事件系统
├── jframe_form/                     # 表单/GUI
├── jframe_thread/                   # 异步任务
└── jframe_example/                  # 示例插件
```

---

## 📚 文档导航

| 文档 | 内容 |
|------|------|
| [事件系统 README](jframe_event/src/main/java/io/github/JiangHu/jframe/event/README.md) | 事件模块完整用法、4 注解详解、6 种用法示例 |
| [事件系统 DEVELOPER](jframe_event/src/main/java/io/github/JiangHu/jframe/event/DEVELOPER.md) | 事件模块内部实现与维护者文档 |
| [示例插件 README](jframe_example/README.md) | 端到端示例的构建、部署与游戏内测试步骤 |
| [建议的框架改进.txt](建议的框架改进.txt) | 框架级改进建议（含已修复的循环依赖、Bean 名冲突等） |

---

## ⚠️ 已知限制与注意事项

- **`JFrameMain` 暂不可继承**：构造器与 `bindPlugin()` 为 `private`，业务插件目前需采用「独立装配 Spring」的方式（方式 B）。改进建议见 [《建议的框架改进.txt》](建议的框架改进.txt) 建议 1。
- **Nukkit 类加载器隔离**：Spring 默认用线程上下文类加载器查找 `classpath:` 资源，而 Nukkit 主线程的上下文类加载器看不到插件 jar 内部资源。创建 Spring 上下文前后需临时切换为插件类加载器（见方式 B）。详见建议 4。
- **`ViewService` 需手动清理**：玩家退出时框架不会自动清理其视图管理器，建议在 `PlayerQuitEvent` 中调用 [`ViewService.remove()`](jframe_form/src/main/java/io/github/JiangHu/jframe/form/ViewService.java:43)，避免内存泄漏。详见建议 3。
- **跨线程操作主线程 API**：`ThreadService` 的任务运行在异步线程，若需修改方块、传送等主线程操作，应额外调度回主线程。
