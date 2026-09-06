# jframe_core — 维护者文档

> 面向模块维护者。记录核心基础设施的实现原理：PluginAware 绑定机制、ForPlugin 作用域代理、跨插件类加载策略、注解扫描封装、统一异常与日志、纠缠值的并发模型与广播流程、函数式映射的键源设计、倒计时触发器的并发模型。
> 使用文档请看 [README.md](README.md)，数据容器详细文档请看 [data/README.md](data/README.md)。

---

## 📑 目录

- [一、模块职责与依赖](#一模块职责与依赖)
- [二、PluginAware 绑定机制](#二pluginaware-绑定机制)
- [三、ForPlugin 作用域代理](#三forplugin-作用域代理)
- [四、跨插件类加载实现](#四跨插件类加载实现)
- [五、AnnotatedClassScanner 扫描封装](#五annotatedclassscanner-扫描封装)
- [六、JFrameException 统一异常](#六jframeexception-统一异常)
- [七、JFrameLog 日志工具](#七jframelog-日志工具)
- [八、纠缠值的并发模型](#八纠缠值的并发模型)
- [九、两阶段广播流程](#九两阶段广播流程)
- [十、异构纠缠的实现](#十异构纠缠的实现)
- [十一、函数式映射的键源设计](#十一函数式映射的键源设计)
- [十二、倒计时触发器的并发模型](#十二倒计时触发器的并发模型)
- [十三、扩展指南](#十三扩展指南)

---

## 一、模块职责与依赖

`jframe_core` 是纯底层模块，**不依赖任何业务模块**，只依赖 Nukkit（`Plugin` 类型）与 Lombok。它被 `jframe_main` 第一个装配，其他模块（event / form / command 等）间接依赖它提供的 [`PluginAware`](module/PluginAware.java)、[`ForPlugin`](module/ForPlugin.java)、类加载工具、扫描工具、异常基类、日志工具与数据容器。

六大职责互不耦合：
- `module/PluginAware` —— 解决「Bean 如何被动拿到 Plugin 实例」。
- `module/ForPlugin` —— 解决「模块如何主动绑定调用方插件的 ClassLoader」。
- `classloader/` —— 解决「如何跨插件加载资源与类」。
- `scan/` —— 解决「如何消除注解类扫描的重复样板」。
- `JFrameException` / `JFrameLog` —— 解决「异常层次统一」与「日志样板消除」。
- `data/` —— 解决「通用数据结构复用」。

---

## 二、PluginAware 绑定机制

### 接口契约

[`PluginAware`](module/PluginAware.java) 只声明一个方法：

```java
void bindPlugin(Plugin plugin);
```

实现方在此方法中保存插件引用，并完成依赖插件才能进行的初始化（注册监听器、读取数据目录等）。

### 绑定流程

绑定由 [`JFrameMain.bindPlugin()`](../../main/JFrameMain.java) 驱动：

```java
Map<String, PluginAware> awareBeans = applicationContext.getBeansOfType(PluginAware.class);
for (PluginAware aware : awareBeans.values()) {
    try {
        aware.bindPlugin(this);
    } catch (Exception e) {
        getLogger().error("绑定 plugin 到 " + aware.getClass().getName() + " 失败", e);
    }
}
```

**设计要点**：

- **按接口发现**：`getBeansOfType` 自动找出所有实现者，新增模块零配置接入。
- **容错隔离**：单个 Bean 绑定异常被捕获，不阻断其他 Bean。
- **调用时机**：`onEnable` 时调用一次，此时容器已刷新、所有 Bean 就绪。

> **关于 Javadoc**：[`PluginAware`](module/PluginAware.java) 的类注释仍提及旧的 `satisfyRequired` 选择性导入机制，但当前装配已改为 [`MainSpringConfig`](../../main/config/MainSpringConfig.java) 静态 `@Import` 全部模块。接口契约本身不变，仅注释描述的触发时机过时。

---

## 三、ForPlugin 作用域代理

[`ForPlugin<T>`](module/ForPlugin.java) 是与 `PluginAware` 互补的插件绑定机制：`PluginAware` 是**被动**接收（由 JFrameMain 注入），`ForPlugin` 是**主动**绑定（由调用方按需获取）。

### 解决的问题

Nukkit 插件类加载器隔离导致：当 command / event / template / scoreboard 模块需要扫描**调用方插件 jar 内**的类或加载其资源时，必须使用调用方插件的 ClassLoader。但模块的 API 对象是 Spring 单例，不持有任何调用方插件的 ClassLoader。

### 接口契约

```java
public interface ForPlugin<T> {
    T forPlugin(Plugin plugin);       // 按插件实例绑定
    T forPlugin(String pluginName);   // 按插件名绑定
}
```

`forPlugin` 返回一个**专门的 Scope 类**（泛型参数 `T`），而非 API 类自身。

### 为什么返回独立的 Scope 类（Option B）

设计时考虑过两种方案：

| 方案 | 描述 | 问题 |
|------|------|------|
| **Option A：返回自身** | `forPlugin` 返回 `this`，API 类新增 ClassLoader 字段 | API 类所有方法都暴露，调用方可能在 Scope 上误调无关方法；API 类需新增状态字段 |
| **Option B：返回 Scope** ✅ | `forPlugin` 返回专门的 Scope 类，只暴露需要 ClassLoader 的方法 | 需为每个模块新增 Scope 类，但 API 精确、防误用、不污染原类 |

选择 **Option B** 的理由：

- **API 精确引导**：Scope 只暴露需要 ClassLoader 的方法（如 `scan`、`getTemplate`、`loadTemplate`），IDE 自动补全不会显示 `register`、`render` 等无关方法。
- **防止误用**：在 Scope 上调用 `register` 会被编译器拒绝——它不属于 Scope 的接口。
- **不污染原类**：API 类无需新增 ClassLoader 字段，核心逻辑保持干净。

### Scope 的状态安全

Scope 对象**共享** API 对象的核心组件引用（如 `CommandEngine`、`EventEngine`），但**独立持有** ClassLoader：

```java
// CommandPluginScope 示例
class CommandPluginScope {
    private final CommandEngine engine;      // 共享引用（Spring 单例）
    private final ClassLoader classLoader;   // 独立持有（调用方插件的）

    void scan(String... packages) {
        engine.scan(classLoader, packages);  // 用独立 ClassLoader 扫描
    }
}
```

**关键结论**：Scope 对象是无状态代理——它只持有引用，不维护任何可变状态。多个 Scope 实例（绑定不同插件）互不干扰，因为它们各自独立持有 ClassLoader，但共享的 Engine 单例本身是线程安全的。

### 线程安全

- 每次 `forPlugin` 调用都创建**新的 Scope 实例**，独立持有 ClassLoader。
- 原 API 对象（Spring 单例）保持无状态，不受 `forPlugin` 调用影响。
- Scope 对象本身无状态（只持有 final 引用），可安全地在多线程间传递。

### 各模块 Scope 实现

| 模块 | Scope 类 | 暴露方法 |
|------|------|------|
| command | [`CommandPluginScope`](../../command/CommandPluginScope.java) | `scan(String...)` |
| event | [`EventPluginScope`](../../event/EventPluginScope.java) | `scan(String...)` |
| template | [`TemplatePluginScope`](../../content_template/TemplatePluginScope.java) | `getTemplate`、`render`、`withPrefix`、`withSuffix` |
| scoreboard | [`ScoreboardPluginScope`](../../scoreboard/ScoreboardPluginScope.java) | `loadTemplate(String)`、`withPrefix(String)` |

---

## 四、跨插件类加载实现

Nukkit 采用**插件级类加载器隔离**：每个插件由独立的 `PluginClassLoader` 加载，插件之间默认互相不可见。这意味着插件 A 无法用 `getClass().getResource()` 读到插件 B 的 jar 内资源。`classloader/` 包打破这一隔离，提供跨插件加载能力。

### PluginClassLoaderFactory —— 插件类加载器工厂

[`PluginClassLoaderFactory`](classloader/PluginClassLoaderFactory.java) 通过 Nukkit 的 `PluginManager` 按插件名定位类加载器：

```java
public static ClassLoader getClassLoader(String pluginName) {
    Server server = requireServer();
    Plugin plugin = server.getPluginManager().getPlugin(pluginName);
    if (plugin == null) {
        throw new IllegalArgumentException("插件不存在: " + pluginName);
    }
    return plugin.getClass().getClassLoader();
}
```

**关键点**：

- **获取方式**：`plugin.getClass().getClassLoader()` 直接拿到该插件的 `PluginClassLoader`。Nukkit 的插件类均由各自的加载器加载，因此类的加载器即插件的加载器。
- **Server 前置检查**：`requireServer()` 校验 `Server.getInstance()` 非空，否则抛 `IllegalStateException`。这保证工厂只在服务端启动后可用（`onEnable` 之后），避免在静态初始化期误用。
- **插件不存在即失败**：传入了不存在的插件名直接抛异常，而非返回 null——跨插件加载是显式契约，静默失败会导致资源加载在下游产生难以追踪的 NPE。

> [`ForPlugin`](module/ForPlugin.java) 的 `forPlugin(Plugin)` / `forPlugin(String)` 内部即通过此工厂获取 ClassLoader。

### CompositeClassLoader —— 委托优先聚合加载器

[`CompositeClassLoader`](classloader/CompositeClassLoader.java) 将多个 `ClassLoader` 聚合为一个虚拟统一加载器，按**构造顺序**逐个委托：

```java
@Override
public InputStream getResourceAsStream(String name) {
    for (ClassLoader delegate : delegates) {
        InputStream is = delegate.getResourceAsStream(name);
        if (is != null) return is;
    }
    return super.getResourceAsStream(name);   // 兜底：系统类路径
}
```

#### 委托优先 vs 父优先

标准 Java 类加载采用**父优先（parent-first）**委托：先问父加载器，父找不到再自己找。`CompositeClassLoader` **故意反转**为**委托优先（delegate-first）**：

| 维度 | 标准 parent-first | CompositeClassLoader delegate-first |
|------|------|------|
| **查找顺序** | 父 → 自身 | 委托列表 → 父（系统类路径） |
| **设计目标** | 避免核心类被篡改、保证类型一致 | 跨插件资源发现：优先在插件 jar 中找 |
| **适用场景** | 通用类加载 | 聚合多个隔离插件的资源 |

**为什么反转**：跨插件加载的目的是「从任意插件的 jar 中发现资源」。若父优先，系统类路径（服务端 jar）会先被搜索，可能命中同名资源而错过插件内的目标资源。委托优先确保插件 jar 优先。

> `super` 指向 `getSystemClassLoader()`（服务端类路径），作为所有委托都未命中时的兜底。这保证 JDK 标准资源（如 `META-INF`）仍可加载。

#### 去重与空值处理

构造时过滤 `null` 与重复加载器，保证委托列表紧凑、无冗余查找：

```java
public CompositeClassLoader(ClassLoader... delegates) {
    super(getSystemClassLoader());
    List<ClassLoader> list = new ArrayList<>();
    for (ClassLoader cl : delegates) {
        if (cl != null && !list.contains(cl)) {
            list.add(cl);
        }
    }
    this.delegates = list;
}
```

#### 线程安全

`CompositeClassLoader` **本身无状态**：`delegates` 列表在构造后不再修改（事实不可变），所有方法仅读取。各委托 `ClassLoader`（Nukkit 的 `PluginClassLoader`）自身负责其线程安全。因此 `CompositeClassLoader` 实例可安全地在多线程间共享，无需额外同步。

### 与模板引擎的协作

[`ClasspathTemplateLoader`](../../content_template/loader/ClasspathTemplateLoader.java) 接受可选的 `ClassLoader` 参数，传入时用 `new ClassPathResource(path, classLoader)` 指定资源查找的加载器：

```java
Resource resource = classLoader != null
        ? new ClassPathResource(path, classLoader)
        : new ClassPathResource(path);
```

传入 `CompositeClassLoader` 后，模板引擎即可从多个插件的 jar 中加载模板，实现「主插件渲染 + 扩展插件提供模板」的解耦。

---

## 五、AnnotatedClassScanner 扫描封装

[`AnnotatedClassScanner`](scan/AnnotatedClassScanner.java) 封装了 Spring `ClassPathScanningCandidateComponentProvider` 的配置样板，消除各模块 Scanner 中的重复代码。

### 重构前后的对比

**重构前**（`CommandScanner` 和 `WrapperScanner` 各有一份几乎相同的 `createProvider`）：

```java
// 重复样板
ClassPathScanningCandidateComponentProvider provider =
    new ClassPathScanningCandidateComponentProvider(false);
provider.addIncludeFilter(new AnnotationTypeFilter(XXX));    // 唯一差异
provider.setResourceLoader(new DefaultResourceLoader(cl));
```

**重构后**（委托给 `AnnotatedClassScanner`）：

```java
// CommandScanner
private final AnnotatedClassScanner scanner = new AnnotatedClassScanner(CommandController.class);
Set<Class<?>> classes = scanner.scan(classLoader, packages);

// WrapperScanner
private final AnnotatedClassScanner scanner = new AnnotatedClassScanner(Wrapper.class);
Set<Class<?>> classes = scanner.scan(classLoader, packages);
```

### 实现要点

#### 扫描流程

```java
public Set<Class<?>> scan(ClassLoader classLoader, String... basePackages) {
    Set<Class<?>> result = new LinkedHashSet<>();
    ClassPathScanningCandidateComponentProvider provider = createProvider(classLoader);
    for (String basePackage : basePackages) {
        for (BeanDefinition bd : provider.findCandidateComponents(basePackage)) {
            String className = bd.getBeanClassName();
            try {
                result.add(Class.forName(className, false, classLoader));
            } catch (Throwable e) {
                JFrameLog.warning("AnnotatedClassScanner", "跳过无法加载的类: " + className);
            }
        }
    }
    return result;
}
```

**关键设计**：

- **`Class.forName(className, false, classLoader)`**：第三个参数 `false` 表示**不触发类初始化**（不执行 `static {}` 块）。扫描只需拿到 `Class` 对象，不应有副作用。
- **容错加载**：`catch (Throwable)` 捕获所有错误（含 `NoClassDefFoundError`），跳过无法加载的类并记录警告。这保证一个类的依赖缺失不会中断整个扫描。
- **`LinkedHashSet`**：保持发现顺序，便于调试与确定性测试。

#### Spring 内置过滤

构造 `ClassPathScanningCandidateComponentProvider(false)` 时传入 `false` 关闭默认过滤器（不自动扫描 `@Component` 等），仅添加目标注解的 `AnnotationTypeFilter`。Spring 扫描器还会自动跳过接口、抽象类、注解类型——即使它们标注了目标注解。

#### TCCL 回退

[`resolveClassLoader()`](scan/AnnotatedClassScanner.java) 静态方法提供线程上下文类加载器（TCCL）回退：

```java
public static ClassLoader resolveClassLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
        cl = AnnotatedClassScanner.class.getClassLoader();
    }
    return cl;
}
```

在 Nukkit 插件中，调用方应在 `onEnable` 期间将 TCCL 切换为插件自身的类加载器，扫描器即可正确发现插件 jar 内的类。但更推荐通过 [`ForPlugin`](module/ForPlugin.java) 显式指定 ClassLoader，避免依赖 TCCL 的隐式状态。

---

## 六、JFrameException 统一异常

[`JFrameException`](JFrameException.java) 是所有模块业务异常的统一基类，继承 `RuntimeException`。

### 设计动机

重构前，各模块各自定义独立的 `RuntimeException` 子类，没有共同基类。调用方若想统一捕获框架异常，需要逐个 `catch` 每种异常类型，或退而求其次 `catch (RuntimeException)`（过于宽泛，会捕获非框架异常）。

### 异常层次

```
RuntimeException
  └── JFrameException          ← 本类（框架统一基类）
        ├── DataException                    (jframe_data)
        ├── ArgumentConversionException      (jframe_command)
        ├── TemplateLoadException            (jframe_template)
        ├── TemplateNotFoundException        (jframe_template)
        ├── TemplateParseException           (jframe_template)
        └── InventoryCodecException          (jframe_inventory)
```

### 构造方法

提供三个标准构造方法，覆盖常见场景：

```java
public JFrameException(String message)                    // 仅消息
public JFrameException(String message, Throwable cause)   // 消息 + 原因
public JFrameException(Throwable cause)                   // 仅原因
```

子类只需按需选择并提供对应构造方法即可。

### 迁移规则

新增模块异常时**必须**继承 `JFrameException`，保持层次统一。这样调用方可以：

```java
try {
    // 调用多个模块的 API
} catch (JFrameException e) {
    // 统一处理所有框架异常
}
```

或精确捕获具体子类：

```java
try {
    engine.getTemplate("main");
} catch (TemplateNotFoundException e) {
    // 精确处理模板不存在
}
```

---

## 七、JFrameLog 日志工具

[`JFrameLog`](JFrameLog.java) 封装了 `Server.getInstance().getLogger()` 的冗长调用，自动添加 `[tag]` 前缀。

### 设计动机

重构前，项目中有 25+ 处 `Server.getInstance().getLogger().xxx()` 调用，散布在 command、event、async、ai、inventory 等模块。每次都要写一长串，且日志前缀格式不统一（有的加 `[模块名]`，有的不加）。

### 实现要点

#### 实时获取 Logger

```java
private static Logger logger() {
    Server server = Server.getInstance();
    if (server == null) {
        throw new IllegalStateException("Nukkit Server 尚未初始化，请在服务端启动后（如 onEnable）调用");
    }
    return server.getLogger();
}
```

**不缓存 Logger 实例**：每次调用都实时获取。理由是 Nukkit 的 Logger 在服务端生命周期内不变，但缓存会引入「静态字段初始化时机」问题——若在 Server 就绪前访问缓存字段，会得到 null。实时获取虽有一次方法调用开销，但保证了正确性。

#### 统一前缀格式

所有方法都自动添加 `[tag]` 前缀：

```java
public static void info(String tag, String message) {
    logger().info("[" + tag + "] " + message);
}
```

输出示例：`[CommandScanner] 已注册命令控制器: com.myplugin.command.GuildController`

#### 五个日志级别

| 方法 | 对应 Nukkit 级别 | 用途 |
|------|------|------|
| `info(tag, msg)` | INFO | 正常流程信息 |
| `warning(tag, msg)` | WARNING | 非致命异常（如跳过无法加载的类） |
| `error(tag, msg)` | ERROR | 错误（不带堆栈） |
| `error(tag, msg, cause)` | ERROR | 错误（带异常堆栈） |
| `debug(tag, msg)` | DEBUG | 调试信息 |

### 迁移规则

新增日志调用时**应使用** `JFrameLog` 而非直接调用 `Server.getInstance().getLogger()`。tag 通常取类名或模块名（如 `"CommandScanner"`、`"HandlerRegistry"`）。

> **注意**：若文件中同时使用了 `Server.getInstance().getScheduler()` 等非日志调用，迁移日志后仍需保留 `Server` 的 import。

---

## 八、纠缠值的并发模型

纠缠体系（[`EntangledValue`](data/reactive/EntangledValue.java) + [`EntangledChannel`](data/reactive/EntangledChannel.java)）采用**粗粒度静态锁**策略：

### 静态锁 `EntangledChannel.LOCK`

整个纠缠体系**共用一把静态锁**（`EntangledChannel.LOCK`），所有结构变更与 `set` 的状态更新段都在此锁内串行化：

```java
public EntangledValue<T> set(T newValue) {
    Object oldVal;
    EntangledChannel ch;
    synchronized (EntangledChannel.LOCK) {     // 状态更新段
        oldVal = this.value;
        if (distinct && Objects.equals(oldVal, newValue)) return this;
        this.value = newValue;
        ch = this.channel;
    }
    if (ch != null) ch.broadcast(this, oldVal, newValue);   // 锁外广播
    else this.dispatch(new EntangledEvent<>(this, oldVal, newValue));
}
```

**为什么用全局静态锁而非每通道锁**：

- 纠缠是**可传递**的：`a-b-c` 同通道，且通道可合并（`mergeFrom`）。若每通道各持一把锁，合并通道时需要跨锁协调，极易死锁。
- 全局锁牺牲少量并发吞吐，换取**绝对不会死锁**的简单性。游戏服务端的值变更频率远低于高并发服务，粗粒度锁足够。

### 锁内 vs 锁外

| 操作 | 是否持锁 | 原因 |
|------|:---:|------|
| 值读写、distinct 判断、channel 读取 | ✅ 锁内 | 状态一致性 |
| 结构变更（纠缠/加入/离开/角色调整/通道合并） | ✅ 锁内 | 避免成员集合并发修改 |
| 监听器回调 | ❌ 锁外 | 避免长耗时回调阻塞其他线程，防止回调内再次 `set` 导致死锁 |

### 可见性保障

- `value` 字段为 `volatile`：即使读操作（`get()`）不加锁，也能读到最新值。
- `listeners` 为 `CopyOnWriteArrayList`：遍历产生快照，增删监听器不影响进行中的遍历。

---

## 九、两阶段广播流程

某成员 `set` 后，由 [`EntangledChannel.broadcast()`](data/reactive/EntangledChannel.java) 执行两阶段派发：

```
成员 set
  └─ 通道.broadcast(trigger, oldVal, newVal)
       ├─ 阶段①：通道级监听器（subscribe 注册）
       │    └─ 以「通道身份」执行，能看到整组变化（最先触发）
       └─ 阶段②：遍历所有成员（含触发者）
            └─ 按各成员「接收」开关派发成员级监听器（addListener 注册）
```

**关键设计**：

- **触发者一视同仁**：阶段②统一遍历所有成员（含触发者），按各自「接收」开关决定是否派发。触发者若 `receive=false`（如 `SOURCE` 角色），也不会收到自己的事件。
- **角色门控**：`SOURCE`（只发）的 `set` 会广播，但自身不收；`SINK`（只听）的 `set` 不产生任何广播；`MUTE` 既不发也不收。
- **异常隔离**：单个监听器抛 `RuntimeException` 由 [`errorHandler`](data/reactive/EntangledValue.java) 捕获，不中断其他监听器。

---

## 十、异构纠缠的实现

不同值类型（如 `EntangledValue<Integer>` 与 `EntangledValue<String>`）能纠缠到同一通道，靠的是 [`Entangled`](data/reactive/Entangled.java) 无类型根接口：

```java
public interface Entangled {
    Object rawValue();                              // 无类型读值
    void dispatch(EntangledEvent<?> event);         // 通道派发事件
    Consumer<RuntimeException> errorHandler();      // 异常处理
}
```

[`EntangledValue<T>`](data/reactive/EntangledValue.java) 实现了 `Entangled`，通道以 `Entangled` 视角管理成员，**不关心具体泛型**。广播时事件携带的值类型取自触发者；同构通道下类型安全，异构场景下监听器需自行转换。

> 这是「类型擦除换取异构灵活性」的典型权衡：通道内部完全无类型，类型安全责任下移到监听器。

---

## 十一、函数式映射的键源设计

[`AbstractFunctionalMap<S, K>`](data/map/AbstractFunctionalMap.java) 的核心设计是**键源 `S` 与内部键 `K` 分离**：

- 所有读写方法（`get` / `put` / `remove` / `containsKey` / `getOrCreate`）以**键源 `S`** 为参数。
- 内部经 `keyExtractor`（`Function<S, K>`）转换为真正的存储键 `K`。
- `K` 对使用者不透明。

**为什么分离**：

- [`PlayerDataMap`](data/map/PlayerDataMap.java) 取 `S=Player`、`K=String`（玩家名）：调用方传 `Player` 对象，内部自动提取名字作为键，退服时按玩家清理。调用方无需关心键是什么。
- [`LruCacheMap`](data/map/LruCacheMap.java) 取 `S=K`（`keyExtractor` 默认 `identity`）：键即源，退化成普通缓存。

**可插拔功能点**：键提取、值加载（`getOrCreate`）、过期回调、淘汰回调均可通过函数式参数定制，子类只需选择泛型与提供策略。

---

## 十二、倒计时触发器的并发模型

[`CountdownTrigger`](data/trigger/CountdownTrigger.java) 采用「**同步段判定触发权 + 锁外执行 action**」模型，与纠缠体系的「状态更新段持锁、回调锁外执行」思路一致，但锁粒度为**实例级**（`synchronized(this)`），无全局静态锁：

```java
public boolean tick() {
    final boolean fire;
    synchronized (this) {                    // ① 同步段：状态判定
        if (cancelled || remaining <= 0) return false;
        remaining--;
        fire = remaining == 0;
        if (fire) {
            triggerCount++;
            if (mode == Mode.RECURRING) remaining = count;   // 自动开始下一轮
        }
    }
    if (fire) runAction();                   // ② 锁外：执行绑定函数
    return fire;
}
```

设计要点：

- **恰好一次**：触发权（`remaining` 归零判定）在同步段内完成，并发调用下绑定函数对每次归零恰好执行一次，不会重复触发。
- **锁外执行**：`runAction()` 在同步段外调用，action 内可安全调用 `tick()` / `reset()` / `cancel()`（重入同步段）而不会死锁；也避免 action 耗时长导致其他线程阻塞在计数上。
- **异常隔离**：action 抛出的 `RuntimeException` 由 `errorHandler` 捕获（默认打印 `System.err`），不影响计数状态；`RECURRING` 模式异常后仍继续下一轮。
- **可见性**：状态字段 `volatile`，查询方法（`getRemaining()` / `isCancelled()` 等）无锁读取，读到的是最近一次同步段提交的值。

---

## 十三、扩展指南

### 新增一个 PluginAware Bean

1. 让 Bean 实现 [`PluginAware`](module/PluginAware.java)。
2. 在 `bindPlugin` 中保存 `Plugin` 引用并完成初始化。
3. 确保 Bean 被 Spring 管理（`@Component` 或 XML 声明）。
4. 无需其他配置——[`JFrameMain.bindPlugin()`](../../main/JFrameMain.java) 会自动发现并调用。

### 新增一个 ForPlugin Scope

若新模块需要跨插件扫描 / 加载能力：

1. 定义 Scope 类（如 `XxxPluginScope`），持有核心组件引用 + ClassLoader。
2. 只在 Scope 上暴露需要 ClassLoader 的方法。
3. 让 API 类实现 [`ForPlugin<XxxPluginScope>`](module/ForPlugin.java)，在 `forPlugin` 中通过 [`PluginClassLoaderFactory`](classloader/PluginClassLoaderFactory.java) 获取 ClassLoader 并构造 Scope。
4. 若涉及注解扫描，Scope 内部委托给 [`AnnotatedClassScanner`](scan/AnnotatedClassScanner.java)。

参考实现：[`CommandPluginScope`](../../command/CommandPluginScope.java)、[`TemplatePluginScope`](../../content_template/TemplatePluginScope.java)。

### 新增一个模块异常

1. 继承 [`JFrameException`](JFrameException.java)。
2. 提供所需构造方法（通常 `String` 和 `String + Throwable`）。
3. 调用方即可通过 `catch (JFrameException)` 统一捕获。

### 新增跨插件加载场景

`classloader/` 包已覆盖「单插件」「指定多插件」「全部插件」三种聚合策略。若需更细粒度控制：

- **按前缀过滤插件**：在 [`PluginClassLoaderFactory.getAllPluginClassLoaders()`](classloader/PluginClassLoaderFactory.java) 基础上，按插件名前缀筛选子集再聚合。
- **自定义委托顺序**：直接构造 [`CompositeClassLoader`](classloader/CompositeClassLoader.java)，传入按优先级排列的加载器数组——构造顺序即查找顺序。
- **缓存加载器实例**：`PluginClassLoaderFactory` 每次调用都查询 `PluginManager`，若高频使用可在调用方缓存返回的 `ClassLoader`（插件运行期间其加载器不变）。

### 新增一个数据结构

- **新的响应式容器**：若需要不同于 `EntangledValue` 的语义（如带版本的值、限流通知），可实现 [`Entangled`](data/reactive/Entangled.java) 接口加入现有纠缠体系，复用通道与广播机制。
- **新的映射类型**：继承 [`AbstractFunctionalMap`](data/map/AbstractFunctionalMap.java)，指定 `<S, K>` 泛型与 `keyExtractor`，复用加载 / 过期 / 淘汰框架。
- **新的触发类型**：若需要按条件（而非按次数）触发，可参考 [`CountdownTrigger`](data/trigger/CountdownTrigger.java) 的「同步段判定触发权 + 锁外执行 action」模型，替换计数判定逻辑即可。

### 修改纠缠体系并发策略

当前全局静态锁 [`EntangledChannel.LOCK`](data/reactive/EntangledChannel.java) 是简单性与安全性的权衡。若未来值变更频率极高成为瓶颈，可考虑：

- 细化为每通道锁，但必须妥善处理通道合并（`mergeFrom`）的锁顺序，避免死锁。
- 或改用无锁结构（如 `ConcurrentHashMap` 存储成员 + CAS 更新值），但广播的原子性保障会更复杂。

> 任何修改请同步更新 [data/README.md](data/README.md) 的线程安全说明与测试覆盖（[`EntangledValueTest`](../../../../../test/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValueTest.java) 含 17 类场景、65 个断言；[`CountdownTriggerTest`](../../../../../test/java/io/github/JiangHu/jframe/core/data/trigger/CountdownTriggerTest.java) 含 11 类场景、103 个断言）。
