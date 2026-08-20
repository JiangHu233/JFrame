# jframe_ai 开发者文档

> 面向**贡献者与高级使用者**的技术文档。涵盖架构设计、核心算法实现细节、扩展指南与性能考量。
> 面向使用者的快速上手请参阅 [`README.md`](README.md)。

---

## 目录

- [一、架构总览](#一架构总览)
- [二、包结构](#二包结构)
- [三、寻路引擎详解](#三寻路引擎详解)
- [四、行为引擎详解（LoopBehavior）](#四行为引擎详解loopbehavior)
- [五、导航执行详解（NavigationExecutor）](#五导航执行详解navigationexecutor)
- [六、导航调度详解](#六导航调度详解)
- [七、战术子系统详解](#七战术子系统详解)
- [八、战斗瞄准算法](#八战斗瞄准算法)
- [九、工具类](#九工具类)
- [十、Spring 装配机制](#十spring-装配机制)
- [十一、扩展指南](#十一扩展指南)
- [十二、性能考量](#十二性能考量)
- [十三、测试](#十三测试)

---

## 一、架构总览

模块采用**两区 + 分层单向 DAG**设计。

### 两区划分

| 区 | 包 | 职责 | 约束 |
|----|----|------|------|
| **算法区** | `pathfinding/` | 策略变体（A* / 贪心），插槽架构可插拔 | 只做纯计算，不含业务策略 |
| **库区** | `core/` | 无状态能力件（行为 / 执行 / 调度 / 战术 / 感知 / 战斗 / 工具） | 不感知具体算法变体，经 `PathfindingStrategy` 插槽引用 |

### 分层结构

```
┌──────────────────────────────────────────────────────────────────┐
│ 门面层   AiAPI（七工厂：path/walk/chase/wander/loop/attack/see）   │
├──────────────────────────────────────────────────────────────────┤
│ 行为层   LoopBehavior（计算→执行→判断 循环，chase/wander 预配置）   │
│ 执行层   NavigationExecutor / PlannedPath（两段式 + 续算）          │
│         AttackExecutor（战斗链式配置）  SeeQuery（视野链式查询）     │
├──────────────────────────────────────────────────────────────────┤
│ 调度层   NavigatorManager → Navigator（实体移动驱动，单调度器）      │
├──────────────────────────────────────────────────────────────────┤
│ 战术层   TacticalScanner + 六个战术 Target + TeamTactics           │
├──────────────────────────────────────────────────────────────────┤
│ 算法区   AStarPathFinder（A*）  GreedyPathFinder（贪心）            │
│ 工具层   BlockChecks / LineOfSight / BlockSnapshotCache / VisionSensor │
└──────────────────────────────────────────────────────────────────┘
```

**依赖方向**：上层依赖下层；库区经 [`PathfindingStrategy`](pathfinding/PathfindingStrategy.java) 插槽引用算法区，不感知具体变体。Bean DAG：

```
AStarPathFinder ──┐
GreedyPathFinder ─┤
NavigatorManager ─┼──→ AiAPI（六参构造注入）
TacticalScanner ──┤
CombatActions ────┤
TeamTactics ──────┘
```

### 设计原则

1. **无状态优先**：算法区全部组件与 `TacticalScanner` / `CombatActions` 均为无状态纯计算，可作 Spring 单例被多线程并发调用。有状态部分（`Navigator` / `LoopBehavior` 运行态）分别封装在 `NavigatorManager` 的并发容器与静态 ACTIVE 表内。
2. **宽容失败**：寻路、战术扫描永不抛异常，统一返回带状态码的结果对象（[`PathResult`](pathfinding/PathResult.java) / [`TacticalPosition`](core/tactical/TacticalPosition.java)），调用方自行决策回退。
3. **熔断保护**：A* 搜索有节点上限（`maxSearchNodes`），贪心有步数上限（`maxSteps`），避免在无解地形上耗尽 CPU。
4. **主线程纪律**：所有驱动实体的操作（导航启动 / 战斗 fire / 循环执行段）必须主线程；纯计算（寻路 / 视野查询）任意线程。`LoopBehavior` 的计算段可经 `ComputeCarrier` 移交线程池，完成后自动回主线程。

---

## 二、包结构

```
io.github.JiangHu.jframe.ai
├── AiAPI.java                     门面（七工厂 + 查询/停止 + 战术 + 可视化）
├── config/
│   └── AiSpringConfig.java        Spring 配置（@ImportResource）
│
├── core/                          【库区】无状态能力件
│   ├── behavior/                  行为引擎
│   │   ├── LoopBehavior.java      循环行为（计算→执行→判断）
│   │   ├── LoopContext.java       轮上下文（rounds / lastPlan）
│   │   ├── BehaviorOutcome.java   终态枚举（COMPLETED/TARGET_LOST/STOPPED/ENTITY_INVALID）
│   │   ├── BehaviorHandle.java    运行句柄（stop / isRunning）
│   │   ├── ComputeCarrier.java    计算载体抽象
│   │   ├── ComputeCarriers.java   载体工厂（sync / of(executor)）
│   │   └── ComputeTask.java       计算任务（context/run/complete/fail）
│   ├── executor/                  执行器
│   │   ├── NavigationExecutor.java 导航链式配置（compute/start/continuous）
│   │   ├── PlannedPath.java       计算结果 + 执行参数（两段式载体）
│   │   └── AttackExecutor.java    战斗链式配置（fire）
│   ├── navigation/                导航调度
│   │   ├── Navigator.java         单实体路径跟随（tick 驱动，节点无缝跳过）
│   │   └── NavigatorManager.java  多实体调度（PluginAware，单调度器，retarget 续段复用）
│   ├── gaze/                      视角修正器（头/身朝向）
│   │   ├── Gaze.java              函数式接口（apply(GazeContext)，每 tick 应用）
│   │   ├── GazeContext.java       应用上下文 + 纯角度函数（yawTowards/pitchTowards/angleDiff）
│   │   ├── MovementGaze.java      头身同向朝移动方向（默认）
│   │   ├── TargetGaze.java        头看向 Target（动态读取，丢失回退移动朝向）
│   │   ├── FixedGaze.java         头身固定 yaw
│   │   └── SmoothGaze.java        装饰器：限速转头 + 反应延迟
│   ├── target/                    目标抽象
│   │   ├── Target.java            接口（get() 每轮动态读取）
│   │   ├── PointTarget.java       固定点
│   │   └── EntityTarget.java      活实体（死亡/移除自动失效）
│   ├── tactical/                  战术
│   │   ├── TacticalScanner.java   单实体战术扫描（评分制）
│   │   ├── TacticalPosition.java  战术位置结果（record）
│   │   ├── CoverTarget / FleeTarget / FlankTarget /
│   │   │   HighGroundTarget / SightTarget / ApproximateTarget.java  六个战术 Target
│   │   ├── FormationType.java     团队阵型枚举（横/纵/楔/环/方）
│   │   └── TeamTactics.java       团队战术（包抄/包围/集结）
│   ├── vision/
│   │   ├── VisionSensor.java      视野感知（距离+FOV+视线）
│   │   └── SeeQuery.java          链式查询门面
│   ├── combat/
│   │   └── CombatActions.java     攻击/射箭/投掷/使用物品
│   └── util/
│       ├── BlockChecks.java       方块可通行性/可站立判定
│       ├── LineOfSight.java       DDA 射线投射视线检测
│       ├── BlockSnapshotCache.java 方块快照缓存（单次搜索内）
│       └── PathVisualizer.java    路径粒子可视化
│
└── pathfinding/                   【算法区】策略变体
    ├── PathfindingStrategy.java   策略接口（插槽）
    ├── PathfindingConfig.java     参数标记接口
    ├── PathResult.java            寻路结果（不可变，含 PARTIAL）
    ├── StepCostFunction.java      外接评分器（函数式接口）
    ├── astar/                     A* 变体
    │   ├── AStarPathFinder.java   A* 实现（默认策略）
    │   ├── AStarNode.java         A* 节点
    │   ├── AStarOptions.java      A* 参数（fluent setter）
    │   └── HeuristicType.java     启发式枚举（OCTILE 默认）
    └── greedy/                    贪心变体
        ├── GreedyPathFinder.java  贪心实现（自适应三阶段）
        └── GreedyOptions.java     贪心参数
```

---

## 三、寻路引擎详解

### 1. 策略插槽

[`PathfindingStrategy`](pathfinding/PathfindingStrategy.java) 是算法区对外的唯一插槽：

```java
public interface PathfindingStrategy {
    PathResult findPath(Level level, Vector3 start, Vector3 target, PathfindingConfig config);
}
```

库区所有组件（`LoopBehavior` / `NavigationExecutor`）只持有 `PathfindingStrategy` 引用，默认注入 `AStarPathFinder`，运行时可换任意实现。

### 2. A*（astar 子包）

[`AStarPathFinder`](pathfinding/astar/AStarPathFinder.java) 核心逻辑封装在私有内部类 `Search` 中，保证外层无状态。

**数据结构**：

| 结构 | 类型 | 作用 |
|------|------|------|
| `open` | `PriorityQueue<AStarNode>` | 待探索节点，按 `fCost = g + h` 升序 |
| `closed` | `HashSet<Long>` | 已确定最优的坐标（**Long 编码**，避免对象开销） |
| `snapshot` | `BlockSnapshotCache` | 单次搜索内的方块查询缓存 |
| `expanded` | `int` | 已展开节点计数（用于熔断） |

**关键优化**：

- **OCTILE 启发式**（默认）：`max(dx,dz) + (√2−1)·min(dx,dz)`，8 方向网格的最优可采纳启发。另有 MANHATTAN / EUCLIDEAN / CHEBYSHEV 可选（[`HeuristicType`](pathfinding/astar/HeuristicType.java)）。
- **closed 坐标 Long 编码**：`(x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | (y & 0xFFFL)`，`HashSet<Long>` 替代节点对象集合。
- **检查顺序反转**：先验 open 表弹出节点有效性（closed 查询）再展开邻居，减少无效邻居枚举。
- **方块快照缓存**（[`BlockSnapshotCache`](core/util/BlockSnapshotCache.java)）：同一列的可站立性 / 实体高度检查在单次搜索内只做一次，重复访问命中缓存。

**主循环**（`Search.run`）：

```
1. 初始化：start.g = 0; start.h = heuristic(start, goal); open.add(start)
2. while open 非空:
     a. current = open.poll()
     b. 若 current 已在 closed → 跳过（惰性去重）
     c. current 加入 closed（Long 编码）
     d. 距 goal² ≤ reachRadius² → 回溯路径，SUCCESS
     e. expanded++ 超过 maxSearchNodes → 熔断，PARTIAL/失败
     f. 枚举邻居（DIRS_4 / DIRS_8）:
          - 可通行性检查（BlockChecks + 快照缓存）
          - 代价 = 基础(对角 1.414 / 直行 1.0 / 攀爬额外) + stepCost 评分器
          - 评分器返回 POSITIVE_INFINITY → 剪枝该步
          - 邻居未在 closed 且 g 更优 → 更新并入 open
3. open 耗尽 → NO_PATH（partialOnFailure 时回退离 goal 最近的节点回溯 → PARTIAL）
```

**模糊解（PARTIAL）**：搜索失败时记录搜索过程中离目标最近的节点，从它回溯出部分路径。调用方拿到「至少朝目标前进了一段」的路径，配合上层循环逐步逼近。

### 3. 贪心（greedy 子包）

[`GreedyPathFinder`](pathfinding/greedy/GreedyPathFinder.java) 不做全局搜索，**自适应三阶段**局部步进：

| 阶段 | 触发 | 行为 | 开销 |
|------|------|------|------|
| ① 快速贪心 | 默认 | 枚举 8 邻居，评分 = 距目标距离 + 转向惩罚 + 随机扰动 | O(8) |
| ② 范围感知 | 连续无进展 | 以当前为中心扩大 `windowRadius` 观察窗，舍伍德随机采样 `samplesPerTick` 个候选 | 中 |
| ③ 恢复 | 找到出路 | 回到阶段① | O(8) |

**设计哲学**：贪心是**短视**的——单次调用最多 `maxSteps` 步（默认 12），大范围绕行由上层循环（`LoopBehavior`）多次调用组合完成。**刻意不退化为 A\***：步数上限 / 目标远离 → `PARTIAL`；区域穷尽 → `NO_PATH`；外部取消 → `CANCELLED`。

### 4. 外接评分器

[`StepCostFunction`](pathfinding/StepCostFunction.java)：

```java
double stepCost(Level level, Vector3 from, Vector3 to, boolean diagonal, PathfinderOptions options);
```

- 叠加在基础移动代价之上；返回 `POSITIVE_INFINITY` 禁止该步；负值截断为 `0.01` 下限。
- 典型用法：远离岩浆、贴墙走、避开领地等业务代价。

---

## 四、行为引擎详解（LoopBehavior）

[`LoopBehavior`](core/behavior/LoopBehavior.java) 是所有持续行为的**同构主形态**：chase / wander 都是它的预配置，不再有独立的行为类。

### 1. 一轮生命周期

```
每 interval tick（错峰启动：首个 tick = entityId % interval）：
  ① 读目标   target.get() → null 则 finish(TARGET_LOST)
  ② 计算     computeFn(ctx) → PlannedPath
             经 ComputeCarrier.dispatch(ComputeTask) 分发
  ③ 执行     complete(plan) 回调 → postToMain → executeFn(ctx, plan)
             默认 executeFn：plan.hasPath() → plan.start()
  ④ 判断     until(ctx) == true → finish(COMPLETED)
```

**实体有效性**：每轮开头检查 `self.closed` / `self.getLevel()`，失效即 `finish(ENTITY_INVALID)` 并从 ACTIVE 表移除——实体死亡/卸载不泄漏。

### 2. 线程模型

| 段 | 线程 | 说明 |
|----|------|------|
| 读目标 / 判断 / 执行 | 主线程 | 驱动实体必须主线程 |
| 计算 | 取决于载体 | `ComputeCarriers.sync()`（默认，主线程同步算）或 `ComputeCarriers.of(executor)`（线程池） |

[`ComputeCarrier`](core/behavior/ComputeCarrier.java) 只有一个方法 `dispatch(ComputeTask task)`；[`ComputeTask`](core/behavior/ComputeTask.java) 四方法：`context()` / `run()`（载体线程执行）/ `complete(plan)` / `fail(error)`。`complete` / `fail` 的默认实现由 `LoopBehavior` 提供，内部 `postToMain` 保证执行段回到主线程。

### 3. ACTIVE 表与句柄

- 静态 `ACTIVE: Map<Long, LoopBehavior>`（实体 ID → 运行中行为），**同实体同时至多一个循环行为**，新 `start()` 顶掉旧的（旧的自然结束，`STOPPED`）。
- [`BehaviorHandle`](core/behavior/BehaviorHandle.java)：`stop()`（postToMain 异步停止）/ `isRunning()` / `lastPlan()`。
- 静态查询：`LoopBehavior.isRunning(entity)` / `activeBehavior(entity)`。

### 4. 预配置工厂（AiAPI 内）

| 工厂 | 预配置 |
|------|--------|
| `chase(self, target)` | `EntityTarget` + `interval(10)`。目标死亡/移除 → `TARGET_LOST` 自动结束 |
| `wander(self, radius)` | 内部 `WanderTarget`（极坐标随机采样）+ `interval(40)`。当前段未走完跳过本轮（不打断），走完停至多 40 tick 选下一段 |

---

## 五、导航执行详解（NavigationExecutor）

[`NavigationExecutor`](core/executor/NavigationExecutor.java) 承担「配置 → 计算 → 执行」链，`path()` / `walk()` 返回同一执行器。

### 1. 两段式

- `compute()`：纯计算，任意线程。产出 [`PlannedPath`](core/executor/PlannedPath.java)（携带 `PathResult` + 执行参数）。
- `PlannedPath.start()`：主线程提交导航（内部经 `NavigatorManager`）。
- `start()`（执行器上）：`compute + start` 一步到位，主线程。

两段式的价值：**大地图防卡顿**——异步线程 `compute()`，完成后传回主线程 `start()`；`PlannedPath` 不可变，跨线程传递安全。

### 2. continuous 模式（走完续算）

`continuous()` 开启后，`PlannedPath.start()` 提交的不是单段导航，而是「走完当前段 → 从最新位置续算下一段」的循环，直到：

- 到达（距目标 ≤ `reachRadius`）
- 某段寻路失败（`NO_PATH` 且非 `PARTIAL`）
- 实体失效
- 段数达 `maxSegments` 上限（默认 100）
- 外部 `stop(entity)`

**段间衔接**：段完成时 `NavigatorManager` 不销毁导航器，而是 `Navigator.retarget(next)` 原地换路径——运动状态与 `Gaze` 修正器实例（含 `SmoothGaze` 平滑状态）跨段保留，无停顿重启。

**段重寻路线程**：续算寻路经 `carrier(ComputeCarrier)` 指定的载体执行（默认同步）。配 `ComputeCarriers.of(executor)` 后段重寻路在线程池计算，完成后经 `postToMain`（`Server.scheduleTask`）回主线程才写实体——异步期间导航器已结束/实体失效的"僵尸段"结果会被丢弃（`finished` 与实体有效性双重校验）。

适合**静态目标长距离导航**；移动目标用 `chase()`（每轮从目标最新位置重算）。

### 3. 与 LoopBehavior 的关系

`LoopBehavior` 默认 `executeFn` 即 `plan.start()`；`chase` 循环 = 「每 10 tick `compute()` + `plan.start()`」的组合。`NavigationExecutor` 是单次/续算导航，`LoopBehavior` 是通用循环——两者共享 `PlannedPath` 与 `NavigatorManager`。

---

## 六、导航调度详解

### NavigatorManager（core/navigation）

- **单调度器**：`bindPlugin(plugin)` 时注册**一个** Nukkit 定时任务，每 tick 调 `tickAll()` 遍历所有 `Navigator.tick()`——多实体导航只有一个调度任务，不随实体数增长。
- **并发容器**：`navigators: Map<Long, Navigator>`（实体 ID → 导航器）。
- **续段复用**：`navigateOrPartial()` 对已 `finished` 的导航器调 `Navigator.retarget(next)` 原地换路径（运动与 gaze 状态保留），而非销毁重建——continuous 续段与 chase 每轮导航均走此路径，段间无停顿；进行中的导航器则被新导航**取代**。
- **快照迭代**：`tickAll()` 遍历 `entrySet` 快照（`ArrayList` 拷贝），删除用两参 `remove(key, value)` 条件删除——tick 栈内 onComplete 回调注册的新导航器不会被误删，`retarget` 复活的导航器不会被旧引用清掉。
- `stop(entity)` / `stopAll()` / `isNavigating(entity)` / `activeNavigatorCount()`。
- `shutdown()`：插件卸载时停全部导航与调度任务。

### Navigator（单实体路径跟随）

`tick()` 每 tick 推进实体：沿路径节点序列移动（`speed` 方块/tick），处理跳跃（Y 差 + 跳跃标志）、**节点无缝切换**（单 tick 内 while 跳过所有已到达的节点，不在节点上停留）、到达判定（末节点距离 ≤ 阈值 → `finished`）。移动朝向经 [`Gaze`](core/gaze/Gaze.java) 修正器应用（默认 `MovementGaze` 头身同向，`yaw`/`headYaw` 同时写——Nukkit 广播移动包头身分读两字段，只写 `yaw` 会侧头）。`finished` 后从管理器移除。

**击退兼容**：复刻 Nukkit 原生生物 `knockbackTicks` 保护期。`tick()` 检测到击退特征（被击飞抬升 `motionY > 0.2` 且非自身跳跃，或腾空且水平速度 > 1.6× 导航速度）时进入约 15 tick 恢复期——期间**不覆盖** motion、仅 `move()` 应用现有惯性位移，让击退惯性自然滑行；落地或惯性衰减到导航速度以下后自动恢复寻路。自身跳跃由 `jumpedLastTick` 标记排除，不误判为击退。

---

## 七、战术子系统详解

### 1. Target 体系（core/targeting + core/tactical）

[`Target`](core/targeting/Target.java) 接口：

```java
public interface Target {
    Vector3 get();   // 每轮动态读取；null 表示目标失效
}
```

- [`PointTarget`](core/targeting/PointTarget.java)：固定坐标。
- [`EntityTarget`](core/targeting/EntityTarget.java)：活实体，`closed` / `getLevel() == null` 返回 null。
- 六个战术 Target（`get()` 时**现场扫描**）：`CoverTarget` / `FleeTarget` / `FlankTarget` / `HighGroundTarget` / `SightTarget` / `ApproximateTarget`——战术能力由此获得**循环性**（每轮重新评估最佳位置），可直接接入 `loop()` / `chase()`。

### 2. TacticalScanner 评分算法

[`TacticalScanner`](core/tactical/TacticalScanner.java)（无状态单例）：

- **采样**：以自身为中心极坐标网格采样候选位置（半径内、角度分档），经 `BlockChecks.isStandable` 过滤。
- **评分**（各能力不同）：
  - 掩体：候选点与威胁连线被方块遮挡（`LineOfSight` DDA）→ 得分高；离自身近加分。
  - 远离：离威胁距离越远得分越高。
  - 包抄：候选点在目标侧/后方（与「自身→目标」向量夹角大）得分高。
  - 高地：候选 Y − 自身 Y ≥ `minAdvantage` 才入围，越高越好。
  - 视野点：候选点能看到目标（DDA 视线通）+ 距离接近 `idealDistance` 加分。
- **快照共享**：单次扫描内所有候选共享一份方块快照，避免重复查世界。

### 3. TeamTactics（团队战术）

- `flankTarget(members, target, radius[, arcSpan])`：成员在目标**远侧半圆**均匀展开（钳形合围），角度偏移按成员索引分配。
- `surroundTarget(members, target, radius)`：360° 均匀环绕。
- `rally(members, point, formation, spacing)`：横 / 纵 / 楔 / 环 / 方五种阵型偏移（`formationOffsets` 纯几何计算）。

---

## 八、战斗瞄准算法

[`CombatActions`](core/combat/CombatActions.java) 的抛射物瞄准（预补偿直线模型）：

```
发射方向 = normalize(targetPos + velocity × t − selfPos)
t = distance / projectileSpeed     // 预估飞行时间，补偿目标线性移动
散布   = 基础散布 × 距离衰减        // arrow(target, speed, spread)
```

- 近战：`EntityAttackEvent` 走事件总线，被取消返回 false。
- 射箭 / 投掷：构造 `EntityArrow` / `EntityProjectile` 子类，设置速度向量后 spawn。
- 全部动作 null / 非法参数防御返回 false / null，不抛异常。
- [`AttackExecutor`](core/executor/AttackExecutor.java) 是其链式门面：配置态对象可复用，`fire()` 校验主线程后按 `Kind` 分派。

---

## 九、工具类

| 类 | 要点 |
|----|------|
| [`BlockChecks`](core/util/BlockChecks.java) | `isSolid` / `isPassable` / `isStandable`（脚部实心 + 头部空间 + 下方支撑），寻路与战术共用的可通行性标准 |
| [`LineOfSight`](core/util/LineOfSight.java) | **DDA 格点遍历**射线投射：沿射线逐格检查实心方块，遇到即遮挡。比逐点采样（可能穿墙缝）更精确且开销稳定 |
| [`BlockSnapshotCache`](core/util/BlockSnapshotCache.java) | 单次搜索内的方块查询缓存（列级），A* 与 TacticalScanner 共用 |
| [`VisionSensor`](core/vision/VisionSensor.java) | 距离（range）+ FOV（朝向夹角 ≤ fov/2）+ 视线（LineOfSight）三重判断；`canSee360` 跳过 FOV；`angleTo` 返回 [0,180] 夹角 |
| [`PathVisualizer`](core/util/PathVisualizer.java) | 火焰粒子标记路径节点；`showPath` 持续模式经 NavigatorManager 的 plugin 注册重复任务 |

---

## 十、Spring 装配机制

[`ai-spring.xml`](../../../resources/ai-spring.xml) 按「算法区变体 → 调度 → 扫描/战斗/团队 → 门面」顺序声明 Bean：

```xml
<!-- 算法区：两个策略变体 -->
<bean id="aStarPathFinder" class="io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder"/>
<bean id="greedyPathFinder" class="io.github.JiangHu.jframe.ai.pathfinding.greedy.GreedyPathFinder"/>

<!-- 库区：调度器 -->
<bean id="navigatorManager" class="io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager"/>

<!-- 库区：能力件 -->
<bean id="tacticalScanner" class="io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner"/>
<bean id="combatActions" class="io.github.JiangHu.jframe.ai.core.combat.CombatActions"/>
<bean id="teamTactics" class="io.github.JiangHu.jframe.ai.core.tactical.TeamTactics"/>

<!-- 门面：六参构造注入 -->
<bean id="aiAPI" class="io.github.JiangHu.jframe.ai.AiAPI">
    <constructor-arg ref="aStarPathFinder"/>
    <constructor-arg ref="greedyPathFinder"/>
    <constructor-arg ref="navigatorManager"/>
    <constructor-arg ref="tacticalScanner"/>
    <constructor-arg ref="combatActions"/>
    <constructor-arg ref="teamTactics"/>
</bean>
```

- [`AiSpringConfig`](config/AiSpringConfig.java)（`@ImportResource`）由 JFrame 插件加载机制触发。
- 行为 / 执行器（`LoopBehavior` / `NavigationExecutor` / `AttackExecutor` / `SeeQuery`）**不装配**——它们是每次工厂调用现场创建的短生命周期对象。
- `bindPlugin(plugin)`：`NavigatorManager` 与 `PathVisualizer` 需要插件引用注册调度任务，由宿主插件启动时调用。

---

## 十一、扩展指南

### 1. 自定义寻路策略

实现 `PathfindingStrategy` + 自带 `PathfindingConfig`：

```java
public class JpsPathFinder implements PathfindingStrategy {
    @Override
    public PathResult findPath(Level level, Vector3 start, Vector3 target, PathfindingConfig config) {
        // ... 返回 PathResult（成功 / PARTIAL / NO_PATH）
    }
}

// 使用
ai.walk(zombie).to(pos).strategy(new JpsPathFinder()).start();
```

约定：纯计算、无状态、永不抛异常、失败返回带状态码的 `PathResult`。

### 2. 自定义 Target

```java
public class PatrolTarget implements Target {
    private final List<Vector3> waypoints;
    private int index;
    @Override
    public Vector3 get() {
        Vector3 next = waypoints.get(index);
        // 到达当前路点后切下一个；循环巡逻
        return next;
    }
}

ai.loop(guard).to(new PatrolTarget(...)).interval(40).start();
```

约定：`get()` 每轮调用一次，轻量；目标失效返回 null。

### 3. 自定义 ComputeCarrier

```java
ExecutorService pool = Executors.newFixedThreadPool(2);
ComputeCarrier carrier = ComputeCarriers.of(pool);
ai.chase(zombie, player).carrier(carrier).start();   // 每轮计算进线程池
```

约定：`dispatch` 内部调用 `task.run()`（任意线程），结果经 `task.complete` / `task.fail` 回传——`LoopBehavior` 的默认实现已保证回主线程。

### 4. 自定义视角修正器（Gaze）

```java
// 例：移动时头看目标、身体朝移动方向，头俯仰跟随目标高度
Gaze lookAtTarget = ctx -> {
    Vector3 pos = ctx.entity().add(0, ctx.entity().getEyeHeight(), 0);
    double dx = targetX - pos.x, dy = targetY - pos.y, dz = targetZ - pos.z;
    if (dx * dx + dz * dz > 1e-6) {
        ctx.entity().headYaw = (float) GazeContext.yawTowards(dx, dz);
        ctx.entity().pitch = (float) GazeContext.pitchTowards(dx, dy, dz);
    }
};

ai.chase(wolf, player).gaze(new SmoothGaze(lookAtTarget).maxHeadTurn(12)).start();
```

约定：`apply` 每 tick 在主线程调用一次；角度计算用 [`GazeContext`](core/gaze/GazeContext.java) 纯函数（`yawTowards` 符合 Nukkit yaw 约定：0 朝 +Z、顺时针正、`atan2(-dx, dz)`；`pitch` 上仰为负）；有状态修正器（如 `SmoothGaze`）实例在续段间复用，状态跨段保留。

### 5. 自定义行为（compute / execute 全接管）

```java
ai.loop(bot)
  .compute(ctx -> myCustomPlan(ctx))                    // 计算段
  .execute((ctx, plan) -> myCustomAct(ctx, plan))       // 执行段（主线程）
  .interval(5).start();
```

---

## 十二、性能考量

| 项 | 措施 |
|----|------|
| A* 单次开销 | OCTILE 最优启发（展开更少）+ closed Long 编码（去重 O(1)）+ 快照缓存（重复列查询 O(1)）+ 节点上限熔断 |
| 贪心单次开销 | O(8) 邻居枚举，无全局搜索；杂兵群 / 游荡场景首选 |
| 大地图长距离 | `continuous()` 分段续算（单段开销恒定）或 `LoopBehavior` + 异步载体 |
| 多实体调度 | 单调度器 `tickAll()`，不随实体数增加调度任务 |
| 多实体错峰 | 循环首 tick = `entityId % interval`，避免同 tick 集中计算 |
| 视线检测 | DDA 格点遍历，开销与距离线性且无采样漏洞 |
| 战术扫描 | 单次扫描共享方块快照；候选经可站立性预过滤 |
| GC 压力 | `PathResult` / `PlannedPath` / `TacticalPosition` 不可变，无隐藏可变状态；closed 表用 Long 原生类型 |

---

## 十三、测试

测试位于 `src/test/java/io/github/JiangHu/jframe/ai/`，**只测纯逻辑**——不依赖运行中的服务器（Entity / Level / 调度相关标注「需真实服务器环境集成验证，此处不覆盖」）：

| 测试 | 覆盖 |
|------|------|
| `pathfinding/PathFinderLogicTest` | A* 主流程：直线路径、绕障、对角线、模糊解、评分器、参数边界 |
| `pathfinding/ExtendedPathfindingLogicTest` | 启发式对比、熔断、取消 |
| `pathfinding/greedy/GreedyPathFinderLogicTest` | 贪心三阶段、PARTIAL 语义、参数边界 |
| `core/targeting/TargetLogicTest` | PointTarget / EntityTarget / 战术 Target 的失效与读取约定 |
| `core/behavior/LoopBehaviorLogicTest` | 链式配置、载体分发、complete/fail 回调、ACTIVE 表 |
| `core/executor/AttackExecutorLogicTest` | 链式配置、fire 防御分支 |
| `core/vision/SeeQueryLogicTest` | 链式配置、默认常量、null 防御 |
| `AiAPILogicTest` | 六参构造注入、七工厂 null 防御 |
| `combat/CombatActionsBallisticTest` | 抛射物瞄准预补偿的纯数学部分 |
| `tactical/*` | 阵型偏移几何、模糊位置 |

运行：`mvn -pl jframe_ai test`。
