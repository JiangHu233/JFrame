# release 包开发者文档 —— ReleaseAPI 内部实现

面向贡献者：仲裁算法、线程模型、状态机与不变量。
面向使用者的 API 指南见 [README.md](README.md)。

## 一、类结构

| 类 | 职责 |
|----|------|
| `ReleaseAPI` | 调度器：通道注册表、drain 仲裁、挂起/唤醒、全局关闭；内部类 `ChannelImpl` 实现通道 |
| `ReleaseChannel` | 通道公开接口（提交侧 + 终结侧 + 完成感知） |
| `ReleasePolicy` | 不可变策略值对象（Kind 枚举 + 静态工厂，构造即校验） |
| `ChannelAbortedException` | abort 时 future 异常完成的 cause |

`ChannelImpl` 为**非 static 内部类**：直接访问外部 `ReleaseAPI` 的
`currentTick` / `totalPending` / `ensureScheduled` / `postRelease` 记账。

## 二、线程模型（核心不变量）

### 记账单线程化

配额、时钟（`currentTick`）、RATE 累积器（`rateAccumulator`）、
AUTO 回收计时（`lastActiveTick`）、`callbackFired` **只在主线程 drain 内读写**——
零锁、零竞态、可单测。

### 提交任意线程

| 字段 | 类型 | 提交侧 | drain 侧 |
|------|------|--------|----------|
| `tasks` | `ConcurrentLinkedQueue` | add | poll |
| `pending` | `AtomicInteger` | ++（0→1 时换 future） | -- |
| `totalPending` | `AtomicInteger` | ++ | -- |
| `batchFuture` | volatile | 换新（0→1 时） | complete |
| `deadlineTick` | volatile | 写（0→1 时） | 读 |
| `closed` | volatile | close/abort 写 | 读 |

`pending` 用 `AtomicInteger` 而非 `CLQ.size()`：后者 O(n) 且弱一致，
`size()`/`isIdle()` 需要 O(1) 强一致快照做挂起判定。

### 挂起 / 唤醒（无丢唤醒）

```
maybeSuspend:  synchronized(schedLock) { 二次检查 totalPending==0 → cancel + handler=null }
ensureScheduled: synchronized(schedLock) { 二次检查 handler==null → scheduleRepeating }
```

提交路径 `pending.incrementAndGet()==1`（0→1）与挂起路径的检查都持锁二次确认，
「先空后入队」窗口内不会丢唤醒；空闲时 repeating task 取消，零调度开销。

> 注意：`currentTick` 只在 drain 内 `++`，空闲挂起期间不走——
> 时钟含义是「drain 执行次数」而非真实 tick，两轮活跃之间的空闲不消耗 deadline。

## 三、drain 五步（每 tick，仅主线程）

```
drain():
  1. currentTick++；收集活跃通道（pending>0），AUTO 在此刷新 lastActiveTick
     （释放前刷新：本 tick 即活跃 tick，清空后的空闲计时从下一 tick 起算）
  2. releaseBatch(active, ct)   —— 配额计算 + 仲裁 + 出队执行
  3. postRelease(ct)            —— future / close 回调 / AUTO 回收
  4. maybeSuspend()             —— 全空则挂起
```

### 配额计算（纯函数：通道快照进 → 配额表出）

```
DEADLINE（期限内）: quota = ceil(pending / (deadlineTick - ct))   → 刚性
DEADLINE（超期）  : quota = pending                                → 弹性（退化）
RATE            : rateAccumulator += ratePerTick; quota = min((int)acc, pending) → 刚性
BEST_EFFORT     : quota = pending                                  → 弹性
```

### 仲裁

- **刚性需求 ≤ 预算**：刚性按配额足额释放；
  弹性组按 pending 占比分食剩余预算（`round(remainBudget * p / elasticTotal)`）。
- **刚性需求 > 预算**：刚性按 `round(quota * budget / rigidDemand)` 比例缩放，
  **保底 1 防饿死**（总释放误差 ≤ 活跃通道数）；弹性组分 0。

### RATE 累积器细节

- 计算 quota 时 `+= ratePerTick`；**实际释放每个任务 `-1`**——
  被预算缩放的差额自动保留到下 tick（永不丢失，只延迟）。
- 小数速率（0.5/tick）通过累积自然实现「每 2 tick 释放 1 个」。

## 四、批次（wave）与 future 快照语义

`pending` 从 0→1 的瞬间（`incrementAndGet()==1`）：
1. 若 `batchFuture.isDone()` → 新建 future（旧批次已终结，新一轮开始）；
2. DEADLINE 通道起算 `deadlineTick = currentTick + durationTicks`（volatile 读，
   挂起唤醒后 currentTick 可能滞后于真实时间——deadline 基于调度时钟，见上文注意）。

`future()` 返回**调用时刻批次**的引用：排干即 complete；
`awaitChannel` 的等待者、`assertNotSame` 断言都依赖此快照。

## 五、postRelease 状态机

```
释放后 pending == 0:
  ├─ 未 closed 且未 aborted  → batchFuture.complete()        （批次完成，通道继续可用）
  └─ closed 且未 aborted:
       ├─ 未 callbackFired  → fire onComplete（恰好一次）+ channels.remove（注销）
       └─ AUTO 回收路径: !closed && autoRecycle && ct - lastActiveTick >= idleRecycleTicks
            → fire onComplete + channels.remove（视为该波完成）
```

- `callbackFired` 只在主线程读写 → 无需 CAS；
- close 时队列已空：close 自身 `ensureScheduled()` 唤醒 drain 处理回调；
- 回调异常走 `logError` / `exceptionHandler`，不影响其他通道。

## 六、背压与终结的实现要点

- **背压**：每通道 `Semaphore(maxBacklog)`；`submit` 阻塞在 acquire
  （虚拟线程 park 友好），醒后**二次检查 closed**——若已被 abort/close 则
  归还名额并抛 ISE（不得向已终结通道泄漏任务）。
- **abort/close 与 drain 的并发**：`discardAll`/`clear` 与 drain 都 `poll()` 同一 CLQ，
  各自递减各自计数——并发安全（每任务恰好被一方消费）。
- **clear vs abort**：都释放被丢弃任务的全部背压名额；clear 使当前批次 future
  **正常完成**（视为排干），abort 使其**异常完成**（等待者可区分）。
- **服务 close()**（插件卸载）：取消调度 + 全部通道 discardAll
  （future 正常完成释放等待者，避免卸载时虚拟线程永久 park）。

## 七、AUTO 惰性复活

被回收的通道已从 `channels` 移除，但 `ReleaseChannel` 引用仍被用户持有。
后续 `submit` → `reRegisterIfAbsent()` 重新注册（同名新实例，**新批次新 future**）。
旧等待者等的是旧批次快照——已完成/异常完成，立即返回，不受复活干扰。

`isClosed()` 对 AUTO 回收返回 false：终结特指显式 close/abort，
回收是「这一波完成」，通道逻辑上仍可复活。

## 八、可测性设计

`scheduleRepeating / cancelHandler / requirePluginReady / logError` 为 `protected`，
`drain()` 为 `protected`——测试子类覆盖后脱离真实 Nukkit Server：

```java
class TestableReleaseAPI extends ReleaseAPI {
    @Override protected TaskHandler scheduleRepeating() { return null; } // 计数由字段记录
    @Override protected void requirePluginReady() { }                    // 跳过插件检查
    void tick() { drain(); }                                             // 手动模拟主线程
}
```

测试文件：
- `ReleaseAPILogicTest`：1-12 组场景（预算限流 / DEADLINE 收敛与超期退化 /
  RATE 小数累积 / 多通道仲裁 / 异常隔离 / 挂起唤醒 / 并发竞态 / 背压 /
  clear / removeChannel / AUTO-MANUAL 生命周期 / abort）
- `flow/FlowContextAwaitChannelLogicTest`：第 13 组（awaitChannel 集成糖）

## 九、已知权衡（有意为之）

| 权衡 | 理由 |
|------|------|
| 弹性组超预算时可能分 0 | 刚性优先是产品语义；弹性本就是 best-effort |
| 缩放保底 1 的总误差 ≤ 通道数 | 防饿死比精确配额更重要 |
| deadline 基于调度时钟（空闲不计时） | 挂起期间无任务在排队，「超时」无意义 |
| submit 阻塞式背压 | 虚拟线程 park 零成本；失控生产者只堵自己的通道 |
| 内部不做异步化（drain 单线程执行任务） | 单线程记账是正确性根基；异步执行收益为负（见计划文档 7.1） |
