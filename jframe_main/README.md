# jframe_main —— JFrame 前置插件

`jframe_main` 把框架全部模块（core / event / form / thread / command / inventory）聚合为一个
**可独立部署的 Nukkit 前置插件（库插件）**。服务器只需部署一份，所有业务插件通过
`depend` 引用它，即可共享同一套 Spring 容器与各模块 API，无需各自打包框架代码。

---

## 一、部署前置插件

```bash
# 在项目根目录打包（产物为 fat jar，已内含各子模块与 Spring）
mvn -pl jframe_main -am package -DskipTests
```

产物：`jframe_main/target/jframe_main.jar`（约 5MB）

将该 jar 放入服务器的 `plugins/` 目录，启动服务器后插件名为 **`JFrame`**，
主类为 `io.github.JiangHu.jframe.main.JFrameMain`。启用时它会：

1. 启动 Spring 容器，加载全部模块；
2. 绑定插件实例到所有 `PluginAware` Bean（EventEngine / CommandEngine / InventoryAPI …）；
3. 将各模块 API 注册到 Nukkit `ServiceManager`。

---

## 二、业务插件接入

### 1. Maven 依赖（`provided` 作用域）

业务插件**编译期**需要引用框架的 API 类，但**运行时**由前置插件提供，
因此必须用 `provided` 作用域，避免把框架类重复打进业务插件 jar 造成冲突：

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

---

## 三、获取模块 API（两种等价方式）

### 方式 A：门面方法（最简洁）

```java
EventAPI eventAPI = JFrameMain.getInstance().getEventAPI();
ViewAPI  viewAPI  = JFrameMain.getInstance().getViewAPI();
ThreadAPI threadAPI = JFrameMain.getInstance().getThreadAPI();
CommandAPI commandAPI = JFrameMain.getInstance().getCommandAPI();
InventoryAPI inventoryAPI = JFrameMain.getInstance().getInventoryAPI();
```

### 方式 B：ServiceManager（更解耦，推荐用于纯服务发现）

```java
RegisteredServiceProvider<EventAPI> provider =
        getServer().getServiceManager().getProvider(EventAPI.class);
EventAPI eventAPI = provider.getProvider();
```

> 两种方式拿到的是**同一个共享实例**，所有业务插件共用。

---

## 四、注册业务插件自己的处理器

业务插件可把自己的 `@Wrapper` / `@CommandController` 注册到框架共享容器。
**注意必须传入业务插件自身的类加载器**，否则扫描不到业务插件 jar 内的类：

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

> 事件监听统一注册在 `JFrame` 插件名下（由 EventEngine 绑定 JFrameMain 实例完成），
> 业务插件无需自己注册 Nukkit 监听器。

---

## 五、与「独立装配」模式的对比

| 维度 | 前置插件模式（本模块） | 独立装配模式（见 jframe_example） |
|------|------------------------|-----------------------------------|
| 框架代码 | 服务器部署一份，业务插件 `provided` | 每个业务插件各打一份进自己 jar |
| Spring 容器 | 全局共享一份 | 每插件独立一份 |
| 状态共享 | 共享（如全局事件总线） | 隔离 |
| 接入成本 | 中（需部署前置插件 + depend） | 低（照示例抄 XML） |
| 适用场景 | 多插件协作、需共享状态 | 单插件、需独立隔离 |

---

## 六、模块构成

| 文件 | 作用 |
|------|------|
| `JFrameMain.java` | 插件主类：启动容器、绑定 PluginAware、注册服务、API 门面 |
| `config/MainSpringConfig.java` | 聚合各模块 SpringConfig 的根配置 |
| `utils/ConfigEnum.java` | 模块枚举 |
| `resources/plugin.yml` | 插件描述（name=JFrame） |
| `resources/main-spring.xml` | 主模块 Bean（当前为空，实际 Bean 由各子模块提供） |
