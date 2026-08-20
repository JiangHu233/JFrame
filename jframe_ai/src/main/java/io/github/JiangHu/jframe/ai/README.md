# jframe_ai

> Minecraft 基岩版（Nukkit MOT）插件框架 **JFrame** 的 AI 模块。
> 以 **七个动词工厂**（path / walk / chase / wander / loop / attack / see）为统一入口，提供 **双算法寻路**（A* 精确 + 贪心低开销）、**循环行为**（计算→执行→判断）、**导航执行**（含走完续算）、**视野感知**、**单实体战术**（Target 化）与 **团队战术**。

---

## 目录

- [一、模块简介](#一模块简介)
- [二、功能特性](#二功能特性)
- [三、快速开始](#三快速开始)
- [四、核心 API 一览](#四核心-api-一览)
- [五、寻路子系统](#五寻路子系统)
- [六、行为子系统（LoopBehavior）](#六行为子系统loopbehavior)
- [七、导航执行（NavigationExecutor）](#七导航执行navigationexecutor)
- [八、战术子系统](#八战术子系统)
- [九、战斗与感知](#九战斗与感知)
- [十、完整示例：一只会战斗的怪物 AI](#十完整示例一只会战斗的怪物-ai)
- [十一、常见问题](#十一常见问题)

---

## 一、模块简介

`jframe_ai` 为 Nukkit MOT 服务端上的实体（僵尸、骷髅、自定义生物等）提供一套**开箱即用**的 AI 行为能力。它不依赖 Nukkit 内置的实体 AI，而是**自行实现**了面向方块世界的寻路与行为层，可以驱动任意 `cn.nukkit.entity.Entity`。

模块分为**两区**：

```
io.github.JiangHu.jframe.ai
├── AiAPI.java                 ← 门面：七个动词工厂 + 查询/停止 + 战术
│
├── core/                      ← 【库区】无状态能力件（不含业务策略）
│   ├── behavior/              ← LoopBehavior 循环行为 + ComputeCarrier 计算载体
│   ├── executor/              ← NavigationExecutor / PlannedPath / AttackExecutor
│   ├── navigation/            ← NavigatorManager → Navigator（实体移动调度）
│   ├── target/                ← Target 接口 + PointTarget / EntityTarget
│   ├── tactical/              ← TacticalScanner / TeamTactics / 六个战术 Target
│   ├── vision/                ← VisionSensor / SeeQuery
│   ├── combat/                ← CombatActions（战斗动作实现）
│   └── util/                  ← BlockChecks / LineOfSight / BlockSnapshotCache / PathVisualizer
│
└── pathfinding/               ← 【算法区】策略变体（插槽架构，可插拔）
    ├── PathfindingStrategy / PathResult / PathfinderOptions / StepCostFunction ...
    ├── astar/                 ← AStarPathFinder（默认策略，全局最优）
    └── greedy/                ← GreedyPathFinder（低开销局部步进）
```

```
   ┌──────────────────────── AiAPI（门面·七工厂） ───────────────────────┐
   │  path · walk · chase · wander · loop · attack · see                │
   │  stop / stopAll / isNavigating / isLooping / 战术 / 可视化          │
   ├────────────────────────────────────────────────────────────────────┤
   │ 行为层  LoopBehavior（计算→执行→判断 循环，载体可换）                │
   │ 执行层  NavigationExecutor（两段式：compute() / start()）           │
   │         AttackExecutor（战斗动作链式配置）  SeeQuery（视野查询）      │
   ├────────────────────────────────────────────────────────────────────┤
   │ 调度层  NavigatorManager → Navigator（实体移动驱动，单调度器 tick）   │
   ├────────────────────────────────────────────────────────────────────┤
   │ 算法区  AStarPathFinder（A*，OCTILE 启发） / GreedyPathFinder（贪心）│
   └────────────────────────────────────────────────────────────────────┘
```

### 与传统方式的对比

| 维度 | 传统方式 | 本框架 |
|------|------|------|
| **寻路算法** | 手写 BFS/DFS，单一算法 | 双策略插槽：[`AStarPathFinder`](pathfinding/astar/AStarPathFinder.java)（精确必达）+ [`GreedyPathFinder`](pathfinding/greedy/GreedyPathFinder.java)（开销极低），运行时可插拔 |
| **行为组织** | 手写定时任务 + if-else | [`LoopBehavior`](core/behavior/LoopBehavior.java) 同构循环：计算→执行→判断，chase/wander 均为其预配置 |
| **移动代价** | 固定步数 | [`StepCostFunction`](pathfinding/StepCostFunction.java) 外接评分器叠加自定义代价 |
| **无解处理** | 返回 `null` 原地不动 | 回退 [`PARTIAL`](pathfinding/PathResult.java) 模糊解——离目标最近的部分路径 |
| **长距离导航** | 一次性搜索全图易超时 | `continuous()` 走完续算，串联短段完成长距离 |
| **目标表达** | 坐标快照，目标移动即过时 | [`Target`](core/targeting/Target.java) 接口：每轮动态读取（点 / 活实体 / 战术位置） |
| **战术行为** | 硬编码位置选择 | [`TacticalScanner`](core/tactical/TacticalScanner.java) 评分 + 六个战术 Target |
| **团队协作** | 各实体独立决策易聚堆 | [`TeamTactics`](core/tactical/TeamTactics.java) 协同站位：钳形包抄 / 包围 / 阵型集结 |
| **多实体调度** | 每实体一个调度任务 | [`NavigatorManager`](core/navigation/NavigatorManager.java) 单调度器统一 tick |
| **视野感知** | 仅距离判断 | [`VisionSensor`](core/vision/VisionSensor.java) 距离 + FOV 角度 + DDA 视线三重判断 |

---

## 二、功能特性

| 类别 | 能力 | 说明 |
|------|------|------|
| **寻路** | A* 启发式方块寻路 | 对角线、跳跃、安全下落，OCTILE 启发式（8 方向最优），方块快照缓存 |
| **寻路** | 贪心低开销寻路 | 自适应三阶段局部步进，不保证最优/必达，开销极低 |
| **寻路** | 外接评分器 | `StepCostFunction` 叠加自定义代价（远离岩浆、贴墙走等） |
| **寻路** | 模糊解 | 无完整路径时回退离目标最近的部分路径（`PARTIAL`） |
| **行为** | 通用循环 | `loop()` 计算→执行→判断周期循环，全部参数自配 |
| **行为** | 追逐 | `chase()` 循环行为预配置：每轮从目标最新位置重算，目标失效自动停 |
| **行为** | 游荡 | `wander()` 循环行为预配置：半径内随机巡游，走完一段再选下一段 |
| **行为** | 计算载体 | `ComputeCarrier` 抽象计算执行位置（同步 / 线程池），回调自动回主线程 |
| **导航** | 两段式执行 | `compute()` 纯计算（任意线程）+ `start()` 驱动实体 |
| **导航** | 走完续算 | `continuous()` 模式：走完一段自动续算，串联短段完成长距离 |
| **导航** | 多实体调度 | `NavigatorManager` 单调度器统一 tick |
| **感知** | 视野判断 | `see()` 链式配置距离/FOV 后查询，距离+角度+视线三要素 |
| **战术** | 战术 Target | 掩体 / 远离 / 包抄 / 高地 / 视野点 / 模糊位置六种 Target，可直接作为循环目标 |
| **团队** | 协同站位 | 钳形包抄 / 360° 包围 / 阵型集结（横/纵/楔/环/方） |
| **战斗** | 四类动作 | 近战 / 射箭 / 投掷抛射物 / 使用物品，`attack()` 链式配置 |

---

## 三、快速开始

### 1. 获取 AI 服务

```java
// 方式一：通过 JFrameMain 门面（推荐）
AiAPI ai = JFrameMain.getAiAPI();

// 方式二：通过 Nukkit ServiceManager
AiAPI ai = server.getServiceManager().get(AiAPI.class);
```

> 独立插件使用本模块时，需自行从 Spring 容器获取 `AiAPI` Bean，并调用 `ai.bindPlugin(yourPlugin)` 启动导航调度。

### 2. 七个动词工厂

```java
// ① 单次导航：走一步算一步
ai.walk(zombie).to(player).speed(0.3).start();

// ② 追逐（目标丢失自动停，完成回调里可启动新行为）
ai.chase(zombie, player).onComplete(outcome -> {
    if (outcome == BehaviorOutcome.TARGET_LOST) ai.wander(zombie, 10).start();
}).start();

// ③ 游荡
ai.wander(zombie, 12).start();

// ④ 纯寻路（不驱动实体，任意线程可调）
PlannedPath plan = ai.path(zombie).to(pos).compute();
if (plan.hasPath()) { /* plan.getResult().getNodes() ... */ }

// ⑤ 战斗（主线程）
ai.attack(skeleton).arrow(player, 1.5, 0.3).fire();

// ⑥ 视野感知（任意线程）
if (ai.see(zombie).range(24).fov(120).canSee(player)) { ... }

// ⑦ 通用循环（全部参数自配）
ai.loop(guard).to(new CoverTarget(guard).threat(enemy).radius(8))
  .interval(20).until(ctx -> ctx.rounds() >= 5).start();
```

### 3. 停止与查询

```java
ai.stop(zombie);       // 停止该实体的一切 AI 驱动（循环行为 + 导航）
ai.stopAll();          // 停止所有导航
ai.isNavigating(zombie); // 是否正在导航
ai.isLooping(zombie);    // 是否有运行中的循环行为
```

---

## 四、核心 API 一览

[`AiAPI`](AiAPI.java) 的全部公开方法：

### 七工厂

| 方法 | 返回 | 说明 |
|------|------|------|
| `path(self)` | `NavigationExecutor` | 纯寻路计算，`compute()` 得 `PlannedPath` |
| `walk(self)` | `NavigationExecutor` | 导航执行，`start()` 驱动实体 |
| `chase(self, target)` | `LoopBehavior` | 追逐预配置（默认 10 tick 一轮） |
| `wander(self, radius)` | `LoopBehavior` | 游荡预配置（默认 40 tick 一轮） |
| `loop(self)` | `LoopBehavior` | 通用循环，全部参数自配 |
| `attack(self)` | `AttackExecutor` | 战斗动作链式配置，`fire()` 主线程执行 |
| `see(self)` | `SeeQuery` | 视野查询链式配置（任意线程） |

### 停止与查询

| 方法 | 说明 |
|------|------|
| `stop(entity)` | 停止循环行为（若有）与当前导航 |
| `stopAll()` | 停止所有导航 |
| `stopLoop(entity)` | 仅停止循环行为（不影响独立导航） |
| `isNavigating(entity)` | 是否正在导航 |
| `isLooping(entity)` | 是否有运行中的循环行为 |
| `getNavigator(entity)` | 获取导航器实例 |
| `activeNavigatorCount()` | 活跃导航器数量 |
| `getCurrentPath(entity)` | 当前完整路径（只读，`List<AStarNode>`） |
| `getRemainingPath(entity)` | 剩余路径（副本） |

### 路径可视化

| 方法 | 说明 |
|------|------|
| `showPathOnce(entity)` | 火焰粒子一次性标记路径 |
| `showPath(entity[, seconds])` | 持续显示路径（需先 `bindPlugin`） |
| `stopShowPath(entity)` | 停止持续显示 |

### 战术（单实体）

| 方法 | 说明 |
|------|------|
| `findCover(self, threat[, Pos], radius)` | 找掩体（Entity/坐标双版本） |
| `findFleePosition(self, threat[, Pos], radius)` | 远离威胁 |
| `findFlankPosition(self, target[, Pos], radius)` | 包抄（单实体） |
| `findHighGround(self, radius, minAdvantage)` | 寻找高地 |
| `findSightPosition(self, target[, Pos], radius[, idealDist])` | 占据视野点 |
| `findApproximatePosition(self, center[, radius])` | 模糊位置选取 |
| `hasReachedApproximate(self, center, radius)` | 是否已到模糊区域 |

### 团队战术

| 方法 | 说明 |
|------|------|
| `flankTarget(members, target, radius[, arcSpan])` | 协同包抄（钳形合围） |
| `surroundTarget(members, target, radius)` | 360° 包围 |
| `rally(members, point, formation, spacing)` | 阵型集结 |
| `formationOffsets(formation, count, spacing)` | 阵型偏移（纯几何） |

---

## 五、寻路子系统

寻路采用**策略模式**（插槽架构），所有算法实现统一的 [`PathfindingStrategy`](pathfinding/PathfindingStrategy.java) 接口，可在运行时插拔切换：

| 策略 | 类名 | 特点 | 适用场景 |
|------|------|------|----------|
| **A\***（默认） | [`AStarPathFinder`](pathfinding/astar/AStarPathFinder.java) | 全局最优，保证必达，单次开销较大 | 精英怪、Boss 等要求必达的场景 |
| **贪心** | [`GreedyPathFinder`](pathfinding/greedy/GreedyPathFinder.java) | 局部步进，不保证最优/必达，开销极低 | 杂兵群、游荡等低开销场景 |

```java
// 在行为/导航上切换策略（默认 A*）
ai.walk(zombie).to(pos).strategy(new GreedyPathFinder()).start();

// 或直接使用算法（低层）
PathResult r = new AStarPathFinder().findPath(level, start, target, new AStarOptions());
```

### 1. A* 寻路（astar 子包）

[`AStarPathFinder`](pathfinding/astar/AStarPathFinder.java) 关键实现细节：

- **OCTILE 启发式**（默认）：8 方向网格的最优启发，兼顾可采纳性与效率；另有 MANHATTAN / EUCLIDEAN / CHEBYSHEV 可选（[`HeuristicType`](pathfinding/astar/HeuristicType.java)）。
- **closed 表坐标编码**：已探索节点以 `Long` 编码坐标存 `HashSet`，避免对象开销。
- **方块快照缓存**（[`BlockSnapshotCache`](core/util/BlockSnapshotCache.java)）：单次搜索内缓存方块查询结果，重复访问同一列不再查世界。
- **检查顺序反转**：先验 open 表有效性再展开邻居，减少无效展开。

通过 [`AStarOptions`](pathfinding/astar/AStarOptions.java)（fluent setter）调整：

```java
AStarOptions options = new AStarOptions()
        .maxSearchNodes(3000)     // 最大搜索节点数（默认 1500）
        .allowDiagonal(true)      // 允许对角线移动（默认 true）
        .allowJump(true)          // 允许跳跃（默认 true）
        .maxDropHeight(4)         // 最大安全下落高度（默认 3）
        .entityHeight(2)          // 实体高度（默认 2）
        .heuristic(HeuristicType.OCTILE)
        .goalReachRadius(1.5)     // 到达判定半径（默认 1.0）
        .stepCostFunction((level, from, to, diagonal, opt) -> {
            // 外接评分器：经过岩浆旁额外付出高代价
            if (isNearLava(level, to)) return 50.0;
            return 0.0;
        })
        .partialOnFailure(true);  // 无完整解时回退部分路径

PathResult result = new AStarPathFinder().findPath(level, start, target, options);
```

> **外接评分器**（[`StepCostFunction`](pathfinding/StepCostFunction.java)）：在基础移动代价（对角线 1.414 / 直行 1.0 / 攀爬额外）之上叠加自定义代价。返回 `POSITIVE_INFINITY` 禁止某步通行，负值截断为 `0.01` 下限。

### 2. 贪心寻路（greedy 子包）

[`GreedyPathFinder`](pathfinding/greedy/GreedyPathFinder.java) 采用**自适应三阶段**局部步进架构，不全局搜索：

| 阶段 | 触发条件 | 行为 | 开销 |
|------|----------|------|------|
| ① 快速贪心 | 默认 | 枚举 8 邻居，距离+转向评分 | O(8) 极快 |
| ② 范围感知 | 连续无进展 | 扩大窗口，舍伍德随机采样 | 中等 |
| ③ 恢复 | 找到出路 | 回到阶段① | O(8) |

> **设计哲学**：贪心是**短视**的，每次调用最多走 `maxSteps`（默认 12）步。大范围绕行由上层循环多次调用组合完成——这正是 `LoopBehavior` 的工作方式。**不退化为 A\***：步数上限/目标远离→`PARTIAL`，区域穷尽→`NO_PATH`，外部取消→`CANCELLED`。

通过 [`GreedyOptions`](pathfinding/greedy/GreedyOptions.java) 调整：

```java
GreedyOptions opts = new GreedyOptions()
        .setWindowRadius(6)       // 阶段②观察窗口半径（默认 4）
        .setSamplesPerTick(12)    // 每步采样候选数（默认 8）
        .setMaxSteps(20)          // 单次最大步数（默认 12）
        .setScoreJitter(0.1)      // 随机扰动幅度（默认 0.05）
        .setEntityHeight(3);      // 实体高度（默认 2）
```

### 3. 寻路结果

[`PathResult`](pathfinding/PathResult.java) 是不可变结果对象：

- `getNodes()`：路径节点列表（`List<AStarNode>`），索引 0 为起点、末尾为目标。
- `getStatus()`：`SUCCESS` / `ALREADY_AT_GOAL` / `START_INVALID` / `NODE_LIMIT_EXCEEDED` / `NO_PATH` / `PARTIAL` / `CANCELLED`。
- `isSuccess()`：`SUCCESS` 和 `ALREADY_AT_GOAL` 均视为成功。
- `isPartial()`：是否为模糊解（离目标最近的部分路径）。
- `hasPath()`：是否携带可用路径。
- `length()` / `getTotalCost()` / `getExpandedNodes()` / `getDestination()`。

### 4. 可通行性判定

寻路依赖 [`BlockChecks`](core/util/BlockChecks.java)：`isSolid` / `isPassable` / `isStandable`。

---

## 六、行为子系统（LoopBehavior）

[`LoopBehavior`](core/behavior/LoopBehavior.java) 是所有持续行为的**同构主形态**——「计算 → 执行 → 判断」周期循环。`chase` / `wander` 都是它的预配置，自定义行为直接用 `loop()`。

### 1. 一轮的生命周期

```
每 interval tick：
  ① 读目标    target.get() → null 则以 TARGET_LOST 结束
  ② 计算      computeFn(ctx) → PlannedPath（经 ComputeCarrier，可在异步线程）
  ③ 执行      executeFn(ctx, plan)（回主线程；默认 plan.start() 驱动导航）
  ④ 判断      until(ctx) 为 true → 以 COMPLETED 结束
```

### 2. 链式配置

```java
BehaviorHandle handle = ai.loop(guard)
        .to(new FlankTarget(guard).target(enemy).radius(10)) // 目标（每轮动态读取）
        .strategy(new GreedyPathFinder())                    // 算法（默认 A*）
        .config(new GreedyOptions().setMaxSteps(16))         // 算法参数
        .speed(0.3)                                          // 移动速度
        .interval(20)                                        // 轮间隔 tick
        .until(ctx -> ctx.rounds() >= 5)                     // 停止条件
        .onComplete(outcome -> { ... })                      // 完成回调（主线程）
        .onExecutor(executor)                                // 计算载体（异步线程池）
        .start();                                            // 返回 BehaviorHandle
```

### 3. 关键组件

| 组件 | 说明 |
|------|------|
| [`Target`](core/targeting/Target.java) | 目标抽象：`get()` 每轮返回最新 `Vector3`，`null` 表示目标失效。`PointTarget`（固定点）/ `EntityTarget`（活实体，死亡自动失效） |
| [`ComputeCarrier`](core/behavior/ComputeCarrier.java) | 计算执行位置抽象：`ComputeCarriers.sync()`（同步，默认）/ `ComputeCarriers.of(executor)`（委托线程池）。计算完成后自动回主线程执行 |
| [`LoopContext`](core/behavior/LoopContext.java) | 轮上下文：`rounds()`（已执行轮数）、`lastPlan()`（上轮路径） |
| [`BehaviorOutcome`](core/behavior/BehaviorOutcome.java) | 终态：`COMPLETED`（满足 until）/ `TARGET_LOST`（目标失效）/ `STOPPED`（外部停止）/ `ENTITY_INVALID`（实体失效） |
| [`BehaviorHandle`](core/behavior/BehaviorHandle.java) | 运行句柄：`stop()` / `isRunning()` |

### 4. 预配置工厂

- **`chase(self, target)`**：`EntityTarget` + 10 tick 一轮。目标死亡/移除自动以 `TARGET_LOST` 结束。
- **`wander(self, radius)`**：内部 `WanderTarget`（极坐标随机采样）+ 40 tick 一轮。当前段未走完时跳过本轮（不打断），走完后停至多 40 tick 再选下一段。

---

## 七、导航执行（NavigationExecutor）

[`NavigationExecutor`](core/executor/NavigationExecutor.java) 负责「配置 → 计算 → 执行」的导航链，`path()` 与 `walk()` 返回同一执行器，语义侧重不同。

### 1. 两段式：compute() 与 start()

```java
// 一步到位（内部 = compute + start）
ai.walk(zombie).to(player).speed(0.3).start();

// 拆两段：异步计算后回主线程执行（大地图防卡顿）
PlannedPath plan = ai.path(zombie).to(farPos).compute();  // 任意线程可调
// ... 传递 plan ...
plan.start();  // 主线程驱动实体
```

[`PlannedPath`](core/executor/PlannedPath.java) 携带计算结果与执行参数：

- `hasPath()`：是否携带可用路径（`PARTIAL` 也算）。
- `getResult()`：底层 `PathResult`（`getNodes()` 等）。
- `start()`：提交导航（主线程）。
- `onComplete(cb)`：导航完成回调（`BehaviorOutcome`）。

### 2. 走完续算（continuous 模式）

```java
// 走完一段自动续算，直到到达（适合长距离静态目标）
ai.walk(npc).to(farPos).continuous().maxSegments(100).start();

// 续算段放入线程池计算，完成后自动回主线程驱动（大地图防段间卡顿）
ai.walk(npc).to(farPos).continuous().carrier(ComputeCarriers.of(pool)).start();
```

- 实体走完当前段后从最新位置续算下一段：`NavigatorManager` 复用同一 `Navigator`（`retarget` 换路径不换实体状态），段间衔接无缝、不重起步。
- 段重寻路经 `carrier` 指定的 [`ComputeCarrier`](core/behavior/ComputeCarrier.java) 执行（默认同步；配 `ComputeCarriers.of(executor)` 即异步，结果自动回主线程）。
- 终止条件：到达（距离 ≤ `reachRadius`）/ 寻路失败 / 实体失效 / 段数上限（`maxSegments`，默认 100）/ 外部 `stop`。

### 3. 全部配置方法

| 方法 | 说明 |
|------|------|
| `to(Target)` / `to(Vector3)` / `target(Entity)` | 目标（Target / 点 / 活实体） |
| `strategy(PathfindingStrategy)` | 算法（默认 A*） |
| `config(PathfindingConfig)` | 算法参数（AStarOptions / GreedyOptions） |
| `speed(double)` | 移动速度（方块/tick，默认 0.25） |
| `continuous()` | 开启走完续算模式 |
| `reachRadius(double)` | 到达判定半径 |
| `maxSegments(int)` | 续算最大段数 |
| `gaze(Gaze)` | 视角修正器（头/身朝向，默认 [`MovementGaze`](core/gaze/MovementGaze.java) 头身同向朝移动方向） |
| `carrier(ComputeCarrier)` | 计算载体（整段寻路与 continuous 续算共用，默认同步，配 [`ComputeCarriers.of(executor)`](core/behavior/ComputeCarriers.java) 即异步） |
| `compute()` | 纯计算，返回 `PlannedPath` |
| `start()` | 计算 + 执行一步到位 |

### 4. 速度参考

| 速度 | 大致表现 |
|------|----------|
| `0.15` | 缓慢行走 |
| `0.25`（默认） | 正常行走 |
| `0.35` | 小跑 |
| `0.5` | 快速奔跑 |

### 5. 视角修正器（Gaze）

导航移动时实体的头/身朝向由 [`Gaze`](core/gaze/Gaze.java) 决定（每 tick 应用）。Nukkit 广播 `MovePlayerPacket` 时头读 `headYaw`、身读 `yaw`——框架保证两者都写，头身不再分家：

```java
// 默认：MovementGaze——头身同向，朝移动方向（不配置即此行为）
ai.walk(zombie).to(pos).start();

// 边走边看向目标（头看目标、身体朝移动方向，目标丢失自动回退移动朝向）
ai.chase(wolf, playerTarget)
  .gaze(new TargetGaze(playerTarget).bodyFollow(false))
  .start();

// 平滑转头：限制每 tick 转角 + 反应延迟，更像生物
ai.chase(wolf, playerTarget)
  .gaze(new SmoothGaze(new TargetGaze(playerTarget))
      .maxHeadTurn(15).maxBodyTurn(8).reactionDelay(3))
  .start();

// 固定朝向（哨兵站岗、巡逻扭头看固定方向）
ai.walk(guard).to(post).gaze(new FixedGaze(90)).start();
```

| 修正器 | 行为 |
|--------|------|
| [`MovementGaze`](core/gaze/MovementGaze.java) | 头身同向朝移动方向（默认） |
| [`TargetGaze`](core/gaze/TargetGaze.java) | 头看向 `Target`/实体/坐标（每 tick 动态读取）；`bodyFollow(true)` 身体跟随、`aimHeight(1.5)` 瞄准高度 |
| [`FixedGaze`](core/gaze/FixedGaze.java) | 头身固定朝某 yaw |
| [`SmoothGaze`](core/gaze/SmoothGaze.java) | 装饰器：限制转头速度（`maxHeadTurn`/`maxBodyTurn` 度每 tick）+ `reactionDelay(ticks)` 反应延迟 |

`Gaze` 是单方法函数式接口（`apply(GazeContext)`），可自定义任意朝向逻辑；连续导航/续段间修正器实例复用，`SmoothGaze` 的平滑状态跨段保持。

---

## 八、战术子系统

### 1. 战术 Target（可直接作为循环目标）

六个战术能力均提供 [`Target`](core/targeting/Target.java) 实现，`get()` 时**现场扫描**并返回最佳位置：

| Target | 能力 | 链式配置 |
|--------|------|----------|
| [`CoverTarget`](core/tactical/CoverTarget.java) | 找掩体（遮挡威胁视线） | `.threat(entity)` / `.threat(pos)` / `.radius(r)` |
| [`FleeTarget`](core/tactical/FleeTarget.java) | 远离威胁（离威胁最远） | 同上 |
| [`FlankTarget`](core/tactical/FlankTarget.java) | 包抄（目标侧方） | `.target(entity)` / `.target(pos)` / `.radius(r)` |
| [`HighGroundTarget`](core/tactical/HighGroundTarget.java) | 寻找高地 | `.radius(r)` / `.minAdvantage(h)` |
| [`SightTarget`](core/tactical/SightTarget.java) | 占据视野点（能看到目标） | `.target(...)` / `.radius(r)` / `.idealDistance(d)` |
| [`ApproximateTarget`](core/tactical/ApproximateTarget.java) | 模糊位置（不确定区域最近点） | `.center(pos)` / `.radius(r)` |

```java
// 战术 Target 直接接入循环：守卫持续寻找掩体
ai.loop(guard).to(new CoverTarget(guard).threat(enemy).radius(8)).interval(40).start();
```

### 2. 单次战术查询（TacticalScanner）

不需要循环时，直接经 `ai.findCover(...)` 等方法单次查询，返回 [`TacticalPosition`](core/tactical/TacticalPosition.java)（`isPresent()` / `getPosition()`）。

### 3. 团队战术（TeamTactics）

```java
// 协同包抄：成员在目标远侧半圆均匀展开（钳形合围）
ai.flankTarget(members, target, 6);

// 360° 包围
ai.surroundTarget(members, target, 5);

// 阵型集结（横/纵/楔/环/方）
ai.rally(members, rallyPoint, FormationType.WEDGE, 2.0);
```

---

## 九、战斗与感知

### 1. 战斗（attack → AttackExecutor）

[`AttackExecutor`](core/executor/AttackExecutor.java) 是 [`CombatActions`](core/combat/CombatActions.java) 的链式门面，动作语义全部委托后者：

```java
ai.attack(zombie).melee(player, 4.0f).fire();          // 近战
ai.attack(skeleton).arrow(player).fire();              // 射箭（默认速度/散布）
ai.attack(skeleton).arrow(player, 1.5, 0.3).fire();    // 射箭（自定义）
ai.attack(witch).projectile("Snowball", player, 1.2, 0.2).fire();  // 投掷
ai.attack(alchemist).useItemOnSelf(potion).fire();     // 对自己使用物品
```

- `fire()` **仅限主线程**（有 Server 且非主线程抛 `IllegalStateException`）。
- 配置态对象可复用：`fire()` 后可重新配置。
- 失败统一返回 `false`（伤害被取消 / 抛射物未生成），不抛异常。

### 2. 视野感知（see → SeeQuery）

[`SeeQuery`](core/vision/SeeQuery.java) 是 [`VisionSensor`](core/vision/VisionSensor.java) 的链式门面：

```java
if (ai.see(zombie).range(24).fov(120).canSee(player)) { ... }  // 距离+FOV+视线
if (ai.see(guard).range(32).canSee360(intruder)) { ... }       // 全向（不限朝向）
ai.see(zombie).angleTo(player);                                 // 相对夹角 [0,180]
```

- 默认参数与 `VisionSensor` 一致：16 格 / 90° FOV。
- 无状态纯读，任意线程可查。

---

## 十、完整示例：一只会战斗的怪物 AI

```java
public class MonsterBrain {

    public static void attach(AiAPI ai, Entity zombie, Player player) {
        // 感知驱动：每 10 tick 检查一次视野
        ai.loop(zombie)
          .to(new SightTarget(zombie).target(player).radius(12))  // 持续占据视野点
          .interval(10)
          .compute(ctx -> {
              // 视野内有玩家 → 追击路径；否则返回 null（本轮空转）
              if (!ai.see(zombie).range(16).canSee(player)) {
                  return null;
              }
              return ai.path(zombie).target(player).compute();
          })
          .execute((ctx, plan) -> {
              if (plan != null && plan.hasPath()) {
                  plan.start();
                  // 距离够近则攻击
                  if (zombie.distance(player) < 2.0) {
                      ai.attack(zombie).melee(player, 4.0f).fire();
                  }
              }
          })
          .until(ctx -> player.closed)          // 玩家下线则结束
          .onComplete(outcome -> ai.wander(zombie, 10).start())  // 战斗结束去游荡
          .start();
    }
}
```

---

## 十一、常见问题

**Q：`chase` 和 `walk + continuous` 有什么区别？**
`chase` 是 `LoopBehavior` 预配置，面向**移动目标**——每轮从目标最新位置重算；`continuous` 是 `NavigationExecutor` 的执行模式，面向**静态目标**——走完一段才续算，事件驱动、开销更低。

**Q：贪心寻路找不到路怎么办？**
贪心短视，单次最多 `maxSteps` 步。找不到完整路径时返回 `PARTIAL`（离目标最近的部分路径），由上层循环（`LoopBehavior`）在走完后继续调用逐步逼近。区域彻底不可达才返回 `NO_PATH`。

**Q：`compute()` 能在异步线程调用吗？**
可以。`path().compute()` 是纯计算不驱动实体；`LoopBehavior` 配合 `onExecutor(threadPool)` 可将每轮计算放入线程池，完成后自动回主线程执行。

**Q：如何调试路径？**
`ai.showPathOnce(entity)` 用火焰粒子标记当前路径；`ai.showPath(entity, 10)` 持续显示 10 秒（需先 `bindPlugin`）。

**Q：实体死亡/卸载后行为会泄漏吗？**
不会。`LoopBehavior` 每轮检查实体有效性（`closed` / `getLevel()`），失效即以 `ENTITY_INVALID` 结束并回收；`EntityTarget` 对目标同样处理（`TARGET_LOST`）。

**Q：为什么实体移动时头和身体朝向不一致（侧头）？**
Nukkit 对非 Player 实体广播移动包时，头读 `headYaw`、身读 `yaw` 两个字段。框架导航与 `CombatActions.faceTo()` 均已同时写两者；若自定义代码直接改 `entity.yaw`，需同步 `entity.headYaw = yaw`，或改用 [`Gaze`](core/gaze/Gaze.java) 修正器交给框架统一管理。

**Q：导航中的实体会被击退吗？**
会。导航器复刻 Nukkit 原生生物的 `knockbackTicks` 保护期：检测到击退（被击飞 `motionY` 抬升，或腾空且水平速度远超导航速度）时进入约 15 tick 恢复期，期间不覆盖 motion、仅应用惯性位移，实体被正常击飞滑行；落地或惯性衰减后自动恢复寻路。自身跳跃不误判为击退。
