# jframe_core — 框架核心基础设施

> 为整个 JFrame 框架提供**模块绑定机制**、**跨插件类加载抽象**、**统一异常与日志**以及**通用数据容器**。本模块不含业务逻辑，是其他所有模块依赖的底层基石。

---

## 📑 目录

- [一、模块定位](#一模块定位)
- [二、包结构](#二包结构)
- [三、PluginAware：模块绑定接口](#三pluginaware模块绑定接口)
- [四、ForPlugin：跨插件作用域代理](#四forplugin跨插件作用域代理)
- [五、classloader：跨插件类加载](#五classloader跨插件类加载)
- [六、scan：注解类扫描封装](#六scan注解类扫描封装)
- [七、data：通用数据容器](#七data通用数据容器)
- [八、JFrameException：统一异常基类](#八jframeexception统一异常基类)
- [九、JFrameLog：日志工具](#九jframelog日志工具)
- [十、与传统方法的对比](#十与传统方法的对比)
- [十一、快速示例](#十一快速示例)
- [十二、Spring 集成](#十二spring-集成)

---

## 一、模块定位

`jframe_core` 解决以下跨模块的底层需求：

1. **模块与 Nukkit 插件的绑定**：许多模块的 Bean 需要拿到 `Plugin` 实例（注册监听器、读取数据目录），但 Spring 容器本身不知道插件存在。[`PluginAware`](module/PluginAware.java) 提供统一的注入入口。
2. **跨插件类加载**：Nukkit 插件类加载器互相隔离，模块需要扫描或加载**调用方插件 jar 内**的类 / 资源时，必须使用调用方的 ClassLoader。[`ForPlugin`](module/ForPlugin.java) + [`classloader`](classloader/PluginClassLoaderFactory.java) 包提供统一抽象。
3. **统一异常与日志**：各模块异常统一继承 [`JFrameException`](JFrameException.java)，日志统一使用 [`JFrameLog`](JFrameLog.java)，消除散乱的样板代码。
4. **通用数据结构**：游戏开发中高频出现的「响应式状态同步」与「键值缓存」场景，提供开箱即用的容器，避免每个模块重复造轮子。

> 本模块是纯工具层，不依赖任何业务模块，被 `jframe_main` 第一个装配。

---

## 二、包结构

```
core/
├── JFrameException.java           # 框架统一异常基类
├── JFrameLog.java                 # 日志工具（封装 Server.getLogger()）
├── module/
│   ├── PluginAware.java           # 被动插件绑定接口（JFrameMain 自动注入）
│   └── ForPlugin.java             # 主动插件绑定接口（调用方按需获取作用域）
├── classloader/
│   ├── PluginClassLoaderFactory.java  # 从 Nukkit 插件获取类加载器
│   └── CompositeClassLoader.java      # 聚合多个类加载器（跨插件加载）
├── scan/
│   └── AnnotatedClassScanner.java     # 注解类路径扫描封装
├── data/
│   ├── README.md                 # 数据容器详细文档（必读）
│   ├── map/                      # 键值映射
│   │   ├── AbstractFunctionalMap.java   # 功能可插拔 Map 基类
│   │   ├── LruCacheMap.java             # 线程安全 LRU 缓存
│   │   └── PlayerDataMap.java           # 玩家维度映射（退服自动清理）
│   ├── reactive/                 # 响应式纠缠值
│   │   ├── EntangledValue.java          # 可纠缠的响应式值容器
│   │   ├── EntangledChannel.java        # 纠缠通道（协调中枢）
│   │   ├── Entangled.java               # 无类型根接口（异构纠缠）
│   │   ├── EntangledEvent.java          # 值变化事件
│   │   └── Role.java                    # 成员收发角色
│   └── trigger/                  # 按次数触发
│       └── CountdownTrigger.java        # 倒计时触发器（指定次调用后执行绑定函数）
└── config/
    └── CoreSpringConfig.java     # Spring 装配入口
```

---

## 三、PluginAware：模块绑定接口

[`PluginAware`](module/PluginAware.java) 是一个单方法接口，实现它的 Spring Bean 会在插件启用时**自动接收** Nukkit `Plugin` 实例：

```java
public interface PluginAware {
    void bindPlugin(Plugin plugin);
}
```

### 用法

模块中需要插件实例的 Bean 实现该接口即可，无需任何手动注册：

```java
public class EventEngine implements PluginAware {
    private Plugin plugin;

    @Override
    public void bindPlugin(Plugin plugin) {
        this.plugin = plugin;
        // 用 plugin 向 PluginManager 注册事件监听器 ...
    }
}
```

### 绑定时机

由 [`JFrameMain.bindPlugin()`](../../main/JFrameMain.java) 在 `onEnable` 时统一扫描容器中所有 `PluginAware` Bean 并调用。单个 Bean 绑定异常被捕获，不影响其他 Bean。

> **为什么需要它**：Spring 容器由 `JFrameMain` 创建，但容器内的 Bean（如 EventEngine）需要 `Plugin` 实例才能与 Nukkit 交互。`PluginAware` 提供了「容器刷新后自动注入插件」的标准入口，避免每个模块各自寻找插件实例。

---

## 四、ForPlugin：跨插件作用域代理

[`ForPlugin<T>`](module/ForPlugin.java) 解决的是与 `PluginAware` 互补的问题：**当模块需要操作调用方插件 jar 内的类 / 资源时，如何拿到正确的 ClassLoader？**

### 问题背景

Nukkit 中每个插件由独立的 `PluginClassLoader` 加载，互相隔离。当 JFrame 的 command / event / template / scoreboard 模块需要扫描**调用方插件包下的注解类**或加载**调用方插件 jar 内的模板**时，必须使用调用方插件的 ClassLoader，否则找不到目标类 / 资源。

### 接口契约

```java
public interface ForPlugin<T> {
    T forPlugin(Plugin plugin);       // 按插件实例绑定
    T forPlugin(String pluginName);   // 按插件名绑定
}
```

`forPlugin` 返回一个**专门的作用域对象（Scope）**，该对象内部绑定了指定插件的 ClassLoader，后续操作自动在正确的类路径下执行。

### 与 PluginAware 的区别

| 维度 | [`PluginAware`](module/PluginAware.java) | [`ForPlugin`](module/ForPlugin.java) |
|------|------|------|
| **绑定方向** | 被动：由 `JFrameMain` 自动注入 | 主动：由调用方按需获取 |
| **用途** | 拿到 `Plugin` 实例（注册监听器、读数据目录） | 拿到调用方插件的 ClassLoader（扫描类、加载资源） |
| **返回值** | 无（void） | 作用域对象（Scope） |
| **调用时机** | `onEnable` 时自动调用一次 | 调用方代码中随时调用 |

### 作用域对象（Scope）的设计

`forPlugin` 返回的不是 API 类自身，而是**专门的 Scope 类**。Scope 只暴露需要插件 ClassLoader 的方法，不暴露无关方法：

```java
// 命令：扫描调用方插件包下的 @CommandController
commandAPI.forPlugin(this).scan("com.myplugin.command");

// 事件：扫描调用方插件包下的 @Wrapper
eventAPI.forPlugin(this).scan("com.myplugin.event");

// 模板：从调用方插件 jar 加载模板
engine.forPlugin(this).getTemplate("main");

// 记分板：从调用方插件 jar 加载记分板模板
scoreboardAPI.forPlugin(this).loadTemplate("hud");
```

**为什么用独立的 Scope 类而非返回自身**：

- **API 精确引导**：IDE 自动补全在 Scope 上只显示相关方法（如 `scan`），不会显示 `register`、`render` 等无关方法。
- **防止误用**：在 Scope 上调用 `register` 会被编译器拒绝——它不属于 Scope 的接口。
- **不污染原类**：API 类无需新增 ClassLoader 字段，保持核心逻辑干净。

> 各模块的 Scope 类实现细节见对应模块文档：[`CommandPluginScope`](../../command/CommandPluginScope.java)、[`EventPluginScope`](../../event/EventPluginScope.java)、[`TemplatePluginScope`](../../content_template/TemplatePluginScope.java)、[`ScoreboardPluginScope`](../../scoreboard/ScoreboardPluginScope.java)。

---

## 五、classloader：跨插件类加载

Nukkit 中每个插件有独立的 `PluginClassLoader`，互相隔离——一个插件默认**看不到**其他插件 jar 内的资源（模板、配置、语言文件）。`classloader` 包提供跨插件加载资源的能力。

| 类型 | 作用 |
|------|------|
| [`PluginClassLoaderFactory`](classloader/PluginClassLoaderFactory.java) | 从 Nukkit `PluginManager` 按插件名获取类加载器 |
| [`CompositeClassLoader`](classloader/CompositeClassLoader.java) | 聚合多个类加载器，按顺序查找资源/类 |

### 用法

```java
// 加载单个指定插件的资源
ClassLoader loader = PluginClassLoaderFactory.getClassLoader("MyAddon");

// 聚合多个插件，从任一插件 jar 加载
CompositeClassLoader multi = PluginClassLoaderFactory.compositeOf("JFrame", "MyAddon");
InputStream is = multi.getResourceAsStream("templates/main.xml");

// 聚合所有已加载插件（兜底）
CompositeClassLoader all = PluginClassLoaderFactory.compositeOfAllPlugins();
```

> **调用时机**：依赖 `Server.getInstance()`，只能在服务端启动后（如 `onEnable`）调用。模板引擎的 [`ClasspathTemplateLoader`](../../content_template/loader/ClasspathTemplateLoader.java) 已支持传入此类加载器，实现跨插件加载模板。[`ForPlugin`](#四forplugin跨插件作用域代理) 内部也通过此工厂获取 ClassLoader。

---

## 六、scan：注解类扫描封装

[`AnnotatedClassScanner`](scan/AnnotatedClassScanner.java) 封装了 Spring `ClassPathScanningCandidateComponentProvider` 的配置样板，让各模块扫描注解类时只需一行代码。

### 设计动机

`CommandScanner` 和 `WrapperScanner` 中原本存在几乎完全相同的 `createProvider` 样板：

```java
// 重复样板（重构前）
ClassPathScanningCandidateComponentProvider provider =
    new ClassPathScanningCandidateComponentProvider(false);  // 关闭默认过滤器
provider.addIncludeFilter(new AnnotationTypeFilter(XXX));    // 唯一差异：注解类型
provider.setResourceLoader(new DefaultResourceLoader(cl));   // 绑定 ClassLoader
```

`AnnotatedClassScanner` 将这段样板提取为通用工具，各模块只需传入注解类型。

### 用法

```java
// 扫描 @CommandController
AnnotatedClassScanner scanner = new AnnotatedClassScanner(CommandController.class);
Set<Class<?>> classes = scanner.scan(pluginClassLoader, "com.myplugin.command");

// 扫描 @Wrapper
AnnotatedClassScanner scanner = new AnnotatedClassScanner(Wrapper.class);
Set<Class<?>> classes = scanner.scan(pluginClassLoader, "com.myplugin.event");
```

### 核心特性

- **自动跳过非具体类**：接口、抽象类、注解类型即使标注了目标注解也会被 Spring 内置过滤跳过。
- **容错加载**：无法加载的类会被跳过并记录警告日志（通过 [`JFrameLog`](JFrameLog.java)），不中断扫描。
- **保持发现顺序**：返回 `LinkedHashSet`，类发现顺序稳定。
- **TCCL 回退**：[`resolveClassLoader()`](scan/AnnotatedClassScanner.java) 静态方法优先使用线程上下文类加载器，回退到本类加载器。

> 各模块的 Scanner（如 [`CommandScanner`](../../command/scan/CommandScanner.java)、[`WrapperScanner`](../../event/scan/WrapperScanner.java)）已委托给本类，消除重复代码。

---

## 七、data：通用数据容器

`data` 包提供三类高频数据结构，**详细用法请阅读 [data/README.md](data/README.md)**。此处仅作概览。

### reactive —— 响应式纠缠值

灵感取自**量子纠缠**：多个 [`EntangledValue`](data/reactive/EntangledValue.java) 加入同一通道后，任一成员的值更新会**通知**其他成员。适合「一处变化、多处感知」：

```
血量数据 ──纠缠──→ HUD 显示
            ├──→ Boss 血条
            └──→ 计分板
（任一处修改，其余自动收到通知）
```

核心类型：

| 类型 | 作用 |
|------|------|
| [`EntangledValue`](data/reactive/EntangledValue.java) | 可观察、可纠缠的值容器 |
| [`EntangledChannel`](data/reactive/EntangledChannel.java) | 纠缠协调中枢，独立一等公民 |
| [`Role`](data/reactive/Role.java) | 成员收发方向：`BOTH` / `SOURCE`（只发）/ `SINK`（只听）/ `MUTE`（静默） |

### map —— 键值映射

功能可插拔的 Map 抽象，以「键源 `S`」为读写入口，内部键 `K` 不透明：

| 类型 | 作用 |
|------|------|
| [`AbstractFunctionalMap`](data/map/AbstractFunctionalMap.java) | 基类：键提取 / 加载 / 过期 / 淘汰回调可插拔 |
| [`LruCacheMap`](data/map/LruCacheMap.java) | 线程安全 LRU 缓存（`S=K`） |
| [`PlayerDataMap`](data/map/PlayerDataMap.java) | 玩家维度映射，退服自动清理（`S=Player`） |

### trigger —— 按次数触发

[`CountdownTrigger`](data/trigger/CountdownTrigger.java)：被调用指定次数后执行绑定函数的计数数据类型。`tick()` 计数减一，归零时执行绑定的函数；`RESETTABLE`（默认）触发后失效可 `reset()` 复活，`RECURRING` 自动循环周期触发。

| 类型 | 作用 |
|------|------|
| [`CountdownTrigger`](data/trigger/CountdownTrigger.java) | 倒计时触发器：指定次调用后执行绑定函数（可重置 / 自动循环） |

---

## 八、JFrameException：统一异常基类

[`JFrameException`](JFrameException.java) 是所有模块业务异常的统一基类，继承 `RuntimeException`。

### 设计动机

各模块原本各自定义独立的 `RuntimeException` 子类，没有统一基类。调用方无法通过一次 `catch (JFrameException e)` 统一捕获框架异常。

### 异常层次

```
RuntimeException
  └── JFrameException          ← 本类（框架统一基类）
        ├── DataException
        ├── ArgumentConversionException
        ├── TemplateLoadException
        ├── TemplateNotFoundException
        ├── TemplateParseException
        └── InventoryCodecException
```

### 用法

```java
// 模块定义异常：继承 JFrameException
public class DataException extends JFrameException {
    public DataException(String message) { super(message); }
    public DataException(String message, Throwable cause) { super(message, cause); }
}

// 调用方统一捕获
try {
    dataSaver.save("key", value);
    engine.getTemplate("main");
} catch (JFrameException e) {
    // 统一处理框架异常
    JFrameLog.error("MyPlugin", "框架异常", e);
}
```

> 调用方既可 `catch (JFrameException)` 统一处理，也可 `catch (DataException)` 等具体子类精确处理。

---

## 九、JFrameLog：日志工具

[`JFrameLog`](JFrameLog.java) 封装了 `Server.getInstance().getLogger()` 的冗长调用，自动添加 `[tag]` 前缀，统一日志格式。

### 设计动机

项目中原本有 25+ 处 `Server.getInstance().getLogger().xxx()` 调用，散布在各模块。每次都要写一长串，且日志前缀不统一。

### API

| 方法 | 说明 |
|------|------|
| `info(tag, message)` | INFO 级别日志 |
| `warning(tag, message)` | WARNING 级别日志 |
| `error(tag, message)` | ERROR 级别日志（不带堆栈） |
| `error(tag, message, cause)` | ERROR 级别日志（带异常堆栈） |
| `debug(tag, message)` | DEBUG 级别日志 |

### 用法

```java
// 简洁调用
JFrameLog.info("CommandScanner", "已注册命令控制器: " + className);
JFrameLog.error("CommandScanner", "注册失败: " + className, e);
JFrameLog.warning("CommandScanner", "跳过无法加载的类: " + className);

// 等价于原来的冗长写法
Server.getInstance().getLogger().info("[CommandScanner] 已注册命令控制器: " + className);
Server.getInstance().getLogger().error("[CommandScanner] 注册失败: " + className, e);
Server.getInstance().getLogger().warning("[CommandScanner] 跳过无法加载的类: " + className);
```

> **调用时机**：依赖 `Server.getInstance()`，只能在服务端启动后（如 `onEnable`）调用。在 Spring 容器启动阶段 Server 可能尚未就绪。

---

## 十、与传统方法的对比

### 响应式状态同步

| 维度 | 传统观察者模式 / `PropertyChangeListener` | [`EntangledValue`](data/reactive/EntangledValue.java) |
|------|------|------|
| **关联方式** | 一对一：被观察者显式持有监听器列表 | 多对多：任意成员加入同一通道即互相纠缠 |
| **可传递性** | 无（A→B、B→C 不意味着 A→C） | 有（A、B、C 同通道则互相通知） |
| **异构支持** | 通常同类型 | 不同类型的值可纠缠同一通道（Integer + String） |
| **方向控制** | 无（监听器总是被触发） | 角色门控：`SOURCE` 只发、`SINK` 只听、`MUTE` 静默 |
| **典型代码量** | 手写注册/注销/通知循环 | `a.entangle(b)` 一行 |

### 玩家数据缓存

| 维度 | 手写 `ConcurrentHashMap<Player, T>` | [`PlayerDataMap`](data/map/PlayerDataMap.java) |
|------|------|------|
| **退服清理** | 需手动监听 `PlayerQuitEvent` 清理，易遗漏导致内存泄漏 | 内置：玩家退出自动移除其数据 |
| **加载策略** | 需手写 `computeIfAbsent` | 内置 `getOrCreate` + 可插拔加载器 |
| **过期/淘汰** | 需自行实现 | 可插拔回调 |

### 跨插件类加载

| 维度 | 手动获取 ClassLoader | [`ForPlugin`](module/ForPlugin.java) + Scope |
|------|------|------|
| **调用方代码** | 需自行 `plugin.getClass().getClassLoader()` 并传递 | `api.forPlugin(this).scan(...)` 一行 |
| **类型安全** | 无（ClassLoader 到处传） | Scope 对象编译期约束可用方法 |
| **样板消除** | 每处重复 | 统一契约，各模块实现一致 |

---

## 十一、快速示例

### 纠缠值：血量驱动多处显示

```java
var hp   = EntangledValue.of(100);
var hud  = EntangledValue.of(100);
var boss = EntangledValue.of(100);

hp.entangle(hud, boss);                          // 纠缠
hud.addListener(e -> refreshHud(e.newValue()));  // 监听

hp.set(80);   // hud、boss 自动收到通知（各自值不变，由监听器响应）
```

### 传感器 → 显示器（单向流）

```java
var sensor  = EntangledValue.of(0);
var display = EntangledValue.of(0);

EntangledChannel ch = new EntangledChannel("hp");
sensor.joinAsSource(ch);    // 只发不收
display.joinAsSink(ch);     // 只听不发

sensor.set(50);   // display 收到；display.set 不会回流给 sensor
```

### 倒计时触发器：连击 / 充能

```java
// 三连击达成触发（一次性保险丝）
var combo = CountdownTrigger.of(3, () -> player.sendMessage("三连击！"));
combo.tick();   // false
combo.tick();   // false
combo.tick();   // true —— 触发

// 每 5 次采集掉落一次奖励（自动循环）
var gather = CountdownTrigger.of(5, () -> dropReward(), CountdownTrigger.Mode.RECURRING);
```

### 跨插件扫描命令控制器

```java
// 在调用方插件的 onEnable 中
commandAPI.forPlugin(this).scan("com.myplugin.command");
```

> 更多场景（异构纠缠、去重、容器突变、通道级监听、触发模式）见 [data/README.md](data/README.md)。

---

## 十二、Spring 集成

[`CoreSpringConfig`](config/CoreSpringConfig.java) 遵循项目统一的装配模式：

```java
@Configuration
@ComponentScan("io.github.JiangHu.jframe.core")
@ImportResource("classpath:core-spring.xml")
```

由 [`MainSpringConfig`](../../main/config/MainSpringConfig.java) 第一个 `@Import`。`PluginAware` Bean 在容器刷新后被 [`JFrameMain`](../../main/JFrameMain.java) 自动绑定插件实例。

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
