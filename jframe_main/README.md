# jframe_main —— JFrame 前置插件

> 把框架全部模块聚合为**一个可独立部署的 Nukkit 前置插件（库插件）**。服务器只需部署一份，所有业务插件通过 `depend` 引用它，即可共享同一套 Spring 容器与各模块 API，无需各自打包框架代码。

---

## 📑 目录

- [一、它解决什么问题](#一它解决什么问题)
- [二、与传统方式的对比](#二与传统方式的对比)
- [三、部署前置插件](#三部署前置插件)
- [四、业务插件接入](#四业务插件接入)
- [五、获取模块 API](#五获取模块-api)
- [六、注册业务插件自己的处理器](#六注册业务插件自己的处理器)
- [七、模块构成](#七模块构成)

---

## 一、它解决什么问题

一个 Minecraft 服务器往往同时运行多个业务插件（经济、公会、小游戏……）。如果每个插件都各自打包一份框架代码、各自启动一个 Spring 容器，会带来三类问题：

1. **重复与冲突**：同一份框架类被多个插件加载器各加载一份，内存翻倍，且容易出现版本不一致导致的 `ClassCastException`。
2. **状态割裂**：各插件容器互相隔离，无法共享全局状态（如统一的事件总线、数据保存器）。
3. **接入繁琐**：每个插件都要自己写容器初始化、资源加载、生命周期管理。

本模块把框架打包成**一个前置插件**，业务插件只需 `depend: [JFrame]`，即可零成本复用同一套容器与 API。

---

## 二、与传统方式的对比

| 维度 | 传统：每插件自带框架 | 前置插件模式（本模块 [`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)） |
|------|------|------|
| **框架代码** | 每个业务插件各打一份进自己 jar | 服务器部署一份，业务插件 `provided` 作用域引用 |
| **Spring 容器** | 每插件独立创建一份 | 全局共享一份（[`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 启动时创建） |
| **状态共享** | 隔离，插件间无法互通 | 共享（如全局事件总线、统一数据保存器） |
| **类加载冲突** | 易因重复加载触发转换异常 | 单一来源，无冲突 |
| **生命周期** | 各插件自行管理容器启停 | 框架统一在 [`onEnable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:232) / [`onDisable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:242) 管理 |
| **接入成本** | 高（需写容器初始化代码） | 低（声明 `depend` + 调门面方法） |
| **适用场景** | 单插件、需强隔离 | 多插件协作、需共享状态 |

> 若业务插件需要完全隔离（独立容器、不共享状态），可参考 `jframe_example` 的「独立装配」用法，把框架直接打进自己 jar。

---

## 三、部署前置插件

```bash
# 在项目根目录打包（产物为 fat jar，已内含各子模块与 Spring）
mvn -pl jframe_main -am package -DskipTests
```

产物：`jframe_main/target/jframe_main.jar`。将该 jar 放入服务器的 `plugins/` 目录，启动服务器后插件名为 **`JFrame`**，主类为 [`io.github.JiangHu.jframe.main.JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java)。

启用时它会依次完成三件事（见 [`onEnable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:232)）：

1. **绑定插件实例**：调用 [`bindPlugin()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:110)，把自身注入所有实现 `PluginAware` 的 Bean（如 EventEngine、CommandEngine、DataSaver），使其能拿到 Nukkit `Plugin` 实例去注册监听器、读取数据目录。
2. **注册服务**：调用 [`registerServices()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:215)，将各模块 API 注册到 Nukkit `ServiceManager`。
3. 容器在**构造阶段**（[`JFrameMain()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:65)）就已创建并刷新完毕。

---

## 四、业务插件接入

### 1. Maven 依赖（`provided` 作用域）

业务插件**编译期**需要引用框架的 API 类，但**运行时**由前置插件提供，因此必须用 `provided` 作用域，避免把框架类重复打进业务插件 jar 造成冲突：

```xml
<dependency>
    <groupId>io.github.JiangHu.jframe</groupId>
    <artifactId>jframe_main</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 2. plugin.yml 声明依赖

```yaml
name: MyPlugin
main: com.example.MyPlugin
depend: [JFrame]   # 关键：确保 JFrame 先加载，且本插件能访问其类
api: ["1.0.0"]
```

> `depend`（硬依赖）会保证 JFrame 在本插件之前加载；若只需运行时可选引用，可用 `softdepend`。

---

## 五、获取模块 API

框架聚合了 **8 个模块**（core / event / form / thread / command / inventory / data / ai），全部由 [`MainSpringConfig`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 统一 `@Import`。获取 API 有两种等价方式：

### 方式 A：门面方法（最简洁）

通过 [`JFrameMain.getInstance()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:54) 的门面方法直接获取：

```java
EventAPI     eventAPI     = JFrameMain.getInstance().getEventAPI();      // 事件总线
ViewAPI      viewAPI      = JFrameMain.getInstance().getViewAPI();       // 表单界面
ThreadAPI    threadAPI    = JFrameMain.getInstance().getThreadAPI();     // 异步任务队列
CommandAPI   commandAPI   = JFrameMain.getInstance().getCommandAPI();    // 命令路由
InventoryAPI inventoryAPI = JFrameMain.getInstance().getInventoryAPI();  // 箱子界面
AiAPI        aiAPI        = JFrameMain.getInstance().getAiAPI();         // AI 寻路与战术
DataSaver    dataSaver    = JFrameMain.getInstance().getDataSaver();     // 数据保存
```

### 方式 B：ServiceManager（更解耦）

适合不想硬依赖 `JFrameMain` 类、纯靠服务发现的场景：

```java
RegisteredServiceProvider<EventAPI> provider =
        getServer().getServiceManager().getProvider(EventAPI.class);
EventAPI eventAPI = provider.getProvider();
```

> 两种方式拿到的是**同一个共享实例**，所有业务插件共用。门面方法内部即 [`getBean()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:61)，ServiceManager 则在 [`registerServices()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:215) 注册。

---

## 六、注册业务插件自己的处理器

业务插件可把自己 jar 内的 `@Wrapper`（事件监听）、`@CommandController`（命令）注册到框架共享容器。**注意必须传入业务插件自身的类加载器**，否则扫描不到业务插件 jar 内的类：

```java
public class MyPlugin extends PluginBase {

    private EventAPI eventAPI;
    private CommandAPI commandAPI;

    @Override
    public void onEnable() {
        eventAPI = JFrameMain.getInstance().getEventAPI();
        commandAPI = JFrameMain.getInstance().getCommandAPI();

        // 扫描本插件 jar 内的 @Wrapper（传入本插件类加载器）
        eventAPI.scan(getClass().getClassLoader(), "com.example.wrapper");

        // 扫描本插件 jar 内的 @CommandController
        commandAPI.scan(getClass().getClassLoader(), "com.example.command");
    }
}
```

> 事件监听统一注册在 `JFrame` 插件名下（由 EventEngine 通过 `PluginAware` 绑定 JFrameMain 实例完成），业务插件无需自己注册 Nukkit 监听器。

---

## 七、模块构成

| 文件 | 作用 |
|------|------|
| [`JFrameMain.java`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) | 插件主类：创建容器、绑定 PluginAware、注册服务、API 门面、生命周期管理 |
| [`config/MainSpringConfig.java`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) | 根配置，`@Import` 全部 8 个模块的 SpringConfig |
| [`utils/ConfigEnum.java`](src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java) | 模块枚举（遗留，当前装配以 MainSpringConfig 为准） |
| [`resources/plugin.yml`](src/main/resources/plugin.yml) | 插件描述（name=JFrame） |
| [`resources/main-spring.xml`](src/main/resources/main-spring.xml) | 主模块 Bean 定义 |

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
