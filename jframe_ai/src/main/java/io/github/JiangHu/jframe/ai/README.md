# jframe_ai

> Minecraft 基岩版（Nukkit MOT）插件框架 **JFrame** 的 AI 模块。
> 提供基于方块世界的 **启发式实体寻路**（含外接评分器）、**实体导航调度**（含边走边搜 / 游荡）、**视野感知**（距离 + FOV 视野角度 + 视线遮挡）、**单实体战术行为**（找掩体 / 远离 / 包抄 / 寻找高地 / 占据视野点 / 模糊位置选取）、**团队战术**（协同包抄 / 包围 / 集结 / 阵型）与 **战斗行为**（近战攻击 / 射箭 / 投掷 / 使用物品）。

---

## 目录

- [一、模块简介](#一模块简介)
- [二、功能特性](#二功能特性)
- [三、快速开始](#三快速开始)
- [四、核心 API 一览](#四核心-api-一览)
- [五、寻路子系统](#五寻路子系统)
- [六、导航子系统](#六导航子系统)
- [七、战术子系统](#七战术子系统)
- [八、战斗子系统](#八战斗子系统)
- [九、完整示例：一只会战斗的怪物 AI](#九完整示例一只会战斗的怪物-ai)
- [十、常见问题](#十常见问题)

---

## 一、模块简介

`jframe_ai` 为 Nukkit MOT 服务端上的实体（僵尸、骷髅、自定义生物等）提供一套**开箱即用**的 AI 行为能力。它不依赖 Nukkit 内置的实体 AI（基岩版服务端实体 AI 能力有限），而是**自行实现**了一套面向方块世界的 A* 寻路与行为层，因此可以驱动任意 `cn.nukkit.entity.Entity`。

模块遵循 JFrame 的统一架构：

- **门面入口**：[`AiAPI`](AiAPI.java) —— 所有功能的统一入口，自身不含逻辑，仅做委托转发。
- **Spring 装配**：通过 [`AiSpringConfig`](config/AiSpringConfig.java) + `ai-spring.xml` 注册到全局容器。
- **自动集成**：在 `jframe_main` 中已默认启用，通过 [`JFrameMain.getAiAPI()`](../main/JFrameMain.java) 即可获取。

```
   ┌──────────────────────────── AiAPI（门面） ────────────────────────────┐
   │  寻路 findPath · 导航 navigate · 战术 findXxx · 战斗 melee/shoot        │
   │  追逐 chase · 游荡 wander · 团队 flankTarget/surround/rally            │
   ├────────────────────────────────────────────────────────────────────────┤
   │ 中层   AnytimePathFinder(追逐)  ContinuousNavigator(续算)             │
   │        WanderBehavior(游荡)  TeamTactics(团队)                        │
   ├────────────────────────────────────────────────────────────────────────┤
   │ 行为层 TacticalScanner(单实体战术)  CombatActions(战斗)                │
   ├────────────────────────────────────────────────────────────────────────┤
   │ 调度层 NavigatorManager → Navigator(实体移动驱动)                      │
   ├────────────────────────────────────────────────────────────────────────┤
   │ 核心层 PathFinder(A* 寻路，支持 StepCostFunction 外接评分器)           │
   └────────────────────────────────────────────────────────────────────────┘
```

---

## 二、功能特性

| 类别 | 能力 | 说明 |
|------|------|------|
| **寻路** | A* 启发式方块寻路 | 支持曼哈顿/欧几里得/切比雪夫启发式，对角线移动、跳跃、安全下落 |
| **寻路** | 外接评分器 | `StepCostFunction` 在基础移动代价上叠加自定义代价（如远离岩浆、贴墙走） |
| **寻路** | 模糊解（边走边搜） | 无完整路径时回退到离目标最近的部分路径（`PARTIAL` 状态） |
| **导航** | 实体路径跟随 | 自动驱动实体沿路径行走、转向、跳跃，含卡住检测 |
| **导航** | 多实体调度 | `NavigatorManager` 统一管理多个实体的导航，单调度器 tick |
| **导航** | 追逐（移动目标） | `AnytimePathFinder` 周期性重搜，支持 `Supplier<Vector3>` 动态目标 |
| **导航** | 走完续算（静态目标） | `ContinuousNavigator` 走完一段后自动续算下一段，串联贪心短段完成长距离导航 |
| **导航** | 游荡 | `WanderBehavior` 在范围内随机巡游（移动→停留→换点循环） |
| **感知** | 视野判断 | `VisionSensor` 综合距离 + FOV 视野角度 + 视线遮挡判断能否看到目标 |
| **战术** | 找掩体 | 寻找能遮挡威胁视线的位置 |
| **战术** | 远离目标 | 寻找离威胁最远的可达位置 |
| **战术** | 包抄（单实体） | 寻找位于目标侧方的位置 |
| **战术** | 寻找高地 | 寻找比当前位置更高的可站立位置 |
| **战术** | 占据视野点 | 寻找能看到目标的可站立位置（找掩体的反向操作） |
| **战术** | 模糊位置选取 | 目标位置不精确时，在不确定区域内选取搜索点 |
| **团队** | 协同包抄 | 多实体交替分配左/右翼，避免聚堆 |
| **团队** | 包围 | 多实体在目标周围 360° 均匀分布站位 |
| **团队** | 集结 | 多实体按阵型（横队/纵队/楔形/圆环/方阵）在集结点列队 |
| **战斗** | 近战攻击 | 对目标施加近战伤害 |
| **战斗** | 射箭 | 生成箭实体并射向目标（含重力补偿与散布） |
| **战斗** | 投掷抛射物 | 通用的抛射物发射（雪球、末影珍珠等） |
| **战斗** | 使用物品 | 对实体或自身使用物品 |

---

## 三、快速开始

### 1. 获取 AI 服务

在 `jframe_main` 环境下，AI 服务已自动装配并注册到 Nukkit `ServiceManager`，有两种获取方式：

```java
// 方式一：通过 JFrameMain 门面（推荐）
AiAPI ai = JFrameMain.getAiAPI();

// 方式二：通过 Nukkit ServiceManager
AiAPI ai = server.getServiceManager().get(AiAPI.class);
```

> 如果你在一个独立的插件中使用本模块（非 `jframe_main` 打包），需自行从 Spring 容器获取 `AiAPI` Bean，并调用 `ai.bindPlugin(yourPlugin)` 启动导航调度。

### 2. 三行代码让实体走向目标

```java
AiAPI ai = JFrameMain.getAiAPI();

// 让僵尸走向玩家所在位置
ai.navigateTo(zombie, player);
```

[`navigateTo`](AiAPI.java) 是「寻路 + 导航」一步到位的便捷方法。它内部先调用 [`findPath`](AiAPI.java) 计算路径，成功后调用 [`navigate`](AiAPI.java) 提交给导航器。

### 3. 检查导航状态

```java
if (ai.isNavigating(zombie)) {
    // 僵尸正在移动中
}

ai.stop(zombie);      // 停止单个实体的导航
ai.stopAll();         // 停止所有导航
```

---

## 四、核心 API 一览

[`AiAPI`](AiAPI.java) 的全部公开方法：

### 寻路

| 方法 | 说明 |
|------|------|
| `findPath(entity, target)` | 计算路径（默认参数） |
| `findPath(entity, target, options)` | 计算路径（自定义参数） |
| `partialOrFailed(entity, target, options)` | 寻路，无完整解时回退到离目标最近的部分路径（`PARTIAL`） |

> 自定义代价：通过 [`PathfinderOptions.stepCostFunction(...)`](pathfinding/PathfinderOptions.java) 注入 [`StepCostFunction`](pathfinding/StepCostFunction.java)，在基础移动代价上叠加自定义代价（如远离岩浆、贴墙行走偏好等）。返回 `POSITIVE_INFINITY` 可禁止某步通行。

### 导航

| 方法 | 说明 |
|------|------|
| `navigate(entity, result)` | 提交导航（已有路径） |
| `navigate(entity, result, speed)` | 提交导航（指定速度） |
| `navigateTo(entity, target)` | **便捷**：寻路 + 导航 |
| `navigateTo(entity, target, options, speed)` | **便捷**：寻路 + 导航（自定义） |
| `stop(entity)` | 停止指定实体导航 |
| `stopAll()` | 停止所有导航 |
| `isNavigating(entity)` | 是否正在导航 |
| `getNavigator(entity)` | 获取导航器实例 |
| `activeNavigatorCount()` | 活跃导航器数量 |
| `getCurrentPath(entity)` | 获取实体当前完整寻路路径（只读视图，`List<BlockNode>`） |
| `getRemainingPath(entity)` | 获取实体尚未走完的剩余路径（副本，可修改） |

### 追逐（移动目标 / 边走边搜）

| 方法 | 说明 |
|------|------|
| `chase(entity, targetSupplier)` | 持续追逐动态目标（默认参数） |
| `chase(entity, targetSupplier, options, speed, periodTicks)` | 追逐（自定义重搜周期与参数） |
| `isChasing(entity)` | 是否正在追逐 |
| `stopChase(entity)` | 停止追逐 |

### 走完续算（静态目标长距离导航）

| 方法 | 说明 |
|------|------|
| `navigateGreedyContinuous(entity, target)` | 走完自动续算（默认参数） |
| `navigateGreedyContinuous(entity, target, options, speed, maxSegments)` | 续算（自定义贪心参数/速度/最大段数） |
| `isNavigatingContinuous(entity)` | 是否正在续算导航 |
| `stopContinuous(entity)` | 停止续算导航 |

### 游荡

| 方法 | 说明 |
|------|------|
| `wander(entity, radius)` | 在实体周围 `radius` 范围内随机游荡（默认参数） |
| `wander(entity, radius, speed, pauseTicks)` | 游荡（自定义速度与停留时长） |
| `isWandering(entity)` | 是否正在游荡 |
| `stopWander(entity)` | 停止游荡 |

### 路径查询与可视化

| 方法 | 说明 |
|------|------|
| `getCurrentPath(entity)` | 获取当前完整寻路路径（只读视图） |
| `getRemainingPath(entity)` | 获取剩余未走完路径（副本） |
| `showPathOnce(entity)` | 用火焰粒子一次性标记当前路径所有节点 |
| `showPath(entity, durationSeconds)` | 持续显示路径 N 秒（定时刷新粒子，自动停止） |
| `showPath(entity)` | 持续显示路径（默认 10 秒） |
| `stopShowPath(entity)` | 停止持续显示 |
| `isShowingPath(entity)` | 是否正在持续显示路径 |

> **用途**：调试寻路行为、演示 AI 走位、验证路径合理性。粒子沿路径节点生成，实体移动时路径会实时刷新。持续显示需先调用 `bindPlugin` 绑定插件（调度器依赖）。

### 视野感知

| 方法 | 说明 |
|------|------|
| `canSee(observer, target)` | 视野判断（默认 16 格 / 90° FOV） |
| `canSee(observer, target, maxDistance, fovDegrees)` | 视野判断（完整参数） |
| `canSee360(observer, target, maxDistance)` | 全向视野（360°，仅距离 + 视线） |
| `angleTo(observer, target)` | 目标相对朝向的夹角（度，0=正前 / 180=正后） |

### 战术（单实体）

| 方法 | 说明 |
|------|------|
| `findCover(self, threat, radius)` | 找掩体 |
| `findFleePosition(self, threat, radius)` | 远离威胁 |
| `findFlankPosition(self, target, radius)` | 包抄（**单实体**；多实体协同包抄见下方团队战术） |
| `findHighGround(self, radius, minAdvantage)` | 寻找高地 |
| `findSightPosition(self, target, radius)` | 占据视野点（能看到目标的位置） |
| `navigateToSightPosition(self, target, radius)` | 占据视野点并导航前往（便捷方法） |
| `findApproximatePosition(self, center, radius)` | 模糊位置选取（不确定区域内最近搜索点） |
| `hasReachedApproximate(self, center, radius)` | 判断是否已到达模糊目标区域 |
| `navigateToApproximate(self, center, radius)` | 模糊位置选取并导航前往（便捷方法） |

> **坐标版重载**：`findCover` / `findFleePosition` / `findFlankPosition` / `findSightPosition` 均提供以 `Vector3` 坐标（而非 `Entity`）为威胁/目标的重载，适用于威胁不是实体（如爆炸点、固定哨位）或仅有坐标信息的场景。

### 团队战术

| 方法 | 说明 |
|------|------|
| `flankTarget(members, target, radius)` | 协同包抄：多实体交替分配左/右翼 |
| `surroundTarget(members, target, radius)` | 包围：多实体在目标周围 360° 均匀站位 |
| `rally(members, rallyPoint, formation, spacing)` | 集结：按阵型在集结点列队 |
| `formationOffsets(formation, count, spacing)` | 计算阵型偏移（纯几何，返回 `List<Vector3>`） |

### 战斗

| 方法 | 说明 |
|------|------|
| `meleeAttack(attacker, target, damage)` | 近战攻击 |
| `shootArrow(shooter, target)` | 射箭（默认） |
| `shootArrow(shooter, target, speed, inaccuracy)` | 射箭（自定义） |
| `throwProjectile(shooter, type, target, speed, inaccuracy)` | 投掷抛射物 |
| `useItemOn(user, target, item)` | 对实体使用物品 |
| `useItemOnSelf(user, item)` | 对自身使用物品 |

### 组件直接访问（高级）

| 方法 | 说明 |
|------|------|
| `getPathFinder()` | 底层寻路器 |
| `getNavigatorManager()` | 底层导航管理器 |
| `getTacticalScanner()` | 底层战术扫描器 |
| `getCombatActions()` | 底层战斗执行器 |
| `getAnytimePathFinder()` | 追逐导航器（边走边搜） |
| `getContinuousNavigator()` | 续算导航器（走完续算） |
| `getWanderBehavior()` | 游荡行为 |
| `getTeamTactics()` | 团队战术 |

---

## 五、寻路子系统

寻路采用**策略模式**（插槽架构），所有寻路算法实现统一的 [`PathfindingStrategy`](pathfinding/PathfindingStrategy.java) 接口，可在运行时插拔切换：

| 策略 | 类名 | 特点 | 适用场景 |
|------|------|------|----------|
| **A\***（默认） | [`PathFinder`](pathfinding/PathFinder.java) | 全局最优，保证必达，单次开销较大 | 精英怪、Boss 等要求必达的场景 |
| **贪心** | [`GreedyPathFinder`](pathfinding/GreedyPathFinder.java) | 局部步进，不保证最优/必达，开销极低 | 杂兵群、游荡等低开销场景 |

```java
// A* 寻路（精确，保证最优）
PathResult r1 = ai.findPath(entity, target);

// 贪心寻路（快速，开销极低）
PathResult r2 = ai.findPathGreedy(entity, target);
```

> **如何选择**：需要保证到达目标用 A\*（`findPath`）；大量实体同时寻路或只需大致方向用贪心（`findPathGreedy`）。追逐移动目标时，贪心的 `chaseGreedy` 比 A\* 的 `chase` 开销更低。

### 1. 基本用法

```java
PathResult result = ai.findPath(zombie, targetLocation);

if (result.isSuccess()) {
    List<BlockNode> path = result.getNodes();
    // path 索引 0 为起点、末尾为目标，按行进顺序排列
} else {
    // 失败原因见 result.getStatus()
    switch (result.getStatus()) {
        case NO_PATH:            // 无可达路径
        case NODE_LIMIT_EXCEEDED:// 超过最大搜索节点数
        case START_INVALID:      // 起点不可站立
    }
}
```

### 2. 自定义寻路参数

通过 [`PathfinderOptions`](pathfinding/PathfinderOptions.java) 调整寻路行为，采用 **fluent setter** 风格：

```java
PathfinderOptions options = new PathfinderOptions()
        .maxSearchNodes(3000)     // 最大搜索节点数（默认 1500）
        .allowDiagonal(true)      // 允许对角线移动（默认 true）
        .allowJump(true)          // 允许跳跃（默认 true）
        .maxDropHeight(4)         // 最大安全下落高度（默认 3）
        .entityHeight(2)          // 实体高度，用于头顶碰撞检测（默认 2）
        .heuristic(HeuristicType.EUCLIDEAN)  // 启发式（默认 MANHATTAN）
        .goalReachRadius(1.5)     // 到达目标的判定半径（默认 1.0）
        .stepCostFunction((level, from, to, diagonal, opt) -> {  // 外接评分器
            // 示例：经过岩浆旁的方块额外付出高代价
            if (isNearLava(level, to)) return 50.0;
            return 0.0;            // 不额外加价
        })
        .partialOnFailure(true);  // 无完整解时回退到部分路径（默认 false）

PathResult result = ai.findPath(zombie, target, options);
```

> **外接评分器**（[`StepCostFunction`](pathfinding/StepCostFunction.java)）：在 A\* 的基础移动代价（对角线 1.414 / 直行 1.0 / 攀爬额外）之上叠加自定义代价。返回 `POSITIVE_INFINITY` 可禁止某步通行，返回负值会被截断为 `0.01` 下限（避免负代价破坏 A\* 最优性）。典型用途：远离岩浆/仙人掌、偏好贴墙走、为已知危险区域加价。
```

### 3. 启发式类型

[`HeuristicType`](pathfinding/HeuristicType.java) 提供三种启发式函数：

| 类型 | 特点 | 适用场景 |
|------|------|----------|
| `MANHATTAN`（默认） | `|dx|+|dy|+|dz|`，计算快 | 4/8 方向网格移动，通用首选 |
| `EUCLIDEAN` | 直线距离 `√(dx²+dy²+dz²)` | 路径更贴近直线，略慢 |
| `CHEBYSHEV` | `max(|dx|,|dy|,|dz|)` | 8 方向等代价移动 |

> **建议**：绝大多数场景使用默认的 `MANHATTAN` 即可。它对网格寻路既保证可采纳性（不高估），又计算高效。

### 4. 寻路结果

[`PathResult`](pathfinding/PathResult.java) 是不可变结果对象：

- `getNodes()`：路径节点列表（`List<BlockNode>`），索引 0 为起点、末尾为目标，按行进顺序排列。
- `getStatus()`：状态枚举（`SUCCESS` / `ALREADY_AT_GOAL` / `START_INVALID` / `NODE_LIMIT_EXCEEDED` / `NO_PATH` / `PARTIAL` / `CANCELLED`）。
- `isSuccess()`：`SUCCESS` 和 `ALREADY_AT_GOAL` 均视为成功。
- `isPartial()`：是否为**模糊解**（`PARTIAL`）——未到达目标，但返回了离目标最近的已探索节点路径。
- `isCancelled()`：是否被**外部取消**（`CANCELLED`）——贪心寻路的 `cancel()` 被调用时返回已走的部分路径。
- `hasPath()`：是否携带可用路径（`SUCCESS` / `ALREADY_AT_GOAL` / `PARTIAL` / `CANCELLED` 均为 `true`）。
- `length()`：路径节点总数（含起终点）。
- `getTotalCost()`：路径总代价（g 值）。
- `getExpandedNodes()`：搜索过程中展开的节点数。
- `getDestination()`：路径终点对应的世界 `Position`。

### 5. 贪心寻路（低开销策略）

[`GreedyPathFinder`](pathfinding/GreedyPathFinder.java) 是 A\* 的轻量替代，采用**自适应三阶段**局部步进架构，不全局搜索：

| 阶段 | 触发条件 | 行为 | 开销 |
|------|----------|------|------|
| ① 快速贪心 | 默认 | 枚举 8 邻居，距离+转向评分 | O(8) 极快 |
| ② 范围感知 | 连续无进展 | 扩大窗口，舍伍德随机采样 | 中等 |
| ③ 恢复 | 找到出路 | 回到阶段① | O(8) |

```java
// 一次性贪心寻路（返回短视路径，可能 PARTIAL，走完即停）
Navigator nav = ai.navigateGreedy(entity, target);

// 贪心追逐移动目标（短视+周期重搜组合，适合杂兵群）
ai.chaseGreedy(entity, () -> targetEntity.getPosition());

// 走完自动续算（静态目标长距离导航，走完一段后自动从新位置续算下一段）
ai.navigateGreedyContinuous(entity, target);

// 外部取消正在进行的贪心追逐 / 续算导航
ai.stopChaseGreedy(entity);
ai.stopContinuous(entity);
```

> **设计哲学**：贪心是**短视**的，每次调用最多走 `maxSteps`（默认 12）步。大范围绕行由上层多次调用组合完成——走完 PARTIAL 路径后从新位置重新调用。**不退化为 A\***：步数上限/目标远离→`PARTIAL`，区域穷尽→`NO_PATH`，外部取消→`CANCELLED`。

> **走完自动续算**：[`ContinuousNavigator`](navigation/ContinuousNavigator.java) 将贪心的短段串联成长距离导航——实体走完当前段后，通过 `Navigator.onComplete` 回调立即从最新位置重新寻路计算下一段，无需等待定时器，段间衔接无缝。适用于**静态目标**的长距离行进（区别于 `AnytimePathFinder` 的移动目标追逐）。终止条件：到达目标（距离 ≤ `reachRadius`）/ 寻路失败 / 实体失效 / 段数上限（默认 100 段 ≈ 1200 步）/ 外部 `stopContinuous`。

通过 [`GreedyOptions`](pathfinding/GreedyOptions.java) 调整贪心参数：

```java
GreedyOptions opts = new GreedyOptions()
        .setWindowRadius(6)       // 阶段②观察窗口半径（默认 4）
        .setSamplesPerTick(12)    // 每步采样候选数（默认 8）
        .setMaxSteps(20)          // 单次最大步数（默认 12）
        .setScoreJitter(0.1)      // 随机扰动幅度（默认 0.05）
        .setEntityHeight(3);      // 实体高度（默认 2）
```

### 6. 可通行性判定

寻路依赖 [`BlockChecks`](util/BlockChecks.java) 判定方块是否可通行/可站立：

- `isSolid(level, x, y, z)`：方块是否为实体（不可穿过）。
- `isPassable(level, x, y, z, entityHeight)`：实体能否占据该空间（考虑高度）。
- `isStandable(level, x, y, z, entityHeight)`：实体能否站立其上（脚下有支撑，自身空间通透）。

---

## 六、导航子系统

导航负责**驱动实体沿路径移动**，由 [`NavigatorManager`](navigation/NavigatorManager.java) 统一调度，每个实体对应一个 [`Navigator`](navigation/Navigator.java)。

### 1. 工作原理

- `NavigatorManager` 实现 `PluginAware`，在插件启动时由框架自动调用 `bindPlugin`，注册一个 **周期调度任务**（默认每 tick 执行一次）。
- 每次 tick，遍历所有活跃 `Navigator`，调用其 `tick()`：
  - 计算实体到当前路径节点的方向，设置 `motionX` / `motionZ`。
  - 设置实体 `yaw` 使其面向行进方向。
  - 若前方有高出一格的方块，触发跳跃（设置 `motionY`）。
  - 检测卡住（长时间未推进），自动跳过或终止。
  - 到达终点时自动移除导航器。

### 2. 提交导航

```java
// 方式一：一步到位（推荐）
ai.navigateTo(zombie, targetLocation);

// 方式二：分步（可对 PathResult 做额外处理）
PathResult result = ai.findPath(zombie, targetLocation);
if (result.isSuccess()) {
    ai.navigate(zombie, result, 0.25);  // 速度 0.25 方块/tick
}
```

### 3. 速度说明

`speed` 参数单位为 **方块/tick**（每 tick 移动的方块数）。参考值：

| 速度 | 大致表现 |
|------|----------|
| `0.15` | 缓慢行走 |
| `0.25`（默认） | 正常行走 |
| `0.35` | 小跑 |
| `0.5` | 快速奔跑 |

> 实际表现受实体自身属性（如 `movementSpeed`）与物理（摩擦力）影响，可能需要微调。

### 4. 多实体并发

`NavigatorManager` 内部使用 `ConcurrentHashMap` 管理所有活跃导航器，线程安全。你可以同时对数十个实体发起导航，它们会在同一个调度 tick 中被统一更新。

```java
for (Entity zombie : zombieHorde) {
    ai.navigateTo(zombie, player);
}
System.out.println("活跃导航数: " + ai.activeNavigatorCount());
```

### 5. 追逐（边走边搜 / 移动目标）

[`AnytimePathFinder`](navigation/AnytimePathFinder.java) 提供「边走边搜」的追逐能力，专为**移动目标**设计：

- 目标以 `Supplier<Vector3>` 形式传入，每次重搜时动态读取最新位置。
- 周期性（默认 20 tick）重新寻路，自动修正因目标移动产生的偏差。
- **强制开启模糊解**：即使某次寻路无完整解（`NO_PATH`），也会回退到离目标最近的部分路径（`PARTIAL`），保证实体始终向目标方向推进，而非原地停滞。

```java
// 持续追逐玩家（玩家可能在移动）
ai.chase(zombie, () -> player);

// 自定义重搜周期与速度
ai.chase(zombie, () -> player, new PathfinderOptions(), 0.3, 10);

ai.isChasing(zombie);   // 是否正在追逐
ai.stopChase(zombie);   // 停止追逐
```

> **何时用 `chase` 而非 `navigateTo`**：`navigateTo` 是一次性寻路，目标移动后路径即过时；`chase` 适合目标会持续移动的场景（追击玩家、跟随主人）。

### 6. 走完自动续算（静态目标长距离导航）

[`ContinuousNavigator`](navigation/ContinuousNavigator.java) 将贪心寻路的短段串联成长距离导航，专为**静态目标**设计：

- 实体走完当前段后，通过 `Navigator.onComplete` 回调立即从最新位置重新寻路计算下一段，无需等待定时器，段间衔接无缝。
- 与 `AnytimePathFinder`（定时重搜追逐**移动**目标）互补：续算导航面向静态目标，基于「走完才续算」事件驱动；追逐面向移动目标，基于固定周期重搜。

```java
// 走完自动续算（实体走完一段后自动续算下一段，直到到达目标）
ai.navigateGreedyContinuous(entity, target);

// 自定义参数（贪心选项 + 速度 + 最大段数）
ai.navigateGreedyContinuous(entity, target, new GreedyOptions(), 0.3, 100);

ai.isNavigatingContinuous(entity);   // 是否正在续算导航
ai.stopContinuous(entity);           // 停止续算导航
```

> **终止条件**：到达目标（水平距离 ≤ `reachRadius`）/ 寻路失败（`NO_PATH`）/ 实体失效（死亡/关闭）/ 段数上限（默认 100 段 ≈ 1200 步，防止无限循环）/ 外部 `stopContinuous`。

### 7. 游荡（wander）

[`WanderBehavior`](navigation/WanderBehavior.java) 让实体在指定范围内**随机巡游**，模拟生物的自然闲逛：

- 状态机：`移动中 → 到达后停留倒计时 → 选取新随机点 → 移动中` 循环。
- 随机点采用极坐标采样：角度 ∈ [0, 2π)、距离 ∈ [radius×0.3, radius]，避免总在脚下打转。

```java
// 让村民在出生点 10 格范围内游荡
ai.wander(villager, 10);

// 自定义速度与停留时长（停留 60 tick = 3 秒）
ai.wander(villager, 10, 0.2, 60);

ai.isWandering(villager);  // 是否正在游荡
ai.stopWander(villager);   // 停止游荡
```

---

## 七、战术子系统

战术子系统由 [`TacticalScanner`](tactical/TacticalScanner.java) 实现，提供四种经典的战斗走位行为。它们都返回 [`TacticalPosition`](tactical/TacticalPosition.java)（一个 `record`），包含位置、评分、与威胁的距离。

> **坐标版重载**：以下所有战术方法（`findCover` / `findFleePosition` / `findFlankPosition` / `findSightPosition`）除接受 `Entity` 威胁外，均提供接受 `Vector3` 坐标的重载。当威胁不是实体（如爆炸点、岩浆源）或仅知坐标时，可直接传入坐标：

```java
// 威胁是一个爆炸点坐标（而非实体）
Vector3 blast = new Vector3(100, 64, 200);
TacticalPosition cover = ai.findCover(zombie, blast, 8);
if (cover.isPresent()) {
    ai.navigateTo(zombie, cover.toLevelPosition(zombie.getLevel()));
}
```

### 1. 找掩体（findCover）

寻找一个**能遮挡威胁视线**的位置——即从该位置到威胁之间存在实体方块阻挡。

```java
TacticalPosition cover = ai.findCover(zombie, player, 8);
if (cover.isPresent()) {
    ai.navigateTo(zombie, cover.toLevelPosition(zombie.getLevel()));
}
```

**评分逻辑**：优先选择视线被阻挡且距离威胁适中的位置（太近的掩体意义不大）。

### 2. 远离目标（findFleePosition）

寻找搜索范围内**离威胁最远**的可达位置。

```java
TacticalPosition flee = ai.findFleePosition(creeper, player, 12);
if (flee.isPresent()) {
    ai.navigateTo(creeper, flee.toLevelPosition(creeper.getLevel()));
}
```

**评分逻辑**：距离威胁越远，评分越高。

### 3. 包抄（findFlankPosition，单实体）

寻找位于目标**侧方**的位置，用于绕到敌人侧面/背后。

```java
TacticalPosition flank = ai.findFlankPosition(skeleton, player, 10);
if (flank.isPresent()) {
    ai.navigateTo(skeleton, flank.toLevelPosition(skeleton.getLevel()));
}
```

**评分逻辑**：位置与「自身→目标」连线的夹角越接近 90°（正侧方），评分越高；同时兼顾可达性。

### 4. 寻找高地（findHighGround）

寻找比当前位置**更高**的可站立位置，获得高度优势（利于远程攻击、扩大视野）。

```java
TacticalPosition high = ai.findHighGround(skeleton, 10, 2);
// minAdvantage=2：要求至少比当前位置高 2 格
if (high.isPresent()) {
    ai.navigateTo(skeleton, high.toLevelPosition(skeleton.getLevel()));
}
```

**评分逻辑**：高度优势（目标 Y − 自身 Y）超过 `minAdvantage` 才纳入候选，优势越大评分越高。

### 5. 占据视野点（findSightPosition）

寻找一个**能看到目标**的可站立位置——找掩体的**反向操作**。适用于弓箭手 / 哨兵需要保持视线锁定目标的场景。

```java
TacticalPosition sight = ai.findSightPosition(skeleton, player, 12);
if (sight.isPresent()) {
    ai.navigateTo(skeleton, sight.toLevelPosition(skeleton.getLevel()));
}
// 或一步到位（找位置 + 导航）：
ai.navigateToSightPosition(skeleton, player, 12);
```

**筛选条件**：候选位置眼部到目标眼部视线畅通（`LineOfSight` 判定）。

**评分逻辑**：距目标越接近理想距离（默认 10 格）评分越高（既不太近被反击，也不太远失去射程），距自身越近越好（便于快速到达）。

### 6. 模糊位置选取（findApproximatePosition）

当目标位置**不精确**（玩家未完全暴露——仅知最后已知位置 / 听到声响）时，AI 无需精确到达某个方块，只需移动到目标**大致所在区域**即可开始搜查。

```java
// 以玩家最后已知位置为圆心，5 格为不确定半径，选取最近搜索点
TacticalPosition search = ai.findApproximatePosition(zombie, lastKnownPos, 5);
if (search.isPresent()) {
    ai.navigateTo(zombie, search.toLevelPosition(zombie.getLevel()));
}
// 或一步到位（找位置 + 导航）：
ai.navigateToApproximate(zombie, lastKnownPos, 5);

// 判断是否已到达模糊区域（可用于停止导航、切换到搜查状态）
if (ai.hasReachedApproximate(zombie, lastKnownPos, 5)) {
    // 已到达大致区域，开始搜查 ...
}
```

**算法**：以 `center` 为圆心、`uncertaintyRadius` 为半径做圆形邻域遍历，筛选可站立位置，选取距自身最近者（快速到达搜索区域）。

**评分逻辑**：距自身越近评分越高（负距离，越大 = 越近 = 越好）。

### 7. TacticalPosition 说明

[`TacticalPosition`](tactical/TacticalPosition.java) 是一个不可变 `record`：

```java
public record TacticalPosition(Vector3 position, double score, double distanceToThreat) {
    public static TacticalPosition empty();
    public boolean isPresent();
    public BlockNode toBlockNode();
    public Position toLevelPosition(Level level);
}
```

- `isPresent()`：是否存在有效结果（`empty()` 返回 `false`）。
- `toLevelPosition(level)`：转换为带 `Level` 信息的 `Position`，可直接传给 `navigateTo`。

### 7. 视线检测与视野感知

战术行为（找掩体 / 占据视野点）与战斗决策依赖两层视线能力：

**几何视线**（[`LineOfSight`](util/LineOfSight.java)）：判定两点间是否存在固体方块阻挡，采用等距采样射线投射：

```java
boolean clear = LineOfSight.hasLineOfSight(level, zombieEye, playerEye);
```

**生物视野**（[`VisionSensor`](util/VisionSensor.java)）：在几何视线上叠加**距离**与**视野角度（FOV）**约束，模拟生物的真实视野：

```java
// 僵尸能否看到 16 格内、前方 90° 锥角的玩家
boolean canSee = ai.canSee(zombie, player, 16, 90);

// 全向感知（哨塔 360°，仅距离 + 视线）
boolean detected = ai.canSee360(guard, intruder, 32);

// 查询目标相对朝向的夹角（度）
double angle = ai.angleTo(zombie, player); // 0=正前, 90=正侧, 180=正后
```

FOV 计算基于 Nukkit yaw 约定（0° 朝 +Z，顺时针为正），通过朝向向量与目标方向的点积求得夹角余弦。

### 8. 团队战术（TeamTactics）

[`TeamTactics`](tactical/TeamTactics.java) 面向**一组实体**（小队/团队）的协同行为，与上方单实体战术互补：

| 方法 | 说明 |
|------|------|
| `flankTarget(members, target, radius)` | **协同包抄**：成员交替分配到目标左/右翼（偏移量递增 ±90°、±120°…），避免全部挤在同一侧 |
| `surroundTarget(members, target, radius)` | **包围**：成员在目标周围 360° 均匀分布站位 |
| `rally(members, rallyPoint, formation, spacing)` | **集结**：成员按指定阵型在集结点列队 |
| `formationOffsets(formation, count, spacing)` | 计算阵型偏移（纯几何，返回 `List<Vector3>`） |

```java
List<Entity> squad = List.of(zombie1, zombie2, zombie3);

// 协同包抄玩家
ai.flankTarget(squad, player, 12);

// 包围玩家
ai.surroundTarget(squad, player, 6);

// 在旗帜处列队（楔形阵）
ai.rally(squad, flagLocation, FormationType.WEDGE, 2.0);
```

**阵型类型**（[`FormationType`](tactical/FormationType.java)）：`LINE`（横队）、`COLUMN`（纵队）、`WEDGE`（楔形）、`CIRCLE`（圆环）、`SQUARE`（方阵）。

> **单实体包抄 vs 团队包抄**：`findFlankPosition` 为单个实体选择最佳侧翼点；`flankTarget` 在此基础上叠加协同偏移，使多个成员分散到不同侧翼，避免聚堆。

---

## 八、战斗子系统

战斗子系统由 [`CombatActions`](combat/CombatActions.java) 实现，提供四种战斗动作。

### 1. 近战攻击（meleeAttack）

对目标施加近战伤害。

```java
boolean hit = ai.meleeAttack(zombie, player, 4.0f);
// damage=4.0：造成 4 点伤害（2 颗心）
```

内部通过 `target.attack(new EntityDamageEvent(...))` 施加伤害，返回是否成功。**注意**：本方法不检查距离，调用方应自行判断是否在攻击范围内。

### 2. 射箭（shootArrow）

生成一支箭并射向目标。

```java
// 默认速度与散布
Entity arrow = ai.shootArrow(skeleton, player);

// 自定义速度与散布
Entity arrow = ai.shootArrow(skeleton, player, 1.6, 0.02);
// speed=1.6：初速度；inaccuracy=0.02：散布角度（弧度）
```

**瞄准逻辑**：[`computeAimMotion`](combat/CombatActions.java) 会根据目标距离进行**重力补偿**（箭受重力下坠），并叠加随机散布（`inaccuracy`），使射击更真实。射手会自动转向目标。

### 3. 投掷抛射物（throwProjectile）

通用的抛射物发射，支持雪球、末影珍珠、喷溅药水等任何已注册的抛射物实体。

```java
// 投掷雪球
Entity snowball = ai.throwProjectile(player, "Snowball", target, 1.5, 0.01);

// 投掷末影珍珠
Entity pearl = ai.throwProjectile(player, "ThrownEnderpearl", target, 1.5, 0.0);
```

`projectileType` 是 Nukkit 抛射物的注册名（如 `"Arrow"`、`"Snowball"`、`"ThrownEnderpearl"`）。

### 4. 使用物品（useItemOn / useItemOnSelf）

对实体或自身使用物品（如喷溅药水、食物等）。

```java
Item potion = Item.get(Item.SPLASH_POTION);  // 喷溅药水

// 对目标使用
ai.useItemOn(witch, player, potion);

// 对自身使用（如治疗）
ai.useItemOnSelf(zombie, Item.get(Item.APPLE));
```

---

## 九、完整示例：一只会战斗的怪物 AI

下面演示如何组合寻路、战术、战斗，实现一个具备简单决策的怪物 AI。这是一个**行为循环**的典型写法——你可以在自己的调度任务或实体 `onUpdate` 中调用。

```java
public class MonsterBrain {

    private final AiAPI ai = JFrameMain.getAiAPI();
    private final Entity self;        // 怪物自身
    private int tickCounter = 0;

    public MonsterBrain(Entity self) {
        this.self = self;
    }

    /** 每个 tick 调用 */
    public void tick(Entity target) {
        tickCounter++;
        if (target == null || target.isClosed()) {
            return;
        }

        double distance = self.distance(target);

        // ---- 决策 ----
        if (distance > 20) {
            // 太远：直接追击
            if (!ai.isNavigating(self)) {
                ai.navigateTo(self, target);
            }
        } else if (distance > 8) {
            // 中距离：寻找高地，远程射箭
            if (tickCounter % 40 == 0) {  // 每 2 秒射一箭
                ai.shootArrow(self, target);
            }
            if (!ai.isNavigating(self)) {
                TacticalPosition high = ai.findHighGround(self, 8, 2);
                if (high.isPresent()) {
                    ai.navigateTo(self, high.toLevelPosition(self.getLevel()));
                }
            }
        } else if (distance <= 3) {
            // 近距离：近战攻击
            ai.stop(self);
            ai.meleeAttack(self, target, 5.0f);
        } else {
            // 较近但未到攻击范围：包抄接近
            if (!ai.isNavigating(self)) {
                TacticalPosition flank = ai.findFlankPosition(self, target, 6);
                if (flank.isPresent()) {
                    ai.navigateTo(self, flank.toLevelPosition(self.getLevel()));
                } else {
                    ai.navigateTo(self, target);
                }
            }
        }

        // ---- 低血量时找掩体撤退（示例：假设有 getHealth()）----
        // if (self.getHealth() < 5) {
        //     TacticalPosition cover = ai.findCover(self, target, 10);
        //     if (cover.isPresent()) {
        //         ai.navigateTo(self, cover.toLevelPosition(self.getLevel()));
        //     }
        // }
    }
}
```

> **提示**：本模块提供的是**原子行为**（寻路、攻击、找掩体……），不包含完整的行为树/状态机框架。上例中的 `if/else` 决策即是「状态机」的简化形态。你可以根据需要引入更复杂的行为树（Behavior Tree）或有限状态机（FSM）来组织这些原子行为。

---

## 十、常见问题

### Q1：实体不动 / 寻路返回 `NO_PATH`？

- 确认起点和目标在同一 `Level`。
- 确认起点方块可站立（脚下有实体方块）。可用 `BlockChecks.isStandable(...)` 验证。
- 目标可能被完全围住或悬空。尝试调大 `maxSearchNodes` 或 `goalReachRadius`。

### Q2：实体卡在墙角？

- 导航器内置卡住检测，会尝试跳过当前节点。若频繁卡住，可调小 `speed` 或检查地形是否有「半格高」障碍（栅栏等）——当前寻路按整格处理。

### Q3：箭总是打不中？

- `shootArrow` 已做重力补偿，但目标若高速移动仍可能 miss。可调大 `inaccuracy`（散布）反而更难命中——应**调小** `inaccuracy`（如 `0.005`）以提高精度，或缩短交战距离。

### Q4：如何让导航更快/更慢？

- 调整 `navigate(entity, result, speed)` 的 `speed` 参数（方块/tick）。
- 导航调度频率固定为每 tick 一次（与游戏帧同步），无法更改。

### Q5：可以在非 `jframe_main` 的独立插件中使用吗？

- 可以。将 `jframe_ai` 作为依赖引入，从 Spring 容器获取 `AiAPI` Bean，并**务必**调用 `ai.bindPlugin(yourPlugin)` 启动导航调度任务，否则 `navigateTo` 不会驱动实体移动。

### Q6：战术搜索范围（radius）设多大合适？

- 取决于地形密度。一般 `8~12` 较合适。过大会增加扫描开销（立方体遍历），过小可能找不到合适位置。

---

## 相关文档

- 开发者文档（架构设计、A* 算法细节、扩展指南）：见 [`DEVELOPER.md`](DEVELOPER.md)
- JFrame 主框架：见根目录 [`README.md`](../../../README.md)
