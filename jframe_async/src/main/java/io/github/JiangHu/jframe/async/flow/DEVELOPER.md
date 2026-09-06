# jframe_flow — 维护者文档

> 面向模块维护者。记录 [`ThreadFlow`](ThreadFlow.java) 的内部架构、设计决策、测试策略与扩展方式。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、架构概览](#一架构概览)
- [二、核心设计决策](#二核心设计决策)
- [三、方法可见性策略](#三方法可见性策略)
- [四、测试策略](#四测试策略)
- [五、扩展指南](#五扩展指南)
- [六、性能考量](#六性能考量)
- [七、常见问题](#七常见问题)

---

## 一、架构概览

```
用户代码
  └── ThreadFlow（编排层：决定任务在哪执行、如何切换）
        ├── FlowChain（回调链模式：CompletableFuture 链式编排）
        │     ├── async / thenAsync     → ExecutorService（ThreadAPI 命名线程池）
        │     └── main / thenMain       → scheduleOnMain → Nukkit ServerScheduler
        ├── FlowContext（虚拟线程模式：park/unpark 同步风格）
        │     ├── awaitAsync / fireAsync → ExecutorService（ThreadAPI 命名线程池）
        │     └── awaitMain / fireMain   → scheduleOnMain → Nukkit ServerScheduler
        └── ThreadAPI（基础设施：命名线程池、生命周期、异常处理）
              └── ExecutorService（实际 OS 线程，单线程串行执行）
```

### 类职责

| 类 | 职责 | 依赖 |
|----|------|------|
| [`ThreadFlow`](ThreadFlow.java:59) | 编排入口，Spring Bean，持有 ThreadAPI 和 Plugin | ThreadAPI, Plugin |
| [`FlowChain`](FlowChain.java:53) | 回调链对象，基于 CompletableFuture 的流式编排 | ThreadFlow |
| [`FlowContext`](FlowContext.java) | 虚拟线程上下文，提供 await/fire 方法 | ThreadFlow |

---

## 二、核心设计决策

### 1. ThreadFlow 作为 Spring Bean

**决策**：ThreadFlow 是 Spring 单例 Bean，通过构造器注入 ThreadAPI，通过 PluginAware 注入 Plugin。

**理由**：
- 业务侧通过 `JFrameMain.getThreadFlow()` 获取，无需手动传参
- ThreadAPI 由 Spring 管理，保证全局唯一
- Plugin 由 JFrameMain 在 `bindPlugin()` 阶段自动注入

**实现**：
```java
// async-spring.xml
<bean class="io.github.JiangHu.jframe.async.flow.ThreadFlow" id="threadFlow">
    <constructor-arg ref="threadAPI"/>
</bean>
```

### 2. 双模式设计（回调链 + 虚拟线程）

**决策**：提供两种互补的模式，而非只选一种。

**理由**：
- 回调链（FlowChain）：Java 8+ 兼容，非阻塞，适合简单线性流程
- 虚拟线程（FlowContext）：Java 21+，同步风格，适合复杂流程（条件、循环、嵌套）
- 两种模式共享底层 ThreadAPI 和 scheduleOnMain，无额外基础设施成本

### 3. MainThreadBridge 模式

**决策**：通过 `scheduleOnMain(Supplier<T>)` 方法桥接 CompletableFuture 与 Nukkit ServerScheduler。

**实现**：
```java
protected <T> CompletableFuture<T> scheduleOnMain(Supplier<T> task) {
    CompletableFuture<T> future = new CompletableFuture<>();
    Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
        try {
            future.complete(task.get());
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    });
    return future;
}
```

**关键点**：
- `scheduleTask` 将任务提交到主线程下一 tick
- 任务完成后 `complete` / `completeExceptionally` 完成 Future
- `protected` 修饰，便于单元测试覆盖为同步执行

### 4. 死锁防护

**决策**：`awaitMain` 内部检测当前线程是否为主线程。

**实现**：
```java
public <T> T awaitMain(Supplier<T> task) {
    if (isMainThread()) {
        return task.get();  // 已在主线程，直接执行
    }
    return flow.scheduleOnMain(task).join();  // park 虚拟线程
}
```

**理由**：如果在主线程上 `join()` 等待主线程任务，会死锁（主线程等待自己）。

### 5. 队列严格校验

**决策**：引用不存在的队列时抛出 `IllegalArgumentException`，而非自动创建。

**实现**：
```java
ExecutorService getExecutor(String queue) {
    ExecutorService executor = threadAPI.getThreadExecutor(queue);
    if (executor == null) {
        throw new IllegalArgumentException(
                "线程队列 '" + queue + "' 不存在，请先通过 ThreadAPI.createThreadTask() 创建");
    }
    return executor;
}
```

**理由**：自动创建队列虽然方便，但拼写错误会意外创建线程（每个队列一个 OS 线程），可能导致线程泄漏和性能下降。严格校验在开发阶段及早发现问题。

### 6. 异常解包

**决策**：`onError` 接收解包后的原始异常，而非 CompletionException/ExecutionException。

**实现**：
```java
static Throwable unwrap(Throwable ex) {
    Throwable current = ex;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
        current = current.getCause();
    }
    return current;
}
```

**理由**：用户关心的是业务异常（如 `IllegalStateException`），而非 JDK 的包装异常。

---

## 三、方法可见性策略

| 方法 | 可见性 | 理由 |
|------|--------|------|
| `create()`, `virtual()` | public | 用户入口 |
| `bindPlugin()` | public | PluginAware 接口要求 |
| `scheduleOnMain()` | protected | 测试覆盖为同步执行 |
| `fireMain()` | protected | 测试覆盖 |
| `isPrimaryThread()` | protected | 测试覆盖 |
| `logVirtualThreadError()` | protected | 测试覆盖（避免依赖 Server） |
| `getExecutor()` | package | FlowChain / FlowContext 调用 |
| `fireAsync()` | package | FlowContext 调用 |
| `getThreadAPI()` | package | 测试清理调用 |
| `requireReady()` | private | 内部校验 |

---

## 四、测试策略

### TestableThreadFlow

测试通过继承 `ThreadFlow` 并覆盖 `protected` 方法，避免依赖真实 Nukkit 服务器：

```java
static class TestableThreadFlow extends ThreadFlow {
    @Override
    protected <T> CompletableFuture<T> scheduleOnMain(Supplier<T> task) {
        // 同步执行，不通过 ServerScheduler
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            future.complete(task.get());
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    protected boolean isPrimaryThread() {
        return false;  // 模拟非主线程
    }
}
```

### 测试覆盖范围

| 测试类 | 覆盖内容 |
|--------|---------|
| `ThreadFlowLogicTest` | 回调链：基本执行、主线程步骤、多步链、异常处理、回调触发、future()、插件绑定 |
| `FlowContextLogicTest` | 虚拟线程：awaitAsync、awaitMain（含死锁防护）、多步序列、异常处理、fire 方法 |

### 竞态条件注意事项

测试中使用 `CountDownLatch` + `AtomicReference` 验证异步结果时，**必须**将 `result.set()` 和 `done.countDown()` 放在同一个回调中：

```java
// ✅ 正确：同一回调内 set + countDown，保证 happens-before
.onSuccess(s -> {
    result.set(s);
    done.countDown();
})
.onError(e -> done.countDown())
```

```java
// ❌ 错误：onSuccess 和 onComplete 分离，可能竞态
.onSuccess(result::set)
.onComplete(done::countDown)
```

**原因**：当 future 在 `onSuccess` 注册后、`onComplete` 注册前完成时，两个回调可能在不同线程执行，`countDown()` 可能在 `set()` 之前发生。

---

## 五、扩展指南

### 添加新的步骤类型

例如添加 `thenBoth`（并行执行两个异步任务，合并结果）：

```java
public <U, R> FlowChain<R> thenBoth(
        String queue,
        Function<T, CompletableFuture<U>> other,
        BiFunction<T, U, R> merger) {
    CompletableFuture<R> next = future.thenCompose(prev -> {
        CompletableFuture<U> otherFuture = other.apply(prev);
        return otherFuture.thenApply(o -> merger.apply(prev, o));
    });
    return new FlowChain<>(flow, next);
}
```

### 添加超时支持

```java
public FlowChain<T> timeout(long timeout, TimeUnit unit) {
    CompletableFuture<T> next = future.orTimeout(timeout, unit);
    return new FlowChain<>(flow, next);
}
```

### 添加重试支持

```java
public FlowChain<T> retry(int maxRetries, Function<Throwable, Boolean> shouldRetry) {
    // 递归重试逻辑...
}
```

---

## 六、性能考量

### 虚拟线程开销

- 每次调用 `virtual()` 创建一个虚拟线程，开销极小（~KB 级栈）
- `awaitAsync` / `awaitMain` 内部使用 `CompletableFuture.join()`，park 虚拟线程不阻塞 OS 线程
- 虚拟线程数量无实际限制（受堆内存约束）

### 回调链开销

- 每个步骤创建一个新的 `CompletableFuture`，对象开销极小
- `thenApplyAsync` / `thenCompose` 提交到 ExecutorService，有一次线程切换开销
- `scheduleOnMain` 提交到 ServerScheduler，等待下一 tick（~50ms 延迟）

### 队列串行性

ThreadAPI 的每个队列使用 `Executors.newSingleThreadExecutor()`，同一队列内的任务 **串行执行**。
不同队列之间 **并行执行**。设计时应将独立的 I/O 操作分到不同队列。

---

## 七、常见问题

### Q: `async` 和 `thenAsync` 的区别？

`async` 创建一个 **独立** 的 CompletableFuture，不依赖前一步结果（忽略前值）。
`thenAsync` 在前一步完成后 **链式** 执行，接收前一步的结果作为输入。

### Q: 为什么 `thenMain` 使用 `thenCompose` 而非 `thenApplyAsync`？

因为 `scheduleOnMain` 返回的是 `CompletableFuture`，需要用 `thenCompose` 拍平嵌套。
如果用 `thenApplyAsync`，会得到 `CompletableFuture<CompletableFuture<R>>`。

### Q: 虚拟线程模式中，异常如何处理？

`virtual()` 内部捕获 `Exception` 并记录到 Nukkit 日志。
如果需要自定义异常处理，在代码体内使用 try-catch。

### Q: 可以在主线程上调用 `virtual()` 吗？

可以。`virtual()` 创建虚拟线程，不阻塞调用线程。
虚拟线程内的 `awaitMain` 会检测是否在主线程，如果在则直接执行，不会死锁。
