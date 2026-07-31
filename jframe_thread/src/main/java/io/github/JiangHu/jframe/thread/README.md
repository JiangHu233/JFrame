# jframe_thread — 异步任务队列

> 基于「命名线程池」的异步任务管理。每个任务队列用名称标识，内部为单线程执行器，**保证同一队列内的任务按提交顺序串行执行**。

---

## 📑 目录

- [一、这个模块解决什么问题](#一这个模块解决什么问题)
- [二、与传统方法的对比](#二与传统方法的对比)
- [三、快速开始](#三快速开始)
- [四、核心概念](#四核心概念)
- [五、API 详解](#五api-详解)
- [六、异常处理](#六异常处理)
- [七、生命周期管理](#七生命周期管理)
- [八、Spring 集成](#八spring-集成)
- [九、完整示例](#九完整示例)
- [十、注意事项](#十注意事项)

---

## 一、这个模块解决什么问题

Nukkit 服务端的主线程负责处理游戏逻辑（方块更新、实体移动、网络包等），任何耗时操作（数据库读写、文件 IO、网络请求）若放在主线程都会造成卡服。本模块提供一个**轻量的命名队列**抽象：

- **按名隔离**：不同业务用不同名称的队列（如 `"data-save"`、`"web-request"`），互不阻塞。
- **队列内串行**：同一队列的任务依次执行，天然适合「写文件 → 备份 → 通知」这类有先后依赖的流程，无需手动加锁。
- **统一异常处理**：任务抛出的异常不会中断队列，可由全局处理器统一接管。
- **可调试**：工作线程命名为 `jframe-thread-{队列名}-{序号}`，在线程转储与日志中一眼定位来源。

---

## 二、与传统方法的对比

| 维度 | 传统 `scheduleAsyncTask` / 手写 `ExecutorService` | 本模块 [`ThreadAPI`](ThreadAPI.java) |
|------|------|------|
| **串行保证** | `scheduleAsyncTask` 每次调度独立线程，**无法保证同系列任务顺序执行**；手写执行器需自行管理 | 同名队列内任务**天然串行**（单线程执行器） |
| **多队列管理** | 需要为每个业务各自创建并持有 `ExecutorService`，散落各处 | 一个 [`ThreadAPI`](ThreadAPI.java) 统一管理任意数量的命名队列 |
| **线程命名** | 默认线程名 `pool-N-thread-M`，无法区分来源 | `jframe-thread-{name}-{n}`，直接体现所属队列 |
| **异常处理** | 任务异常默认打印到控制台或被吞，需每个任务各自 try/catch | 全局 [`exceptionHandler`](ThreadAPI.java:65) 统一接管 |
| **生命周期** | 需手动 `shutdown` 每个执行器，易遗漏导致线程泄漏 | [`stopAll()`](ThreadAPI.java:166) / [`removeThreadTask()`](ThreadAPI.java:151) 一键关闭，框架停服时自动调用 |
| **并发创建** | 手写 `if (!contains) put` 存在竞态，可能创建重复执行器并泄漏 | 基于 `computeIfAbsent`，并发创建同名队列安全 |

> **何时用本模块**：需要一组「按顺序执行、互不干扰」的异步任务（如玩家数据串行落盘、消息队列消费）。若只需一次性延迟 / 定时任务，Nukkit 自带的 `getScheduler()` 更合适。

---

## 三、快速开始

```java
// 获取 ThreadAPI（前置插件模式下由 JFrameMain 装配）
ThreadAPI threadAPI = jframeMain.getThreadAPI();

// 1. 创建一个名为 "data-save" 的任务队列
threadAPI.createThreadTask("data-save");

// 2. 向该队列提交任务（按提交顺序串行执行）
threadAPI.pushTask("data-save", () -> {
    savePlayerData();   // 异步执行，不阻塞主线程
});

threadAPI.pushTask("data-save", () -> {
    backupToFile();     // 会在上一个任务完成后才开始
});

// 3. 不再需要时关闭该队列（等待已提交任务完成）
threadAPI.removeThreadTask("data-save");
```

---

## 四、核心概念

### 命名队列（Named Task Queue）

每个队列由一个**名称字符串**标识，内部对应一个单线程执行器（[`Executors.newSingleThreadExecutor()`](ThreadAPI.java:110)）。向同一名称提交的多个任务，会由**同一个工作线程**按提交顺序依次执行——因此天然串行，无需额外同步。

```
ThreadAPI
├── "data-save"   队列 → [任务A] → [任务B] → [任务C]   （单线程，串行）
├── "web-request" 队列 → [任务X] → [任务Y]             （独立线程，与上面并行）
└── "chat-log"    队列 → [任务1] → [任务2]             （独立线程）
```

不同名称的队列各自拥有独立线程，彼此并行；同一名称内的任务串行。

### 守护线程

所有工作线程均设为**守护线程**（[`setDaemon(true)`](ThreadAPI.java:107)），在插件卸载或 JVM 关闭时不会阻塞退出。

---

## 五、API 详解

[`ThreadAPI`](ThreadAPI.java) 的全部公开方法：

### 队列管理

| 方法 | 说明 |
|------|------|
| [`createThreadTask(name)`](ThreadAPI.java:90) | 创建命名队列。若已存在则保持不变（基于 `computeIfAbsent`，并发安全） |
| [`getThreadExecutor(name)`](ThreadAPI.java:119) | 获取指定队列的执行器；不存在返回 `null` |
| [`removeThreadTask(name)`](ThreadAPI.java:151) | 移除并关闭单个队列，等待已提交任务完成后停止。存在并移除返回 `true`，不存在返回 `false` |
| [`stopAll()`](ThreadAPI.java:166) | 关闭**所有**队列 |
| [`close()`](ThreadAPI.java:178) | 等价于 [`stopAll()`](ThreadAPI.java:166)，语义更直观的关闭入口 |

### 任务提交

| 方法 | 说明 |
|------|------|
| [`pushTask(name, runnable)`](ThreadAPI.java:134) | 向指定队列提交任务。**队列必须已创建**，否则抛出 `NullPointerException` |

```java
threadAPI.createThreadTask("guild");
threadAPI.pushTask("guild", () -> disbandGuild(id));   // ✓
threadAPI.pushTask("unknown", () -> {});               // ✗ 抛 NullPointerException
```

### 可配置项

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| [`awaitTime`](ThreadAPI.java:55) | `Integer`（秒） | [`DEFAULT_AWAIT_TIME`](ThreadAPI.java:45) = `1` | 关闭队列时等待已提交任务完成的最长时间 |
| [`exceptionHandler`](ThreadAPI.java:65) | `Consumer<Exception>` | `null` | 全局异常处理器；为 `null` 时异常被静默忽略 |

```java
// 调整关闭等待时间（按实例，不影响其他实例）
threadAPI.setAwaitTime(3);

// 设置全局异常处理器
threadAPI.setExceptionHandler(e -> getLogger().error("异步任务异常", e));
```

---

## 六、异常处理

任务执行过程中抛出的 `Exception` 会被 [`runTaskWrapper()`](ThreadAPI.java:207) 统一捕获，**不会中断队列**，也不会影响后续任务：

- 若设置了 [`exceptionHandler`](ThreadAPI.java:65)，异常交由其处理（如记录日志、告警）。
- 若未设置（`null`），异常被静默忽略。

```java
threadAPI.setExceptionHandler(e -> {
    getLogger().error("队列任务执行失败: " + e.getMessage(), e);
    // 可选：通知管理员、写入监控等
});
```

> **设计意图**：异步任务的异常无法被调用方直接 try/catch（调用 `pushTask` 时任务尚未执行）。统一处理器让异常有明确的出口，避免「任务静默失败、问题难以排查」。

---

## 七、生命周期管理

### 关闭单个队列

[`removeThreadTask(name)`](ThreadAPI.java:151) 会调用 [`shutdownExecutor()`](ThreadAPI.java:190)：先 `shutdown()`（停止接收新任务），再在 [`awaitTime`](ThreadAPI.java:55) 秒内 `awaitTermination`（等待已提交任务完成）。

```java
if (threadAPI.removeThreadTask("data-save")) {
    getLogger().info("data-save 队列已关闭");
}
```

### 关闭所有队列

[`stopAll()`](ThreadAPI.java:166) 遍历关闭全部队列并清空映射。框架停服时由 [`JFrameMain.onDisable()`](../main/JFrameMain.java) 自动调用，无需业务插件手动处理。

---

## 八、Spring 集成

模块遵循项目的 `Config + XML` 装配模式：

- [`ThreadSpringConfig`](config/ThreadSpringConfig.java)：`@Configuration` + `@ComponentScan` + `@ImportResource("classpath:thread-spring.xml")`。
- [`thread-spring.xml`](../../../../../../../resources/thread-spring.xml)：定义 `id="threadAPI"` 的 Bean。

在 `jframe_main` 环境下，[`MainSpringConfig`](../main/config/MainSpringConfig.java) 已统一 `@Import` 本模块，业务插件通过 [`JFrameMain.getThreadAPI()`](../main/JFrameMain.java) 即可获取。

---

## 九、完整示例

### 场景：玩家数据串行落盘

多个玩家的数据保存需要异步执行，但**同一玩家的保存必须串行**（避免并发写同一文件冲突）：

```java
public void onPlayerQuit(Player player) {
    String queueName = "save-" + player.getName();

    threadAPI.createThreadTask(queueName);
    threadAPI.pushTask(queueName, () -> {
        DataSaver saver = jframeMain.getDataSaver();
        saver.save(playerData, "players/" + player.getName());
    });
    // 玩家专属队列，退出后清理
    threadAPI.removeThreadTask(queueName);
}
```

### 场景：全局异常监控

```java
@Override
public void onEnable() {
    ThreadAPI threadAPI = jframeMain.getThreadAPI();
    threadAPI.setExceptionHandler(e ->
            getLogger().warning("异步任务异常: " + e.getMessage()));

    threadAPI.createThreadTask("bg-jobs");
    threadAPI.pushTask("bg-jobs", () -> runBackgroundJob());
}
```

---

## 十、注意事项

- **队列须先创建**：[`pushTask()`](ThreadAPI.java:134) 不会自动创建队列，调用前必须 [`createThreadTask()`](ThreadAPI.java:90)，否则抛 `NullPointerException`。
- **跨线程操作主线程**：任务运行在异步线程，若需修改方块、传送实体、发送表单等**主线程操作**，应通过 Nukkit `getScheduler().scheduleTask()` 调度回主线程，切勿直接在异步任务中调用非线程安全的 API。
- **不要提交阻塞型长任务**：单线程队列内的任务串行，一个长时间阻塞（如无限循环、长时间网络等待）会卡住该队列后续所有任务。需要周期性任务请用 Nukkit 调度器。
- **及时清理无用队列**：每个队列占用一个线程，长期不用的队列应 [`removeThreadTask()`](ThreadAPI.java:151) 关闭，避免资源占用。

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
