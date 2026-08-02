# jframe_thread — 维护者文档

> 面向模块维护者。记录 [`ThreadAPI`](ThreadAPI.java) 的内部架构、设计决策、线程安全细节与扩展方式。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、模块结构](#一模块结构)
- [二、核心数据结构](#二核心数据结构)
- [三、线程安全设计](#三线程安全设计)
- [四、线程工厂与守护线程](#四线程工厂与守护线程)
- [五、任务执行与异常包装](#五任务执行与异常包装)
- [六、关闭流程](#六关闭流程)
- [七、Spring 装配](#七spring-装配)
- [八、设计决策记录](#八设计决策记录)
- [九、扩展指南](#九扩展指南)

---

## 一、模块结构

```
jframe_thread/
├── pom.xml                          # 依赖父 JFrame + JUnit 5
└── src/main/
    ├── java/.../thread/
    │   ├── ThreadAPI.java           # 唯一公开类，全部逻辑集中于此
    │   └── config/
    │       └── ThreadSpringConfig.java   # Spring 装配入口
    └── resources/
        └── thread-spring.xml        # 定义 threadAPI Bean
```

模块刻意保持**单类核心**（[`ThreadAPI`](ThreadAPI.java)），没有抽象出 `ExecutorManager` 接口或拆分多个类。原因：职责单一、调用路径短、维护成本低。若未来需要多实现（如池化策略可配置），再抽取接口。

---

## 二、核心数据结构

[`ThreadAPI`](ThreadAPI.java) 的全部状态字段：

| 字段 | 类型 | 作用 |
|------|------|------|
| [`DEFAULT_AWAIT_TIME`](ThreadAPI.java:45) | `int` 常量 | 关闭等待时间默认值（秒），固定为 `1` |
| [`awaitTime`](ThreadAPI.java:55) | `Integer` | 关闭队列时 `awaitTermination` 的超时（秒），实例级可配置 |
| [`exceptionHandler`](ThreadAPI.java:65) | `Consumer<Exception>` | 全局异常处理器，`null` 表示忽略 |
| [`threadExecutors`](ThreadAPI.java:72) | `ConcurrentHashMap<String, ExecutorService>` | 名称 → 执行器映射，核心存储 |
| [`threadIndex`](ThreadAPI.java:77) | `AtomicInteger` | 工作线程序号生成器，用于线程命名 |

`threadExecutors` 是整个模块的「单一事实来源」：所有队列的创建、查询、移除、关闭都围绕它进行。

---

## 三、线程安全设计

### 为什么用 `ConcurrentHashMap`

[`threadExecutors`](ThreadAPI.java:72) 选择 `ConcurrentHashMap` 而非普通 `HashMap` 或 `Collections.synchronizedMap`：

- **读多写少**：[`pushTask()`](ThreadAPI.java:134) 每次都要 `get` 查找执行器，而 `ConcurrentHashMap` 的读操作无锁。
- **避免全局锁**：`synchronizedMap` 每次读写都加同一把锁，在多业务并发提交任务时成为瓶颈。

### `computeIfAbsent` 保证创建幂等

[`createThreadTask()`](ThreadAPI.java:90) 的核心是：

```java
threadExecutors.computeIfAbsent(name, this::createNamedExecutor);
```

`ConcurrentHashMap.computeIfAbsent` 是**原子操作**：即使多个线程同时用相同 `name` 调用 [`createThreadTask()`](ThreadAPI.java:90)，也只会创建**一个**执行器，工厂函数 `createNamedExecutor` 只会被调用一次。这避免了「先 `containsKey` 再 `put`」的 check-then-act 竞态（该写法在并发下会创建重复执行器并泄漏线程）。

### `pushTask` 的 NPE 语义

[`pushTask()`](ThreadAPI.java:134) 通过 `get` 取执行器，若返回 `null` 则**故意抛 `NullPointerException`**（解引用 `null`）。这是有意的「快速失败」设计：

- 强制调用方先 [`createThreadTask()`](ThreadAPI.java:90)，避免任务被静默丢弃。
- 比返回 `boolean` 或打印警告更能暴露调用方逻辑错误。

---

## 四、线程工厂与守护线程

[`createNamedExecutor()`](ThreadAPI.java:104) 构造单线程执行器时，传入自定义 [`ThreadFactory`](ThreadAPI.java:113)：

```java
new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, "jframe-thread-" + name + "-" + threadIndex.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
```

**设计要点**：

1. **命名规范** `jframe-thread-{name}-{n}`：`name` 是队列名，`n` 由 [`threadIndex`](ThreadAPI.java:77) 全局递增。在线程转储（jstack）、日志、APM 中可直接定位到来源队列，大幅降低排查成本。

2. **守护线程** `setDaemon(true)`：工作线程设为守护线程，JVM 退出时不会因这些线程存活而阻塞。框架停服时 [`stopAll()`](ThreadAPI.java:166) 会主动关闭，守护属性是兜底保障。

3. **单线程执行器** `newSingleThreadExecutor`：保证队列内任务串行。若执行中任务因异常退出，执行器会自动创建新线程继续处理后续任务（`SingleThreadExecutor` 的内置行为）。

---

## 五、任务执行与异常包装

[`pushTask()`](ThreadAPI.java:134) 提交任务时，用 [`runTaskWrapper()`](ThreadAPI.java:207) 包装原始 `Runnable`：

```java
executor.submit(() -> runTaskWrapper(runnable));
```

[`runTaskWrapper()`](ThreadAPI.java:207) 的实现：

```java
protected void runTaskWrapper(Runnable runnable) {
    try {
        runnable.run();
    } catch (Exception e) {
        if (exceptionHandler != null) {
            exceptionHandler.accept(e);
        }
    }
}
```

**为什么需要包装层**：

- **隔离异常**：`ExecutorService.submit()` 返回的 `Future` 会缓存任务异常，若无人 `get()` 则异常被吞。包装层在任务线程内当场捕获，确保异常有出口。
- **不中断队列**：捕获后方法正常返回，执行器不会因异常终止，后续任务继续执行。
- **统一处理**：所有任务走同一 [`exceptionHandler`](ThreadAPI.java:65)，便于集中日志、监控、告警。

> `protected` 修饰便于子类覆盖（如增加重试、指标上报）。

---

## 六、关闭流程

### 单队列关闭

[`removeThreadTask()`](ThreadAPI.java:151) → [`shutdownExecutor()`](ThreadAPI.java:190)：

```java
private void shutdownExecutor(ExecutorService executor) {
    executor.shutdown();                              // 1. 停止接收新任务
    try {
        if (!executor.awaitTermination(awaitTime, TimeUnit.SECONDS)) {
            executor.shutdownNow();                   // 2. 超时则强制中断
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();           // 3. 恢复中断标志
    }
}
```

**三阶段关闭**：优雅关闭 → 超时强杀 → 中断恢复。`awaitTime`（默认 1 秒）控制容忍已提交任务继续运行的时间。

### 全量关闭

[`stopAll()`](ThreadAPI.java:166) 遍历 [`threadExecutors`](ThreadAPI.java:72) 逐个关闭并 `clear()`。[`close()`](ThreadAPI.java:178) 直接委托 [`stopAll()`](ThreadAPI.java:166)，提供符合 `AutoCloseable` 语义的入口（虽然本类未实现该接口，但保留了关闭习惯）。

---

## 七、Spring 装配

模块遵循项目统一的 `Config + XML` 双层装配：

1. [`ThreadSpringConfig`](../config/ThreadSpringConfig.java)：
   ```java
   @Configuration
   @ComponentScan("io.github.JiangHu.jframe.thread")
   @ImportResource("classpath:thread-spring.xml")
   ```
   `@ComponentScan` 扫描模块内 `@Component`（当前无）；`@ImportResource` 引入 XML。

2. [`thread-spring.xml`](../../../../../../../resources/thread-spring.xml)：
   ```xml
   <bean class="io.github.JiangHu.jframe.async.thread.ThreadAPI" id="threadAPI"/>
   ```
   显式声明 Bean，`id="threadAPI"` 作为注入名。

3. 在 `jframe_main` 环境下，[`MainSpringConfig`](../main/config/MainSpringConfig.java) 统一 `@Import(ThreadSpringConfig.class)`，业务侧通过 [`JFrameMain.getThreadAPI()`](../main/JFrameMain.java) 获取。

---

## 八、设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 执行器类型 | `newSingleThreadExecutor` | 队列内任务需串行（如玩家数据顺序落盘） |
| 线程映射容器 | `ConcurrentHashMap` | 读多写少，读无锁 |
| 创建方式 | `computeIfAbsent` | 原子幂等，杜绝并发重复创建 |
| 线程类型 | 守护线程 | JVM 退出不阻塞，框架兜底 |
| 异常处理 | 包装层 + 可选 handler | 异步异常无法被调用方捕获，需统一出口 |
| 关闭策略 | shutdown → awaitTermination → shutdownNow | 优雅优先，超时兜底 |
| 模块形态 | 单类核心 | 职责单一，避免过度抽象 |

---

## 九、扩展指南

### 增加池化策略（如固定线程数）

当前每个队列固定单线程。若某队列需要并行（如批量网络请求），可在 [`createNamedExecutor()`](ThreadAPI.java:104) 中按名称策略选择 `newFixedThreadPool`：

```java
private ExecutorService createNamedExecutor(String name) {
    int size = resolvePoolSize(name);   // 按名解析线程数
    return Executors.newFixedThreadPool(size, buildFactory(name));
}
```

注意：多线程会破坏「同队列串行」语义，需在文档中明确区分。

### 增加任务指标

覆盖 [`runTaskWrapper()`](ThreadAPI.java:207)（`protected`）记录执行耗时、成功/失败计数：

```java
@Override
protected void runTaskWrapper(Runnable runnable) {
    long start = System.nanoTime();
    super.runTaskWrapper(runnable);
    metrics.record(name, System.nanoTime() - start);
}
```

### 增加拒绝策略

当前 [`pushTask()`](ThreadAPI.java:134) 在队列关闭后会触发执行器的拒绝（默认 `AbortPolicy` 抛 `RejectedExecutionException`）。如需自定义，在 [`createNamedExecutor()`](ThreadAPI.java:104) 改用 `ThreadPoolExecutor` 显式设置 `RejectedExecutionHandler`。

> 修改核心逻辑前，请同步更新 [README.md](README.md) 的 API 说明与本文件的决策记录。
