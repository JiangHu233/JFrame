# release 包 —— 主线程多通道缓释调度器

> 解决「异步线程批量向 Nukkit 主线程提交任务」的拥堵问题：任务进入各通道队列后，
> 由每 tick 的仲裁在**全局预算**内按通道策略分批释放执行，主线程永不拥堵。

## 一、解决什么问题

```java
// ❌ 传统写法：主线程 for 循环 + 主线程操作 → 卡服
for (Block b : blocks) {
    Data d = heavyCompute(b);        // 重计算挤占主线程
    level.setBlock(b, d);            // 主线程操作堆在一个 tick 内
}

// ✅ ReleaseAPI 写法：异步计算 + 缓释到主线程
threadFlow.virtual(ctx -> {
    var ch = release.acquire(ReleasePolicy.deadlineSeconds(20)); // 20 秒内做完
    for (Block b : blocks) {
        Data d = heavyCompute(b);                 // 虚拟线程内计算（不占主线程）
        ch.submit(() -> level.setBlock(b, d));    // 缓释到主线程
    }
    ch.close();                                   // 声明提交结束
    ctx.awaitChannel(ch);                         // park 等整批落地
    ctx.awaitMain(() -> broadcast("建造完成"));
});
```

主线程每 tick 只执行全局预算（默认 200）个任务，其余在队列中等待；
10000 个方块操作不再一次性砸进一个 tick。

## 二、三种释放策略（`ReleasePolicy`）

| 策略 | 工厂方法 | 语义 | 适用 |
|------|---------|------|------|
| DEADLINE | `deadlineTicks(n)` / `deadlineSeconds(s)` | 声明 N tick 内完成，速率 = ceil(pending/剩余tick) 动态收敛；超期退化为弹性 | 有明确完成时限的批次（建造、批量发放） |
| RATE | `ratePerTick(r)` / `ratePerSecond(r)` | 固定速率（支持小数累积，如 0.5/tick = 每 2 tick 1 个） | 持续平滑投放（公告队列、粒子效果） |
| BEST_EFFORT | `bestEffort()` | 吃全局剩余预算，不保证速率 | 不着急的后台任务 |

## 三、通道获取

```java
// MANUAL（默认）：零自动管理开销，需显式 close()/abort() 终结
ReleaseChannel ch = release.acquire(ReleasePolicy.deadlineSeconds(20));

// MANUAL + 背压上限（队列超 1000 时 submit 阻塞，防内存膨胀）
ReleaseChannel ch2 = release.acquire(ReleasePolicy.bestEffort(), 1000);

// AUTO（显式申请）：空闲超 idleRecycleTicks 自动回收，再 submit 惰性复活
// 含回收扫描开销，适合 for 循环内用完即弃、无法显式收尾的场景
ReleaseChannel ch3 = release.acquireAuto(ReleasePolicy.ratePerTick(5));

// 命名通道（等效 MANUAL）：跨代码位置复用；同名策略冲突抛 IllegalArgumentException
ReleaseChannel ch4 = release.channel("castle", ReleasePolicy.deadlineTicks(600));

// 便捷提交：内置 default 通道（best-effort）
release.submit(() -> level.setBlock(...));
```

**MANUAL vs AUTO 选型**：
- 有明确收尾点（循环结束知道何时完成）→ `acquire`（MANUAL，默认）
- 无法显式收尾（事件驱动持续提交）→ `acquireAuto`（AUTO，显式申请）

## 四、完成感知

```java
ch.onComplete(() -> broadcast("完成"));   // 通道终结且排干时主线程触发，恰好一次
ch.future();                             // 「当前批次」快照：队列清空时 complete

// ThreadFlow 集成糖（虚拟线程内同步等待整批落地）：
ctx.awaitChannel(ch);                          // park 直至批次排干 / abort
ctx.awaitChannel(ch, 30, TimeUnit.SECONDS);    // 带超时变体（长流程建议）
```

`future()` 为**快照语义**：等待的是调用时刻的批次，AUTO 回收复活 / 新一波提交
换新 future，旧等待者不受干扰。

**主线程禁止 `awaitChannel`**——future 完成依赖主线程 drain，等待即死锁；
检测到即抛 `IllegalStateException`，请改用 `onComplete` 回调。

## 五、终结语义

| 操作 | 新提交 | 存量任务 | onComplete | future |
|------|--------|---------|------------|--------|
| `close()` | 抛 ISE | 继续释放 | 排干后触发 | 正常完成 |
| `abort()` | 抛 ISE | 全部丢弃 | 不触发 | 异常完成（`ChannelAbortedException`） |
| `clear()` | 不影响 | 丢弃（名额释放） | 不影响 | 视为排干正常完成 |

## 六、全局配置

```java
release.setGlobalBudgetPerTick(200);   // 所有通道每 tick 释放总数上限
release.setIdleRecycleTicks(100);      // AUTO 通道空闲回收阈值（tick）
release.setExceptionHandler(e -> ...); // 任务执行异常处理（默认仅日志）
```

降级旋钮：卡服时可调低 `globalBudgetPerTick`；违约时可 abort 通道丢弃存量。

## 七、架构定位

独立基础设施原语（与 ThreadAPI / TaskAPI / ThreadFlow 平级）：
「跨线程 → 主线程的限流桥梁」。零依赖 flow 包；
`FlowContext.awaitChannel` 为其提供的单向糖接口。

```
用户代码（任意线程）
  └── ReleaseChannel（提交侧：CLQ + AtomicInteger + Semaphore 背压）
        └── ReleaseAPI（仲裁侧：单一 repeating task 每 tick drain）
              └── Nukkit 主线程（预算内分批执行）
```

详细设计与测试计划见 `plans/async-main-release-queue-plan.md`，
内部实现（仲裁算法 / 线程模型 / 状态机）见 [DEVELOPER.md](DEVELOPER.md)。

## 八、快速上手

```java
ReleaseAPI release = JFrameMain.getInstance().getReleaseAPI();

// 场景：异步遍历 10000 个方块，计算在虚拟线程，写操作缓释到主线程
threadFlow.virtual(ctx -> {
    var ch = release.acquire(ReleasePolicy.deadlineSeconds(30), 10_000);
    for (BlockPos pos : allPositions) {
        BlockState state = computeExpensive(pos);      // 异步计算
        ch.submit(() -> level.setBlock(pos, state));   // 主线程分批执行
    }
    ch.close();
    ctx.awaitChannel(ch, 60, TimeUnit.SECONDS);
    ctx.awaitMain(() -> player.sendMessage("全部完成"));
});
```
