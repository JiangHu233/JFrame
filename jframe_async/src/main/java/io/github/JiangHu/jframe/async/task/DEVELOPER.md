# jframe_task — 维护者文档

> 面向模块维护者。记录 [`SuspendableTask`](SuspendableTask.java) 与 [`TaskAPI`](TaskAPI.java) 的内部架构、设计决策、线程安全细节与扩展方式。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、模块结构](#一模块结构)
- [二、核心数据结构](#二核心数据结构)
- [三、状态机详解](#三状态机详解)
- [四、线程安全设计](#四线程安全设计)
- [五、幂等触发的设计](#五幂等触发的设计)
- [六、异常处理](#六异常处理)
- [七、Spring 装配](#七spring-装配)
- [八、设计决策记录](#八设计决策记录)
- [九、测试策略](#九测试策略)
- [十、扩展指南](#十扩展指南)

---

## 一、模块结构

```
jframe_async/
└── src/main/
    ├── java/io/github/JiangHu/jframe/async/
    │   ├── config/
    │   │   └── AsyncSpringConfig.java       # @ComponentScan + @ImportResource
    │   ├── task/
    │   │   ├── SuspendableTask.java         # 抽象基类 + Functional 内部子类
    │   │   ├── TaskAPI.java                 # 管理器（PluginAware）
    │   │   ├── README.md                    # 使用文档
    │   │   └── DEVELOPER.md                 # 本文件
    │   └── thread/
    │       └── ThreadAPI.java               # 异步线程池（互补模块）
    └── resources/
        └── async-spring.xml                 # threadAPI + taskAPI Bean 定义
```

模块刻意保持**双类核心**（[`SuspendableTask`](SuspendableTask.java) + [`TaskAPI`](TaskAPI.java)），职责分离：
- [`SuspendableTask`](SuspendableTask.java)：单个任务的生命周期与状态机
- [`TaskAPI`](TaskAPI.java)：任务注册表与插件绑定

---

## 二、核心数据结构

### [`SuspendableTask`](SuspendableTask.java) 字段

| 字段 | 类型 | 作用 |
|------|------|------|
| [`plugin`](SuspendableTask.java:62) | `Plugin` (final) | 关联插件，注册 Nukkit 调度 |
| [`interval`](SuspendableTask.java:65) | `int` (final) | 调度间隔（tick） |
| `taskHandler` | `TaskHandler` | Nukkit 调度句柄，null = 未注册 |
| `running` | `volatile boolean` | 运行态标志，独立于 taskHandler |
| `finished` | `volatile boolean` | 是否自然完成（isValid 返回 false） |

### [`TaskAPI`](TaskAPI.java) 字段

| 字段 | 类型 | 作用 |
|------|------|------|
| [`plugin`](TaskAPI.java:50) | `Plugin` | 由 PluginAware 注入 |
| `tasks` | `ConcurrentHashMap<String, SuspendableTask>` | 名称 → 任务映射 |

---

## 三、状态机详解

```
         execute()                    isValid()==false
Idle ──────────────▶ Running ─────────────────────▶ Idle
 ▲                     │                               │
 │                     │ 每 interval tick:              │
 │                     │   isValid==true → onTick()     │ + onFinish()
 │                     │   isValid==false → cancel()    │
 │                     │                                │
 │   cancel()          │   execute() (已在跑)            │
 └────────────────────┘   = no-op (保持)                │
                                                          │
         execute() (恢复) ◀──────────────────────────────┘
```

### 关键转换

| 转换 | 触发 | 内部动作 |
|------|------|----------|
| Idle → Running | `execute()` | `onStart()` → `running=true` → `scheduleTask()` |
| Running → Running | `tick()` + `isValid()==true` | `onTick()` |
| Running → Idle | `tick()` + `isValid()==false` | `finished=true` → `cancel()` → `onFinish()` |
| Running → Idle | 外部 `cancel()` | `running=false` → `taskHandler.cancel()`（**不触发 onFinish**） |
| Idle → Running | `execute()`（恢复） | 同首次启动，`finished` 重置为 false |

---

## 四、线程安全设计

### 为什么用 `volatile running` 而非 `taskHandler != null`

运行态判据使用独立的 `volatile boolean running` 字段，而非 `taskHandler != null`：

- `taskHandler` 由 `scheduleTask()` 赋值，该方法在生产环境调用 `Server.getInstance()`，在测试中被覆盖返回 null
- 若用 `taskHandler != null` 判断运行态，测试中 `scheduleTask()` 返回 null 会导致状态机无法正确工作
- `volatile running` 作为**单一事实来源**，与 Nukkit 调度句柄解耦，更健壮

### `synchronized` 的使用

[`execute()`](SuspendableTask.java:170) 与 [`cancel()`](SuspendableTask.java:184) 均为 `synchronized` 方法：

- 防止多线程并发调用 `execute()` 导致重复注册调度
- 防止 `cancel()` 与 `execute()` 竞态导致状态不一致
- 临界区极短（仅字段读写 + 一次调度注册），不会造成性能问题

[`tick()`](SuspendableTask.java:232) 也为 `synchronized`，由 Nukkit 主线程调用，与外部 `execute()`/`cancel()` 互斥。

### `TaskAPI` 注册表

使用 `ConcurrentHashMap`，与 [`ThreadAPI`](../thread/ThreadAPI.java) 的设计一致：
- 读多写少（`execute(name)` / `isRunning(name)` 频繁查询）
- `computeIfAbsent` 保证 `createTask()` 的创建幂等

---

## 五、幂等触发的设计

[`execute()`](SuspendableTask.java:170) 的核心逻辑：

```java
public synchronized void execute() {
    if (running) {
        return; // 正在执行 → 保持，不重复注册
    }
    onStart();          // 先初始化；若抛异常则不进入运行态
    running = true;
    finished = false;
    taskHandler = scheduleTask();
}
```

**设计要点**：

1. **先检查后执行**：`if (running) return` 保证幂等，不会重复注册 `scheduleRepeatingTask`
2. **onStart 先于状态变更**：若 `onStart()` 抛异常，`running` 保持 false，任务不进入不一致状态
3. **scheduleTask 可覆盖**：`protected` 修饰，测试可覆盖为不调用真实 Nukkit 调度器

---

## 六、异常处理

[`tick()`](SuspendableTask.java:232) 内捕获 `onTick()` 的异常：

```java
synchronized void tick() {
    if (!isValid()) {
        finished = true;
        cancel();
        onFinish();
        return;
    }
    try {
        onTick();
    } catch (Exception e) {
        Server.getInstance().getLogger().error(
                "SuspendableTask tick 异常: " + getClass().getName(), e);
    }
}
```

**策略**（与 [`ThreadAPI.runTaskWrapper`](../thread/ThreadAPI.java:207) 一致）：
- 异常被捕获并记录，**不中断后续 tick**
- 任务继续运行，直到 `isValid()` 返回 false
- 若需在异常时停止，在 `isValid()` 中检查错误状态

---

## 七、Spring 装配

模块遵循项目统一的 `Config + XML` 模式，Bean 定义全部在 XML 中：

1. [`AsyncSpringConfig`](../config/AsyncSpringConfig.java)：
   ```java
   @Configuration
   @ComponentScan("io.github.JiangHu.jframe.async")
   @ImportResource("classpath:async-spring.xml")
   ```

2. [`async-spring.xml`](../../../../../../../resources/async-spring.xml)：
   ```xml
   <bean class="io.github.JiangHu.jframe.async.thread.ThreadAPI" id="threadAPI"/>
   <bean class="io.github.JiangHu.jframe.async.task.TaskAPI" id="taskAPI"/>
   ```

3. [`TaskAPI`](TaskAPI.java) 实现 [`PluginAware`](../../../../../../../../../core/src/main/java/io/github/JiangHu/jframe/core/module/PluginAware.java)，
   由 `JFrameMain.bindPlugin()` 自动注入插件实例。

4. 业务侧通过 `jframeMain.getTaskAPI()` 或 ServiceManager 获取。

---

## 八、设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 调度方式 | `scheduleRepeatingTask` + 完成时 cancel | 与 NavigatorManager/WanderBehavior 一致，简洁可靠 |
| 运行态判据 | `volatile boolean running` | 与 taskHandler 解耦，测试友好 |
| 线程同步 | `synchronized` 方法 | 临界区极短，简单可靠 |
| 异常处理 | 捕获 + 记录，不中断 | 与 ThreadAPI 一致，保证队列不因单次异常停止 |
| onStart 时机 | scheduleTask 前 | 初始化失败时不进入运行态 |
| onFinish 触发 | 仅自然完成 | 手动 cancel 不触发，语义清晰 |
| 模块形态 | 双类核心（Task + TaskAPI） | 职责分离：状态机 vs 注册表 |
| Bean 定义 | XML（非注解） | 遵循项目统一约定 |

---

## 九、测试策略

[`SuspendableTaskLogicTest`](../../../../../test/java/io/github/JiangHu/jframe/async/task/SuspendableTaskLogicTest.java) 共 40 个测试用例，覆盖：

| 测试组 | 覆盖内容 |
|--------|----------|
| 构造校验 | null plugin → NPE，interval ≤ 0 → IAE，null lambda → NPE |
| Functional 委托 | onTick/isValid 正确委托给 Lambda |
| 状态机 | 初始态、execute 启动、幂等触发、tick 执行、自动挂起、手动 cancel、恢复、完整生命周期 |
| TaskAPI 注册表 | createTask 幂等、register、execute(name)、cancel(name)、cancelAll、插件绑定 |

### 无需 Nukkit 服务器的测试方法

测试通过覆盖 [`scheduleTask()`](SuspendableTask.java:222) 避免调用真实 `Server.getInstance()`：

```java
static class TestableSuspendableTask extends SuspendableTask {
    @Override
    protected TaskHandler scheduleTask() {
        scheduleCount.incrementAndGet();
        return null; // 不实际注册调度
    }
    // ...
}
```

`tick()` 为包级可见，测试直接调用以模拟调度器的 tick 触发。

Plugin 通过 JDK 动态代理 mock，对所有方法返回默认值。

---

## 十、扩展指南

### 支持异步线程执行

当前任务在主线程执行（`scheduleRepeatingTask` 默认同步）。若需异步执行，覆盖 [`scheduleTask()`](SuspendableTask.java:222)：

```java
@Override
protected TaskHandler scheduleTask() {
    return Server.getInstance().getScheduler()
            .scheduleRepeatingTask(plugin, this::tick, interval, true); // true = async
}
```

注意：异步执行时 `onTick()` 不能直接操作方块/实体等非线程安全 API。

### 增加任务指标

覆盖 `onStart()` / `onFinish()` / `onTick()` 记录执行次数、耗时：

```java
private long startTime;

@Override
protected void onStart() {
    startTime = System.nanoTime();
}

@Override
protected void onFinish() {
    long elapsed = System.nanoTime() - startTime;
    metrics.record(getName(), elapsed);
}
```

### 自定义调度策略

覆盖 [`scheduleTask()`](SuspendableTask.java:222) 可实现任意调度策略（如动态间隔、延迟启动等）。

> 修改核心逻辑前，请同步更新 [README.md](README.md) 的 API 说明与本文件的决策记录。
