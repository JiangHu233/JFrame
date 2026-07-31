# jframe_core — 框架核心基础设施

> 为整个 JFrame 框架提供**模块绑定机制**与**通用数据容器**。本模块不含业务逻辑，是其他所有模块依赖的底层基石。

---

## 📑 目录

- [一、模块定位](#一模块定位)
- [二、包结构](#二包结构)
- [三、PluginAware：模块绑定接口](#三pluginaware模块绑定接口)
- [四、data：通用数据容器](#四data通用数据容器)
- [五、与传统方法的对比](#五与传统方法的对比)
- [六、快速示例](#六快速示例)
- [七、Spring 集成](#七spring-集成)

---

## 一、模块定位

`jframe_core` 解决两类跨模块的底层需求：

1. **模块与 Nukkit 插件的绑定**：许多模块的 Bean 需要拿到 `Plugin` 实例（注册监听器、读取数据目录），但 Spring 容器本身不知道插件存在。[`PluginAware`](module/PluginAware.java) 提供统一的注入入口。
2. **通用数据结构**：游戏开发中高频出现的「响应式状态同步」与「键值缓存」场景，提供开箱即用的容器，避免每个模块重复造轮子。

> 本模块是纯工具层，不依赖任何业务模块，被 `jframe_main` 第一个装配。

---

## 二、包结构

```
core/
├── module/
│   └── PluginAware.java          # 模块插件绑定接口
├── data/
│   ├── README.md                 # 数据容器详细文档（必读）
│   ├── map/                      # 键值映射
│   │   ├── AbstractFunctionalMap.java   # 功能可插拔 Map 基类
│   │   ├── LruCacheMap.java             # 线程安全 LRU 缓存
│   │   └── PlayerDataMap.java           # 玩家维度映射（退服自动清理）
│   └── reactive/                 # 响应式纠缠值
│       ├── EntangledValue.java          # 可纠缠的响应式值容器
│       ├── EntangledChannel.java        # 纠缠通道（协调中枢）
│       ├── Entangled.java               # 无类型根接口（异构纠缠）
│       ├── EntangledEvent.java          # 值变化事件
│       └── Role.java                    # 成员收发角色
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

## 四、data：通用数据容器

`data` 包提供两类高频数据结构，**详细用法请阅读 [data/README.md](data/README.md)**。此处仅作概览。

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

---

## 五、与传统方法的对比

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

---

## 六、快速示例

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

> 更多场景（异构纠缠、去重、容器突变、通道级监听）见 [data/README.md](data/README.md)。

---

## 七、Spring 集成

[`CoreSpringConfig`](config/CoreSpringConfig.java) 遵循项目统一的装配模式：

```java
@Configuration
@ComponentScan("io.github.JiangHu.jframe.core")
@ImportResource("classpath:core-spring.xml")
```

由 [`MainSpringConfig`](../../main/config/MainSpringConfig.java) 第一个 `@Import`。`PluginAware` Bean 在容器刷新后被 [`JFrameMain`](../../main/JFrameMain.java) 自动绑定插件实例。

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
