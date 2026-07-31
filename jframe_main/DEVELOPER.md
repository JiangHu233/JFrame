# jframe_main — 维护者文档

> 面向模块维护者。记录 [`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 的容器创建、类加载器修正、PluginAware 绑定、服务注册与生命周期实现。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、模块结构](#一模块结构)
- [二、容器创建时机](#二容器创建时机)
- [三、类加载器修正（核心）](#三类加载器修正核心)
- [四、PluginAware 绑定机制](#四pluginaware-绑定机制)
- [五、ServiceManager 注册](#五servicemanager-注册)
- [六、模块装配](#六模块装配)
- [七、生命周期](#七生命周期)
- [八、ConfigEnum 现状](#八configenum-现状)
- [九、设计决策记录](#九设计决策记录)
- [十、扩展指南](#十扩展指南)

---

## 一、模块结构

```
jframe_main/
├── pom.xml                              # 聚合全部子模块为 fat jar
└── src/main/
    ├── java/.../main/
    │   ├── JFrameMain.java              # 插件主类，全部逻辑集中于此
    │   ├── config/
    │   │   └── MainSpringConfig.java    # 根配置，@Import 全部模块 SpringConfig
    │   └── utils/
    │       └── ConfigEnum.java          # 模块枚举（遗留）
    └── resources/
        ├── plugin.yml                   # Nukkit 插件描述（name=JFrame）
        └── main-spring.xml              # 主模块 Bean 定义
```

[`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 继承 `cn.nukkit.plugin.PluginBase`，是 Nukkit 实例化的插件主类，**不是** Spring Bean。它持有 Spring 容器（[`applicationContext`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:57)）作为框架与 Nukkit 之间的桥梁。

---

## 二、容器创建时机

与常见「在 `onEnable` 创建容器」不同，本模块在**构造方法**中就创建并刷新容器：

```java
public JFrameMain() {
    this.applicationContext = createApplicationContext();   // 构造阶段即完成
}
```

Nukkit 加载插件时先 `new` 主类（触发构造），再依次调用 `onLoad` → `onEnable`。把容器创建放在构造阶段，保证后续任何生命周期回调都能直接使用容器。代价是：若容器刷新失败，插件构造即抛异常，Nukkit 会标记插件加载失败——这是期望的快速失败行为。

---

## 三、类加载器修正（核心）

[`createApplicationContext()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:85) 是本模块最关键的实现，解决 Nukkit 插件环境下的类加载隔离问题。

### 问题

Nukkit 主线程的上下文类加载器（`Thread.getContextClassLoader()`）是「服务器类加载器」，它**看不到插件 jar 内部的 classpath 资源**（如各模块的 `*-spring.xml`）。而 Spring 解析 `classpath:` 资源时优先使用线程上下文类加载器，因此会抛出：

```
FileNotFoundException: class path resource [xxx-spring.xml] cannot be opened
```

### 解决

```java
private AnnotationConfigApplicationContext createApplicationContext() {
    ClassLoader pluginLoader = getClass().getClassLoader();        // 插件类加载器
    ClassLoader prev = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(pluginLoader);    // ① 临时切换
    try {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setClassLoader(pluginLoader);                      // ② 上下文持久持有
        context.register(MainSpringConfig.class);
        context.refresh();
        return context;
    } finally {
        Thread.currentThread().setContextClassLoader(prev);        // ③ 恢复
    }
}
```

三步处理：

1. **临时切换线程上下文类加载器**为插件类加载器，使 `refresh()` 期间 Spring 能加载插件内的 `classpath:` 资源。
2. **让上下文本身持久持有插件类加载器**（`setClassLoader`），保证后续 Bean 类型解析、资源加载、`@ComponentScan` 扫描都一致——这一步确保容器在构造完成后、回到原类加载器环境时仍能正常工作。
3. **`finally` 恢复原类加载器**，避免污染主线程。

> 这是 Nukkit 插件集成 Spring 的通用痛点，本方法是标准解法。

---

## 四、PluginAware 绑定机制

部分模块的 Bean 需要拿到 Nukkit `Plugin` 实例（用于注册事件监听器、读取数据目录、调度任务等）。但 Spring 容器本身不知道 `Plugin` 的存在。本模块通过 [`PluginAware`](../core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java) 接口桥接：

```java
protected void bindPlugin() {
    Map<String, PluginAware> awareBeans = applicationContext.getBeansOfType(PluginAware.class);
    for (PluginAware aware : awareBeans.values()) {
        try {
            aware.bindPlugin(this);                    // 把 JFrameMain(this) 注入每个 Bean
        } catch (Exception e) {
            getLogger().error("绑定 plugin 到 " + aware.getClass().getName() + " 失败", e);
        }
    }
}
```

**设计要点**：

- **按接口发现**：`getBeansOfType(PluginAware.class)` 自动找出所有实现该接口的 Bean，无需逐一列举。新增模块只要让 Bean 实现 `PluginAware` 即可被自动绑定。
- **容错**：单个 Bean 绑定异常被捕获并记录，不影响其他 Bean，避免一个模块失败拖垮整个框架。
- **调用时机**：在 [`onEnable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:235) 调用一次。此时容器已刷新、所有 Bean 已就绪。

---

## 五、ServiceManager 注册

[`registerServices()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:215) 把各模块 API 注册到 Nukkit `ServiceManager`，使业务插件能通过服务发现获取：

```java
server.getServiceManager().register(EventAPI.class, getEventAPI(), this, ServicePriority.NORMAL);
// ... ViewAPI / ThreadAPI / CommandAPI / InventoryAPI / AiAPI / DataSaver 同理
```

- 第三个参数 `this`（JFrameMain）是服务提供者，Nukkit 据此在插件卸载时自动注销服务。
- `ServicePriority.NORMAL` 为默认优先级。

**门面方法与服务的等价性**：[`getEventAPI()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:128) 等门面方法内部调用 [`getApi()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:202) → [`getBean()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:61)，与 ServiceManager 注册的是**同一实例**。

---

## 六、模块装配

[`MainSpringConfig`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 是装配中枢，用 `@Import` 一次性导入全部 8 个模块：

```java
@Configuration
@ImportResource("classpath:main-spring.xml")
@Import({
    CoreSpringConfig.class, CommandSpringConfig.class, ThreadSpringConfig.class,
    FormSpringConfig.class, EventSpringConfig.class, DataSpringConfig.class,
    InventorySpringConfig.class, AiSpringConfig.class
})
```

每个子模块遵循统一的 `*SpringConfig`（`@Configuration` + `@ComponentScan` + `@ImportResource("classpath:*-spring.xml")`）模式。根配置只需 `@Import` 它们，Spring 会递归处理各模块的扫描与资源加载。

---

## 七、生命周期

| 阶段 | 方法 | 行为 |
|------|------|------|
| 构造 | [`JFrameMain()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:65) | 创建并刷新 Spring 容器（含类加载器修正） |
| 加载 | [`onLoad()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:227) | 仅打印日志 |
| 启用 | [`onEnable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:232) | 设置 `instance`、[`bindPlugin()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:110)、[`registerServices()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:215) |
| 禁用 | [`onDisable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:242) | 关闭 InventoryAPI 视图、ThreadAPI 线程池、最后关闭容器 |

### 关闭顺序

[`onDisable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:242) 按「业务资源 → 容器」的顺序关闭：

1. `InventoryAPI.closeAll()` —— 关闭所有打开的箱子视图（避免玩家卡在界面）。
2. `ThreadAPI.stopAll()` —— 关闭所有异步线程池（等待已提交任务）。
3. `applicationContext.close()` —— 销毁 Spring 容器，触发各 Bean 的销毁回调。

每步独立 try-catch，保证某一步失败不阻断后续清理。

> `instance` 在 [`onEnable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:233) 才赋值，而非构造阶段。因此构造期间（容器刷新中）若 Bean 代码调用 `JFrameMain.getInstance()` 会得到 `null`——Bean 初始化逻辑不应依赖此单例。

---

## 八、ConfigEnum 现状

[`ConfigEnum`](src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java) 是一个模块枚举，每个值关联一个 `SpringConfig` 类。其 Javadoc 描述了「动态导入模块」的设计意图。

**当前实际状态**：容器装配完全由 [`MainSpringConfig`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 的静态 `@Import` 完成，[`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 不再读取 `ConfigEnum` 进行动态导入。[`modules`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:59) 字段初始化为 `List.of(ConfigEnum.CORE)` 但未参与装配逻辑。

> 该枚举目前为遗留代码，保留供未来「按需动态装配模块」特性使用。维护时注意：新增模块需同时更新 [`MainSpringConfig`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 的 `@Import`（必需）与 [`ConfigEnum`](src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java)（保持一致）。

---

## 九、设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 容器创建时机 | 构造方法 | 保证所有生命周期回调都能用容器；失败快速暴露 |
| 类加载器处理 | 临时切换 + 上下文持久持有 | 解决 Nukkit 插件 classpath 资源不可见问题 |
| Plugin 绑定 | `PluginAware` 接口 + `getBeansOfType` | 按接口自动发现，新增模块零配置接入 |
| 服务注册 | ServiceManager + 门面方法双通道 | 兼顾便捷（门面）与解耦（服务发现） |
| 模块装配 | 静态 `@Import` | 简单可靠，编译期可见全部依赖 |
| 关闭顺序 | 业务资源 → 容器 | 先释放玩家可见资源，再销毁容器 |

---

## 十、扩展指南

### 新增一个模块（如 `jframe_xxx`）

1. 在新模块中创建 `XxxSpringConfig`（`@Configuration` + `@ComponentScan` + `@ImportResource`）。
2. 在 [`MainSpringConfig`](src/main/java/io/github/JiangHu/jframe/main/config/MainSpringConfig.java) 的 `@Import` 中加入 `XxxSpringConfig.class`。
3. 在 [`ConfigEnum`](src/main/java/io/github/JiangHu/jframe/main/utils/ConfigEnum.java) 中加入对应枚举值（保持一致）。
4. 若新模块有公开 API 类，在 [`JFrameMain`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 增加门面方法（参考 [`getEventAPI()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:128)），并在 [`registerServices()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:215) 注册到 ServiceManager。
5. 若该模块 Bean 需要 `Plugin` 实例，让其实现 `PluginAware`，[`bindPlugin()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:110) 会自动处理。
6. 若该模块持有需关闭的资源，在 [`onDisable()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:242) 增加关闭逻辑（容器关闭前）。

### 修改类加载器策略

若未来 Nukkit 版本改变了类加载行为，[`createApplicationContext()`](src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java:85) 是唯一需要调整的入口。注意保持「上下文持久持有插件类加载器」这一步，否则容器在构造完成后回到原类加载器环境会失效。
