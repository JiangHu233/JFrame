# jframe_task — 可挂起 Nukkit 主线程任务

> 基于 Nukkit `scheduleRepeatingTask` 的「按需驱动、自动挂起、可恢复」任务抽象。任务在有效时由主线程 tick 循环持续驱动，完成后自动挂起；外部可随时通过 `execute()` 重新唤醒。

---

## 📑 目录

- [一、这个模块解决什么问题](#一这个模块解决什么问题)
- [二、与 ThreadAPI 的区别](#二与-threadapi-的区别)
- [三、快速开始](#三快速开始)
- [四、核心概念](#四核心概念)
- [五、API 详解](#五api-详解)
- [六、完整示例](#六完整示例)
- [七、注意事项](#七注意事项)

---

## 一、这个模块解决什么问题

Nukkit 主线程每 tick（约 50ms）执行一次，任何耗时操作都会造成卡服。但很多游戏逻辑（方块操作、实体移动）**必须在主线程执行**，不能放到异步线程。

本模块提供一个**分帧执行 + 按需启停**的任务抽象：

- **分帧执行**：将长耗时工作拆分到多个 tick 内逐步完成，每 tick 只做一小步
- **自动挂起**：任务完成后自动取消调度，不浪费后续 tick
- **按需恢复**：外部调用 `execute()` 即可重新启动，无需手动管理调度生命周期
- **幂等触发**：重复调用 `execute()` 安全——已在跑就保持，没在跑才启动

---

## 二、与 ThreadAPI 的区别

| 维度 | [`ThreadAPI`](../thread/ThreadAPI.java)（异步线程） | [`SuspendableTask`](SuspendableTask.java)（主线程分帧） |
|------|------|------|
| **执行线程** | 独立异步线程 | Nukkit **主线程**（同步） |
| **适用场景** | 数据库 IO、文件读写等非线程安全操作 | 方块操作、实体移动等**必须主线程**的操作 |
| **生命周期** | 队列常驻，任务一次性提交 | 按需启动、自动挂起、可恢复 |
| **调度方式** | `ExecutorService.execute()` | `ServerScheduler.scheduleRepeatingTask()` |

> **何时用本模块**：需要在主线程分批执行的耗时计算（如分帧寻路、批量方块操作、动画序列）。
> 若只需异步 IO，用 [`ThreadAPI`](../thread/ThreadAPI.java) 更合适。

---

## 三、快速开始

```java
// 获取 TaskAPI
TaskAPI taskAPI = jframeMain.getTaskAPI();

// 方式一：函数式创建（Lambda，最简洁）
SuspendableTask task = taskAPI.createTask(
    "block-build",           // 任务名称
    2,                       // 每 2 tick 执行一次
    () -> placeNextBlock(),  // onTick：每 tick 放一个方块
    () -> hasMoreBlocks()    // isValid：还有方块要放就继续
);

// 启动任务（幂等：重复调用安全）
task.execute();

// 也可以通过名称触发
taskAPI.execute("block-build");
```

---

## 四、核心概念

### 状态机

```
Idle ──execute()──▶ Running ──isValid()==false──▶ Idle（自动挂起 + onFinish）
                       │
                  每 interval tick:
                    isValid==true  → onTick()  做一小步工作
                    isValid==false → cancel()  自动挂起

Running 中再次 execute() → no-op（保持执行，不重复注册）
```

### 幂等触发（核心特性）

[`execute()`](SuspendableTask.java:170) 是整个模块的核心入口：

| 调用时任务状态 | 行为 |
|---|---|
| **正在执行** | 直接返回（保持执行，不重复注册调度） |
| **未在执行** | 注册新的 `scheduleRepeatingTask`，启动执行 |

这意味着你可以**放心地在事件回调中反复调用 `execute()`**，无需担心重复注册。

### 生命周期钩子

| 钩子 | 触发时机 | 用途 |
|------|----------|------|
| [`onStart()`](SuspendableTask.java:131) | `execute()` 实际启动调度时 | 初始化（重置游标、记录开始时间等） |
| [`onFinish()`](SuspendableTask.java:142) | `isValid()` 返回 false、自动挂起时 | 收尾（发通知、清理资源等） |

> 注意：手动 [`cancel()`](SuspendableTask.java:184) **不会**触发 `onFinish()`。

---

## 五、API 详解

### [`SuspendableTask`](SuspendableTask.java)（抽象基类）

| 方法 | 说明 |
|------|------|
| [`execute()`](SuspendableTask.java:170) | **核心入口**。幂等触发：已在跑则保持，没在跑则启动 |
| [`cancel()`](SuspendableTask.java:184) | 手动取消调度，回到 Idle 态。可再次 `execute()` 恢复 |
| [`isRunning()`](SuspendableTask.java:196) | 是否正在被 Nukkit 调度器驱动 |
| [`isFinished()`](SuspendableTask.java:206) | 是否已自然完成（`isValid()` 曾返回 false） |
| `onTick()` | **抽象**。每 interval tick 执行的增量工作 |
| `isValid()` | **抽象**。是否还有有效工作（false = 完成） |
| `onStart()` | 可选钩子。任务启动时调用 |
| `onFinish()` | 可选钩子。任务自然完成时调用 |

### [`TaskAPI`](TaskAPI.java)（管理器）

| 方法 | 说明 |
|------|------|
| [`createTask(name, interval, onTick, isValid)`](TaskAPI.java:62) | 函数式创建任务并注册（Lambda） |
| [`register(name, task)`](TaskAPI.java:77) | 注册自定义子类实例 |
| [`execute(name)`](TaskAPI.java:107) | 按名触发任务 |
| [`cancel(name)`](TaskAPI.java:118) | 按名取消并移除 |
| [`cancelAll()`](TaskAPI.java:131) | 取消所有任务（插件卸载时调用） |
| [`getTask(name)`](TaskAPI.java:142) | 获取任务实例 |
| [`isRunning(name)`](TaskAPI.java:152) | 查询指定任务是否在执行 |
| [`getPlugin()`](TaskAPI.java:50) | 获取已绑定的插件（用于手动创建子类任务） |

---

## 六、完整示例

### 场景一：分帧批量放置方块

```java
public class BlockBuildTask extends SuspendableTask {
    private final List<Location> blocks;
    private int cursor = 0;

    public BlockBuildTask(Plugin plugin, List<Location> blocks) {
        super(plugin, 2); // 每 2 tick（100ms）放一个方块
        this.blocks = blocks;
    }

    @Override
    protected void onStart() {
        cursor = 0; // 每次启动时重置
    }

    @Override
    protected void onTick() {
        Location loc = blocks.get(cursor);
        loc.getLevel().setBlock(loc, Block.get(Block.STONE));
        cursor++;
    }

    @Override
    protected boolean isValid() {
        return cursor < blocks.size();
    }

    @Override
    protected void onFinish() {
        Server.getInstance().getLogger().info("建筑完成，共放置 " + cursor + " 个方块");
    }
}

// 使用
BlockBuildTask build = new BlockBuildTask(taskAPI.getPlugin(), blockList);
taskAPI.register("castle", build);
build.execute();
```

### 场景二：函数式分帧寻路

```java
// 每 tick 展开一个 A* 节点，找到路径后自动停止
SuspendableTask search = taskAPI.createTask("mob-pathfinding", 1,
    () -> expandNextNode(),          // onTick
    () -> !pathFound() && hasBudget() // isValid
);

// 玩家移动时触发（幂等：已在跑就不重复注册）
search.execute();
```

### 场景三：按名管理

```java
// 触发
taskAPI.execute("castle");

// 查询
if (taskAPI.isRunning("castle")) {
    getLogger().info("建筑任务进行中...");
}

// 取消
taskAPI.cancel("castle");

// 插件卸载时全部关闭（JFrameMain.onDisable 已自动调用）
taskAPI.cancelAll();
```

---

## 七、注意事项

- **onTick 必须轻量**：`onTick()` 在主线程执行，单次耗时过长会卡服。将大任务拆成小步，每 tick 只做一步。
- **isValid 是终止条件**：确保 `isValid()` 最终会返回 false，否则任务永不停止（除非手动 cancel）。
- **异常不中断**：`onTick()` 抛出的异常会被捕获并记录日志，任务继续执行。若需在异常时停止，在 `isValid()` 中检查错误状态。
- **与 ThreadAPI 互补**：IO 密集用 [`ThreadAPI`](../thread/ThreadAPI.java)，主线程分帧计算用 `SuspendableTask`。切勿在 `onTick()` 中执行阻塞 IO。

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
