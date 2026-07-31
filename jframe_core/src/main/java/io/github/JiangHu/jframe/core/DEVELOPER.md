# jframe_core — 维护者文档

> 面向模块维护者。记录核心基础设施的实现原理：PluginAware 绑定机制、纠缠值的并发模型与广播流程、函数式映射的键源设计。
> 使用文档请看 [README.md](README.md)，数据容器详细文档请看 [data/README.md](data/README.md)。

---

## 📑 目录

- [一、模块职责与依赖](#一模块职责与依赖)
- [二、PluginAware 绑定机制](#二pluginaware-绑定机制)
- [三、纠缠值的并发模型](#三纠缠值的并发模型)
- [四、两阶段广播流程](#四两阶段广播流程)
- [五、异构纠缠的实现](#五异构纠缠的实现)
- [六、函数式映射的键源设计](#六函数式映射的键源设计)
- [七、扩展指南](#七扩展指南)

---

## 一、模块职责与依赖

`jframe_core` 是纯底层模块，**不依赖任何业务模块**，只依赖 Nukkit（`Plugin` 类型）与 Lombok。它被 `jframe_main` 第一个装配，其他模块（event / form / command 等）间接依赖它提供的 [`PluginAware`](module/PluginAware.java) 与数据容器。

两大职责互不耦合：
- `module/` —— 解决「Bean 如何拿到 Plugin 实例」。
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

## 三、纠缠值的并发模型

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

## 四、两阶段广播流程

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

## 五、异构纠缠的实现

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

## 六、函数式映射的键源设计

[`AbstractFunctionalMap<S, K>`](data/map/AbstractFunctionalMap.java) 的核心设计是**键源 `S` 与内部键 `K` 分离**：

- 所有读写方法（`get` / `put` / `remove` / `containsKey` / `getOrCreate`）以**键源 `S`** 为参数。
- 内部经 `keyExtractor`（`Function<S, K>`）转换为真正的存储键 `K`。
- `K` 对使用者不透明。

**为什么分离**：

- [`PlayerDataMap`](data/map/PlayerDataMap.java) 取 `S=Player`、`K=String`（玩家名）：调用方传 `Player` 对象，内部自动提取名字作为键，退服时按玩家清理。调用方无需关心键是什么。
- [`LruCacheMap`](data/map/LruCacheMap.java) 取 `S=K`（`keyExtractor` 默认 `identity`）：键即源，退化成普通缓存。

**可插拔功能点**：键提取、值加载（`getOrCreate`）、过期回调、淘汰回调均可通过函数式参数定制，子类只需选择泛型与提供策略。

---

## 七、扩展指南

### 新增一个 PluginAware Bean

1. 让 Bean 实现 [`PluginAware`](module/PluginAware.java)。
2. 在 `bindPlugin` 中保存 `Plugin` 引用并完成初始化。
3. 确保 Bean 被 Spring 管理（`@Component` 或 XML 声明）。
4. 无需其他配置——[`JFrameMain.bindPlugin()`](../../main/JFrameMain.java) 会自动发现并调用。

### 新增一个数据结构

- **新的响应式容器**：若需要不同于 `EntangledValue` 的语义（如带版本的值、限流通知），可实现 [`Entangled`](data/reactive/Entangled.java) 接口加入现有纠缠体系，复用通道与广播机制。
- **新的映射类型**：继承 [`AbstractFunctionalMap`](data/map/AbstractFunctionalMap.java)，指定 `<S, K>` 泛型与 `keyExtractor`，复用加载 / 过期 / 淘汰框架。

### 修改纠缠体系并发策略

当前全局静态锁 [`EntangledChannel.LOCK`](data/reactive/EntangledChannel.java) 是简单性与安全性的权衡。若未来值变更频率极高成为瓶颈，可考虑：

- 细化为每通道锁，但必须妥善处理通道合并（`mergeFrom`）的锁顺序，避免死锁。
- 或改用无锁结构（如 `ConcurrentHashMap` 存储成员 + CAS 更新值），但广播的原子性保障会更复杂。

> 任何修改请同步更新 [data/README.md](data/README.md) 的线程安全说明与测试覆盖（[`EntangledValueTest`](../../../../../test/java/io/github/JiangHu/jframe/core/data/reactive/EntangledValueTest.java) 含 17 类场景、65 个断言）。
