# jframe_flow — 线程切换编排器

> 在异步线程与 Nukkit 主线程之间优雅切换，告别回调地狱。提供**回调链**与**虚拟线程**两种语法糖。
>
> **相关文档**：[维护者文档](DEVELOPER.md) ｜ [ThreadAPI 使用文档](../thread/README.md) ｜ [设计方案](../../../../../../../../../../../plans/thread-switching-syntax-sugar.md)

---

## 📑 目录

- [一、为什么需要 ThreadFlow](#一为什么需要-threadflow)
- [二、快速开始](#二快速开始)
- [三、模式一：回调链（FlowChain）](#三模式一回调链flowchain)
- [四、模式二：虚拟线程同步风格（FlowContext）](#四模式二虚拟线程同步风格flowcontext)
- [五、两种模式对比](#五两种模式对比)
- [六、最佳实践](#六最佳实践)

---

## 一、为什么需要 ThreadFlow

在 Nukkit 插件开发中，耗时操作（数据库查询、网络请求、大量计算）必须放到异步线程，
而操作游戏世界（方块、实体、玩家）必须在主线程执行。传统写法需要手动管理线程切换：

```java
// ❌ 传统写法：回调嵌套，难以维护
threadAPI.pushTask("database", () -> {
    User user = userDao.findByName(name);
    Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
        player.sendMessage("欢迎, " + user.getName());
        player.teleport(user.getSpawn());

        threadAPI.pushTask("cache", () -> {
            cacheStore.put("last-login", user.getId());
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                player.sendMessage("处理完成!");
            });
        });
    });
});
```

ThreadFlow 提供两种语法糖，让线程切换变得线性、可读：

```java
// ✅ 回调链模式
threadFlow.create()
    .async("database", () -> userDao.findByName(name))
    .thenMain(user -> {
        player.sendMessage("欢迎, " + user.getName());
        player.teleport(user.getSpawn());
        return user.getId();
    })
    .thenAsync("cache", id -> cacheStore.put("last-login", id))
    .thenMain(() -> player.sendMessage("处理完成!"))
    .onError(e -> logger.error("登录流程失败", e))
    .start();
```

```java
// ✅ 虚拟线程模式（同步风格，最直观）
threadFlow.virtual(ctx -> {
    User user = ctx.awaitAsync("database", () -> userDao.findByName(name));
    ctx.awaitMain(() -> {
        player.sendMessage("欢迎, " + user.getName());
        player.teleport(user.getSpawn());
    });
    ctx.awaitAsync("cache", () -> cacheStore.put("last-login", user.getId()));
    ctx.awaitMain(() -> player.sendMessage("处理完成!"));
});
```

---

## 二、快速开始

### 获取 ThreadFlow

```java
// 方式一：通过 JFrameMain 门面
ThreadFlow flow = JFrameMain.getThreadFlow();

// 方式二：通过 Spring 容器
@Autowired
private ThreadFlow threadFlow;
```

### 前置条件

- 导入 `jframe_async` 模块（JFrameMain 自动绑定 Plugin）
- Java 21+（虚拟线程模式需要）

---

## 三、模式一：回调链（FlowChain）

### 基本概念

`create()` 返回一个 `FlowChain<Void>`，通过链式方法在异步线程和主线程之间切换。
每个方法返回新的 `FlowChain`（不可变），类型参数表示当前步骤的产出值类型。

### 方法总览

| 方法 | 执行线程 | 数据关系 | 说明 |
|------|---------|---------|------|
| `async(queue, Supplier)` | 异步队列 | 忽略前值，产出新值 | 起始步骤或重新开始 |
| `async(queue, Runnable)` | 异步队列 | 忽略前值，无返回值 | Void 变体 |
| `thenAsync(queue, Function)` | 异步队列 | 变换前值 | 在异步线程上处理前一步结果 |
| `thenAsync(queue, Consumer)` | 异步队列 | 消费前值，无返回值 | Void 变体 |
| `main(Supplier)` | 主线程 | 忽略前值，产出新值 | 在主线程上开始新任务 |
| `main(Runnable)` | 主线程 | 忽略前值，无返回值 | Void 变体 |
| `thenMain(Function)` | 主线程 | 变换前值 | 在主线程上处理前一步结果 |
| `thenMain(Consumer)` | 主线程 | 消费前值，无返回值 | Void 变体 |
| `onSuccess(Consumer)` | 完成线程 | — | 正常完成时触发 |
| `onError(Consumer)` | 完成线程 | — | 异常时触发（接收解包后的原始异常） |
| `onComplete(Runnable)` | 完成线程 | — | 无论成功或失败都触发 |
| `start()` | — | — | 启动流程（兜底异常处理） |
| `future()` | — | — | 获取底层 CompletableFuture |

### 完整示例

```java
// 玩家登录流程：查数据库 → 操作实体 → 写缓存 → 通知
threadFlow.create()
    .async("database", () -> userDao.findByName("Steve"))   // 异步：查数据库
    .thenMain(user -> {                                      // 主线程：操作实体
        player.sendMessage("欢迎回来, " + user.getName());
        player.teleport(user.getSpawnLocation());
        return user.getId();
    })
    .thenAsync("cache", id -> {                              // 异步：写缓存
        cacheStore.put("last-login:" + player.getName(), id);
        return null;
    })
    .thenMain(() -> player.sendMessage("数据处理完成!"))       // 主线程：通知
    .onError(e -> logger.error("登录流程失败", e))            // 异常处理
    .start();
```

### 数据传递

每步可以变换值类型，类型安全：

```java
Integer result = threadFlow.create()
    .async("io", () -> "123")        // FlowChain<String>
    .thenAsync("parse", Integer::parseInt)  // FlowChain<Integer>
    .thenMain(n -> n * 2)            // FlowChain<Integer>
    .future()                        // CompletableFuture<Integer>
    .get(3, TimeUnit.SECONDS);       // 246
```

### 异常处理

链中任意步骤抛出异常，后续步骤自动跳过，异常传播到 `onError`：

```java
threadFlow.create()
    .async("io", () -> {
        throw new IllegalStateException("连接超时");
    })
    .thenMain(v -> { /* 不会执行 */ })
    .onError(e -> {
        // e 是解包后的原始异常（IllegalStateException）
        // 不是 CompletionException 或 ExecutionException
        player.sendMessage("操作失败: " + e.getMessage());
    })
    .start();
```

> **注意**：如果未注册 `onError`，异常会被记录到 Nukkit 日志，不会静默丢失。

### 队列必须预先创建

引用不存在的队列时会抛出 `IllegalArgumentException`，避免拼写错误意外创建线程：

```java
// ✅ 正确：先创建队列
threadAPI.createThreadTask("my-queue");
threadFlow.create()
    .async("my-queue", () -> doWork())
    .start();

// ❌ 错误：队列不存在，抛出 IllegalArgumentException
threadFlow.create()
    .async("typo-queue", () -> doWork())
    .start();
```

---

## 四、模式二：虚拟线程同步风格（FlowContext）

### 基本概念

`virtual()` 在虚拟线程上执行代码体。代码体中使用 `awaitAsync` / `awaitMain` 切换线程，
这些方法会 **park 虚拟线程**（不阻塞 OS 线程），在目标线程完成任务后自动恢复。

### 方法总览

| 方法 | 执行线程 | 阻塞 | 说明 |
|------|---------|------|------|
| `awaitAsync(queue, Supplier)` | 异步队列 | ✅ park 虚拟线程 | 等待异步任务完成，返回结果 |
| `awaitAsync(queue, Runnable)` | 异步队列 | ✅ park 虚拟线程 | Void 变体 |
| `awaitMain(Supplier)` | 主线程 | ✅ park 虚拟线程 | 等待主线程任务完成，返回结果 |
| `awaitMain(Runnable)` | 主线程 | ✅ park 虚拟线程 | Void 变体 |
| `fireAsync(queue, Runnable)` | 异步队列 | ❌ | 提交后立即返回（fire-and-forget） |
| `fireMain(Runnable)` | 主线程 | ❌ | 提交后立即返回（fire-and-forget） |

### 完整示例

```java
// 批量数据处理：加载 → 过滤 → 转换 → 保存
threadFlow.virtual(ctx -> {
    // 异步加载原始数据
    List<String> raw = ctx.awaitAsync("database", () -> loadAllRecords());

    // 异步过滤
    List<String> filtered = ctx.awaitAsync("cpu", () -> raw.stream()
            .filter(s -> s.length() > 10)
            .toList());

    // 主线程：操作游戏世界
    ctx.awaitMain(() -> {
        for (String record : filtered) {
            player.sendMessage(record);
        }
    });

    // 异步保存结果
    ctx.awaitAsync("database", () -> saveProcessed(filtered));

    // 主线程：通知完成
    ctx.awaitMain(() -> player.sendMessage("处理完成! 共 " + filtered.size() + " 条记录"));
});
```

### 完整控制流

虚拟线程模式支持 `if / for / try-catch` 等所有 Java 控制流：

```java
threadFlow.virtual(ctx -> {
    User user = ctx.awaitAsync("database", () -> userDao.findById(id));

    if (user == null) {
        ctx.awaitMain(() -> player.sendMessage("用户不存在"));
        return;
    }

    try {
        var data = ctx.awaitAsync("network", () -> fetchExternalData(user));
        ctx.awaitMain(() -> player.sendMessage("数据: " + data));
    } catch (Exception e) {
        ctx.awaitMain(() -> player.sendMessage("获取数据失败: " + e.getMessage()));
    }
});
```

### 死锁防护

`awaitMain` 内部检测当前线程是否为主线程，如果是则直接同步执行，避免死锁：

```java
// 如果 virtual() 的代码恰好在主线程上运行（例如通过 Thread.ofVirtual().start()），
// awaitMain 会直接同步执行，不会死锁
threadFlow.virtual(ctx -> {
    ctx.awaitMain(() -> player.sendMessage("安全!")); // 自动检测，不会死锁
});
```

---

## 五、两种模式对比

| 特性 | 回调链（FlowChain） | 虚拟线程（FlowContext） |
|------|--------------------|-----------------------|
| **代码风格** | 链式调用 | 同步线性 |
| **Java 版本** | Java 8+ | Java 21+ |
| **阻塞** | 非阻塞（回调驱动） | park 虚拟线程（不阻塞 OS 线程） |
| **控制流** | 无（纯回调） | 完整支持 if/for/try-catch |
| **异常处理** | `onError` 回调 | try-catch |
| **循环中切换** | 不支持 | ✅ 原生支持 |
| **复杂度** | 简单流程 | 复杂流程更直观 |
| **调试** | 栈追踪跨线程 | 栈追踪连续（虚拟线程内） |

### 选择建议

- **简单流程**（3-5 步线性链）→ 回调链
- **复杂流程**（条件分支、循环、嵌套）→ 虚拟线程
- **需要返回值给调用方** → 回调链的 `future()`
- **需要完整 try-catch** → 虚拟线程

---

## 六、最佳实践

### 队列命名约定

按职责命名队列，便于监控和调试：

```java
"database"  // 数据库 I/O
"cache"     // 缓存读写
"network"   // 网络请求
"cpu"       // CPU 密集型计算
"file"      // 文件 I/O
```

### 避免在主线程上 await

```java
// ❌ 错误：在主线程上 await 会阻塞主线程
@CommandMapping("test")
public void onCommand(Player player) {
    var data = threadFlow.create()
        .async("io", () -> loadData())
        .future()
        .join();  // 阻塞主线程！服务器卡顿！
}

// ✅ 正确：使用回调
@CommandMapping("test")
public void onCommand(Player player) {
    threadFlow.create()
        .async("io", () -> loadData())
        .thenMain(data -> player.sendMessage("数据: " + data))
        .start();
}
```

### 始终注册 onError

```java
// ✅ 始终注册 onError，避免异常被吞
threadFlow.create()
    .async("io", () -> riskyOperation())
    .thenMain(data -> processData(data))
    .onError(e -> logger.error("操作失败", e))
    .start();
```

### 合理拆分队列

```java
// ✅ I/O 和 CPU 分离到不同队列
threadFlow.create()
    .async("database", () -> loadFromDB())      // I/O 密集
    .thenAsync("cpu", data -> heavyCompute(data)) // CPU 密集
    .thenMain(result -> updatePlayer(result))     // 主线程
    .start();
```
