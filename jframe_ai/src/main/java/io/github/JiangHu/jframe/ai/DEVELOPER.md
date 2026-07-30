# jframe_ai 开发者文档

> 面向**贡献者与高级使用者**的技术文档。涵盖架构设计、核心算法实现细节、扩展指南与性能考量。
> 面向使用者的快速上手请参阅 [`README.md`](README.md)。

---

## 目录

- [一、架构总览](#一架构总览)
- [二、包结构](#二包结构)
- [三、寻路引擎详解（A\*）](#三寻路引擎详解a)
- [四、导航调度详解](#四导航调度详解)
- [五、战术评分算法](#五战术评分算法)
- [六、战斗瞄准算法](#六战斗瞄准算法)
- [七、工具类](#七工具类)
- [八、Spring 装配机制](#八spring-装配机制)
- [九、扩展指南](#九扩展指南)
- [十、性能考量](#十性能考量)
- [十一、测试](#十一测试)

---

## 一、架构总览

模块采用**分层 + 单向 DAG**设计，自底向上分为五层：

```
┌─────────────────────────────────────────────────────────────┐
│  门面层    AiAPI（聚合转发，不含逻辑）                       │
├─────────────────────────────────────────────────────────────┤
│  中层      AnytimePathFinder(追逐) WanderBehavior(游荡)      │
│            TeamTactics(团队战术)                             │
├─────────────────────────────────────────────────────────────┤
│  行为层    TacticalScanner（单实体战术）  CombatActions（战斗）│
├─────────────────────────────────────────────────────────────┤
│  调度层    NavigatorManager → Navigator（实体移动驱动）      │
├─────────────────────────────────────────────────────────────┤
│  核心层    PathFinder（A* 寻路，支持 StepCostFunction 评分器）│
├─────────────────────────────────────────────────────────────┤
│  工具层    BlockChecks（可通行性）  LineOfSight（视线）  VisionSensor（视野） │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向**：上层依赖下层。中层三个组件（追逐/游荡/团队）依赖核心层与调度层，再被门面聚合。具体到 Bean 的 DAG：

```
PathFinder ──┬─────────────────────────────┐
NavigatorManager ──┼──→ AnytimePathFinder ──┤
                   ├──→ WanderBehavior ─────┼──→ AiAPI
TacticalScanner ──┼─────────────────────────┤
CombatActions ────┼─────────────────────────┤
TeamTactics ──────┘                         │
```

七个组件均可单独使用；[`AiAPI`](AiAPI.java) 仅做构造注入与委托转发。Spring 按「核心 → 调度 → 行为/中层 → 门面」的顺序创建 Bean（XML 中按此顺序声明）。

### 设计原则

1. **无状态优先**：`PathFinder`、`TacticalScanner`、`CombatActions` 均为无状态纯计算，可作 Spring 单例被多线程并发调用。有状态部分（`Navigator`）被封装在 `NavigatorManager` 的并发容器内。
2. **宽容失败**：寻路、战术扫描永不抛异常，统一返回带状态码的结果对象（[`PathResult`](pathfinding/PathResult.java) / [`TacticalPosition`](tactical/TacticalPosition.java)），调用方自行决策回退。
3. **熔断保护**：A* 搜索有节点上限（`maxSearchNodes`），避免在无解地形上耗尽 CPU。

---

## 二、包结构

```
io.github.JiangHu.jframe.ai
├── AiAPI.java                  门面入口（公开 API）
├── config/
│   └── AiSpringConfig.java     Spring 配置（@ImportResource）
├── pathfinding/                寻路核心
│   ├── BlockNode.java          A* 节点
│   ├── HeuristicType.java      启发式枚举
│   ├── PathfinderOptions.java  寻路参数（fluent setter）
│   ├── PathResult.java         寻路结果（不可变，含 PARTIAL 模糊解）
│   ├── StepCostFunction.java   外接评分器（函数式接口）
│   └── PathFinder.java         A* 算法实现
├── navigation/                 导航调度
│   ├── Navigator.java          单实体路径跟随
│   ├── NavigatorManager.java   多实体调度（PluginAware）
│   ├── AnytimePathFinder.java  追逐导航（边走边搜，移动目标）
│   ├── ContinuousNavigator.java 走完续算（静态目标长距离导航）
│   └── WanderBehavior.java     游荡行为（随机巡游状态机）
├── tactical/                   战术行为
│   ├── TacticalPosition.java   战术位置结果（record）
│   ├── TacticalScanner.java    单实体战术（找掩体/远离/包抄/高地/视野点/模糊位置）
│   ├── FormationType.java      团队阵型枚举（横/纵/楔/环/方）
│   └── TeamTactics.java        团队战术（协同包抄/包围/集结）
├── combat/                     战斗行为
│   └── CombatActions.java      攻击/射箭/投掷/使用物品
└── util/                       工具
    ├── BlockChecks.java        方块可通行性/可站立判定
    ├── LineOfSight.java        射线投射视线检测
    └── VisionSensor.java       视野感知（距离+FOV+视线遮挡）
```

---

## 三、寻路引擎详解（A*）

寻路由 [`PathFinder`](pathfinding/PathFinder.java) 实现，采用经典 **A\*** 算法。核心逻辑封装在私有内部类 `Search` 中，保证外层 `PathFinder` 无状态。

### 1. 数据结构

| 结构 | 类型 | 作用 |
|------|------|------|
| `open` | `PriorityQueue<BlockNode>` | 待探索节点，按 `fCost = g + h` 升序 |
| `closed` | `HashSet<BlockNode>` | 已确定最优的节点（闭合表） |
| `dirs` | `int[][]` | 邻居方向偏移（`DIRS_4` 或 `DIRS_8`） |
| `expanded` | `int` | 已展开节点计数（用于熔断） |

### 2. 主循环（`Search.run`）

```
1. 初始化：start.gCost = 0; start.hCost = heuristic(start, goal); open.add(start)
2. reachSq = goalReachRadius²   // 到达判定的平方半径
3. while open 非空:
     a. current = open.poll()
     b. 惰性删除：if (!closed.add(current)) continue   // 已闭合的过时条目跳过
     c. 到达判定：if (current == goal || 水平距离² ≤ reachSq) → 回溯重建路径，返回 SUCCESS
     d. 熔断：if (++expanded > maxSearchNodes) → 返回 NODE_LIMIT_EXCEEDED
     e. 扩展邻居：for each dir in dirs:
          - neighbor = findLanding(current, dir)
          - if neighbor == null || closed.contains(neighbor) → 跳过
          - 切角检测（仅对角线）：if isCornerBlocked → 跳过
          - 计算 stepCost（对角线 = diagonalCost；攀爬 dy>0 额外 +dy×0.5）
          - neighbor.gCost = current.gCost + stepCost
          - neighbor.hCost = heuristic(neighbor, goal)
          - neighbor.cameFrom = current
          - open.add(neighbor)
4. open 耗尽未到达 → 返回 NO_PATH
```

### 3. 惰性删除（Lazy Deletion）

本实现**不**在发现更优 g 值时去 `open` 中删除旧条目（PriorityQueue 不支持高效删除）。而是允许同一坐标多次入队，在 `poll` 时通过 `closed.add(current)` 判定：

- `closed.add()` 返回 `false` 表示该坐标已在闭合表中（已有更优解被处理），直接 `continue` 跳过。
- 由于启发式可采纳（不高估），**首次从 open 弹出的节点必然是最优的**，后续重复条目可安全丢弃。

这避免了维护「坐标→open 节点」索引的开销，实现简洁。

### 4. 邻居生成（`findLanding`）

对当前节点沿 `(dx, dz)` 方向寻找落脚点，按优先级尝试三种移动方式：

```
1. 同高度平移：if isWalkable(nx, cur.y, nz) → 落脚 (nx, cur.y, nz)
2. 向上跳跃（allowJump 且前方脚部为固体）：
     jy = cur.y + 1
     if 起跳点头部可穿过(cur.x, jy, cur.z) && 落点可站立(nx, jy, nz) → 落脚 (nx, jy, nz)
3. 安全下落（逐层下探，最多 maxDropHeight 格）：
     for drop = 1..maxDropHeight:
         ly = cur.y - drop
         if 该层被固体阻挡 → break（无法继续下落）
         if 该层可站立(nx, ly, nz) → 落脚 (nx, ly, nz)
```

### 5. 切角检测（`isCornerBlocked`）

对角线移动 `(dx, dz)` 时，若两个正交相邻格均为固体，实体无法穿过它们的夹角：

```
isCornerBlocked(cur, dx, dz):
    return isSolid(cur.x+dx, cur.y, cur.z) && isSolid(cur.x, cur.y, cur.z+dz)
```

这防止实体「穿墙角」——例如从 `(0,0)` 对角走到 `(1,1)`，若 `(1,0)` 和 `(0,1)` 都是墙，则禁止该对角移动。

### 6. 可通行性判定（`isWalkable` / `isPassable` / `isSolid`）

```
isWalkable(x, y, z):           // 该格是否为有效落脚点（脚部位置）
    边界检查 (MIN_Y=-64 .. MAX_Y=320)
    脚部可穿过: isPassable(x, y, z)
    头部空间: for h = 1..entityHeight-1: isPassable(x, y+h, z)
    脚下支撑: isSolid(x, y-1, z)

isPassable(x, y, z):  return block.canPassThrough()   // 空气、水、草等
isSolid(x, y, z):     return !block.canPassThrough()  // 石头、木头等
```

> 判定基于 Nukkit `Block.canPassThrough()`，与原版物理一致。

### 7. 启发式函数

[`HeuristicType`](pathfinding/HeuristicType.java) 提供三种：

| 类型 | 公式 | 说明 |
|------|------|------|
| `MANHATTAN` | `|dx|+|dy|+|dz|` | 默认；网格移动可采纳 |
| `EUCLIDEAN` | `√(dx²+dy²+dz²)` | 直线距离 |
| `CHEBYSHEV` | `max(|dx|,|dy|,|dz|)` | 8 方向等代价 |

> **可采纳性**：曼哈顿距离在 4/8 方向网格移动中不高估实际代价（对角线代价 ≥ 1），保证 A* 找到最优解。若使用对角线移动却选 `EUCLIDEAN`，理论上仍可采纳（直线 ≤ 实际网格路径），但搜索效率略低。

### 8. 路径重建（`reconstruct`）

到达终点后，从终点沿 `cameFrom` 指针回溯至起点，再 `Collections.reverse()` 反转为行进顺序。结果存入 [`PathResult`](pathfinding/PathResult.java)，**索引 0 为起点、末尾为终点**。

### 9. 外接评分器（`StepCostFunction`）

[`StepCostFunction`](pathfinding/StepCostFunction.java) 是一个函数式接口，允许调用方在 A\* 的**基础移动代价**之上叠加自定义代价：

```
stepCost = baseCost + max(0.01, stepCostFunction.extraCost(level, from, to, diagonal, options))
```

- `baseCost`：对角线 1.414、直行 1.0、攀爬额外 `+dy×0.5`（与未接入评分器时一致）。
- `extraCost` 返回 `POSITIVE_INFINITY` → 该步被禁止通行（等效于不可通行）。
- 返回负值会被截断为 `0.01` 下限——**避免负代价破坏 A\* 的最优性**（A\* 要求每步代价 ≥ ε > 0，否则可能出现零代价环导致无法终止或非最优解）。

通过 [`PathfinderOptions.stepCostFunction(...)`](pathfinding/PathfinderOptions.java) 注入。典型用途：远离岩浆/仙人掌、偏好贴墙、为已知危险区域加价。

### 10. 模糊解（`PARTIAL` / `partialOrFailed`）

当目标不可达（被围住/悬空/超出搜索上限）时，标准 `findPath` 返回 `NO_PATH`。但许多场景下「向目标方向尽量推进」比「原地不动」更有价值（如追逐移动目标）。为此提供模糊解回退：

- [`PathFinder.partialOrFailed`](pathfinding/PathFinder.java)：在 A\* 主循环中追踪**离目标 h 值最小**的已闭合节点（`bestNode`）。若最终未到达目标，则从 `bestNode` 回溯一条部分路径，返回状态为 `PARTIAL` 的 [`PathResult`](pathfinding/PathResult.java)。
- [`PathfinderOptions.partialOnFailure(true)`](pathfinding/PathfinderOptions.java)：开启后，`findPath` 内部改用 `partialOrFailed` 语义。
- [`NavigatorManager.navigateOrPartial`](navigation/NavigatorManager.java)：接受 `SUCCESS` 与 `PARTIAL` 两种结果提交导航（`NO_PATH` 仍不导航）。

> **注意**：模糊解不保证到达目标，仅保证「沿最优方向推进」。调用方可通过 `result.isPartial()` 判断，决定是否继续重试或放弃。

### 11. 策略插槽架构（`PathfindingStrategy`）

寻路算法采用**策略模式**，所有算法实现统一的 [`PathfindingStrategy`](pathfinding/PathfindingStrategy.java) 接口：

```
PathfindingStrategy（接口/插槽）
├── PathFinder          — A* 全局最优（getName="astar"）
└── GreedyPathFinder    — 贪心局部步进（getName="greedy"）
```

配置体系同样有继承层次：

```
PathfindingConfig（抽象基类：entityHeight/entityWidth）
├── PathfinderOptions   — A* 专属（maxSearchNodes/allowDiagonal/...）
└── GreedyOptions       — 贪心专属（windowRadius/samplesPerTick/...）
```

- `findPath(Level, Vector3, Vector3, PathfindingConfig)`：接口方法，接受基类配置类型。实现内部做 `instanceof` 检查，类型不匹配时降级为默认配置（不抛异常）。
- `getDefaultConfig()`：返回该策略的默认配置新实例。
- **向后兼容**：`PathfinderOptions extends PathfindingConfig`、`PathFinder implements PathfindingStrategy`，现有代码无需修改。

---

## 三-B、贪心寻路引擎详解

[`GreedyPathFinder`](pathfinding/GreedyPathFinder.java) 是 A\* 的轻量替代。核心思想：**不全局搜索，而是递推选择下一步**。单次 `findPath` 调用最多走 `maxSteps` 步，返回 PARTIAL 路径交给 Navigator 走完后由上层重新调用。

### 1. 自适应三阶段架构

```
while (step < maxSteps):
    if stallCount1 < stallThreshold1:
        阶段①：fastGreedyStep（8邻居，距离+转向评分）
        if 有进展: stallCount1 = 0
        elif 无邻居: stallCount1++
    else:
        阶段②：rangeAwareStep（窗口采样，全因子评分+随机扰动）
        if 找到出路: stallCount1 = 0; 回到阶段①
```

| 阶段 | 方法 | 评分因子 | 开销 |
|------|------|----------|------|
| ① 快速贪心 | `fastGreedyStep` | 距离 + 转向 | O(8) |
| ② 范围感知 | `rangeAwareStep` | 距离 + 转向 + 访问惩罚 - 边界接近度 | 中等 |
| ③ 恢复 | 回到① | — | O(8) |

### 2. 舍伍德随机采样（`SamplingState`）

阶段②在窗口内**随机采样**候选位置，直到收集到 `samplesPerTick` 个**可站立**候选（不可站立的不计入 k）。三重终止条件：

1. **最优值稳定**：连续 `noImproveLimit` 次未发现更优候选
2. **方差收敛**：最近 `varianceWindow` 次采样的分数方差 < `varianceFloor`
3. **预算上限**：窗口内所有列已采样完

采样完成后，对每个候选施加**随机扰动**（舍伍德随机化）：`finalScore = rawScore × (1 + ε)`，ε ∈ [-scoreJitter, +scoreJitter]，打破确定性，避免被特定地形卡住。

### 3. 短期目标机制（`SubGoal`）

当评分最高的候选经迷你 A\* 验证**不可达**、但次优候选可达时：
- 将最优候选设为**短期目标**（跳板），吸引力权重 `subGoalWeight = 1.0`
- 有效目标 = `lerp(最终目标, 短期目标, subGoalWeight)`（线性插值）
- 每步 `subGoalWeight *= subGoalDecay`（默认 0.9），衰减到 `subGoalThreshold`（默认 0.1）以下时**自然淘汰**

### 4. 时间衰减访问惩罚（`VisitHistory`）

滑动窗口记录最近 `visitHistorySize`（默认 20）步的位置。惩罚函数：

```
penalty = Σ weight_i × decay_i
weight_i = 1 / (1 + dist_i)      // 距离越近权重越大
decay_i  = e^(-λ × age_i)        // 年龄越大衰减越快（λ = visitDecayLambda，默认 0.2）
```

近期位置惩罚大（防振荡），远期遗忘（允许回退）。

### 5. 熔断条件（不退化为 A\*）

| 条件 | 返回状态 | 说明 |
|------|----------|------|
| 步数上限 `step ≥ maxSteps` | `PARTIAL` | 短视路径，交给上层重新调用 |
| 目标远离 `currentDist - startDist > maxDrift` | `PARTIAL` | 偏离太远，交给上层重新决策 |
| 区域穷尽（阶段②采样完仍无出路） | `NO_PATH` | 窗口内确实无路 |
| 外部取消 `cancel()` | `CANCELLED` | volatile 标志，返回已走的部分路径 |

> **关键设计**：熔断**不退化为 A\***。贪心永远不调用全局 A\* 作为后备——大范围绕行由上层多次 PARTIAL 调用组合完成。

### 6. 步数刷新机制

当离目标的距离比上次记录近 `refreshThreshold`（默认 2）方块时，步数计数回退 `refreshAmount`（默认 3），延长寻路预算。这允许在持续进展时走更远，在停滞时及时熔断。

### 7. 外部取消

`GreedyPathFinder.cancel()` 设置 `volatile boolean cancelled` 标志。正在执行的 `findPath` 在下一步循环检查时返回 `CANCELLED`（附带已走的部分路径）。`volatile` 保证跨线程可见性，支持从其他线程终止寻路。

### 8. 无状态设计

`GreedyPathFinder` 外层无实例状态（`cancelled` 为 volatile 可安全重置），可作单例。单次寻路的可变状态封装在 `GreedySearch` 内部类中，每次 `findPath` 创建新实例。

---

## 四、导航调度详解

### 1. Navigator 单实体跟随

[`Navigator`](navigation/Navigator.java) 是**有状态、单次使用**的控制器，绑定一个实体与一条路径。关键常量：

| 常量 | 值 | 含义 |
|------|----|------|
| `ARRIVAL_RADIUS` | 0.6 | 到达单路径点的判定半径 |
| `DEFAULT_SPEED` | 0.25 | 默认速度（方块/tick） |
| `DEFAULT_JUMP_FORCE` | 0.42 | 跳跃冲量（motionY） |
| `STUCK_THRESHOLD` | 12 | 卡住判定 tick 数 |
| `STUCK_MOVE_DELTA` | 0.02 | 卡住判定位移阈值 |

#### `tick()` 流程

```
1. 终止检查：finished / entity 失效(closed/!alive) / index >= path.size() → finish()
2. target = path[index]   // 当前目标路径点
3. dx = (target.x + 0.5) - entity.x;  dz = (target.z + 0.5) - entity.z   // 指向方块中心
4. 到达判定：if 水平距离 < ARRIVAL_RADIUS → index++; stuckTicks=0; return
5. 设置水平速度：归一化 (dx,dz) × speed → entity.motionX / motionZ
6. 朝向：entity.yaw = atan2(-dx, dz)   // Nukkit yaw：0 朝 +Z，顺时针为正
7. 跳跃：if target.y > entity.floorY && entity.motionY <= 0.05 → entity.motionY = jumpForce
8. 卡住检测：if 本 tick 位移 < STUCK_MOVE_DELTA → stuckTicks++；若 > STUCK_THRESHOLD → index++（跳过卡住的点）
```

#### 移动模型说明

采用「**直接设置速度向量**」的方式，而非寻路到目标点后传送。每 tick 写入 `motionX/motionZ`，由实体自身的物理更新（`onUpdate`）处理重力、摩擦、碰撞。这种方式：

- 兼容大多数 Nukkit 生物实体与自定义实体。
- 对由客户端控制的 `cn.nukkit.Player` **无效**（玩家位置由客户端决定）。

#### 路径暴露（`getPath` / `getRemainingPath`）

`Navigator` 持有当前路径与游标索引（`index`），通过两个方法对外暴露，供调试与可视化使用：

- [`getPath()`](navigation/Navigator.java)：返回**完整路径的只读视图**（`Collections.unmodifiableList`），调用方不可修改内部状态。
- [`getRemainingPath()`](navigation/Navigator.java)：返回**尚未走完的剩余路径副本**（`path.subList(index, size)` 的 `ArrayList` 拷贝），调用方可自由修改。

> 两者均不抛异常：导航结束或索引越界时返回空列表。`AiAPI.getCurrentPath` / `getRemainingPath` 是对它们的门面封装（实体无活跃导航器时返回空列表）。

### 2. NavigatorManager 多实体调度

[`NavigatorManager`](navigation/NavigatorManager.java) 管理所有活跃 `Navigator`：

- 内部用 `ConcurrentHashMap<Long, Navigator>`（key 为实体 `getId()`）。
- 实现 [`PluginAware`](../core/module/PluginAware.java)，插件启动时由框架自动调用 `bindPlugin(plugin)`。
- `bindPlugin` 通过 `Server.getScheduler().scheduleRepeatingTask(plugin, this::tickAll, 1)` 注册**每 tick 一次**的调度任务。
- `tickAll()` 遍历所有 Navigator 调用 `tick()`，返回 `false`（已结束）的从 Map 移除。

#### 并发安全

- Map 使用 `ConcurrentHashMap`，`navigate` / `stop` / `tickAll` 可在不同线程调用。
- `tickAll` 使用迭代器安全移除（`Iterator.remove` 或先收集再删）。

### 3. AnytimePathFinder 追逐（边走边搜）

[`AnytimePathFinder`](navigation/AnytimePathFinder.java) 是面向**移动目标**的持续追逐导航器，核心方法 `chase(entity, targetSupplier, options, speed, periodTicks)`：

```
1. 注册一个周期任务（periodTicks，默认 20 tick）到 Nukkit 调度器
2. 每次 tick：
   a. target = targetSupplier.get()      // 动态读取最新目标位置
   b. if entity 与 target 距离 ≤ GIVE_UP_RADIUS(1.5) → 视为追上，停止追逐
   c. result = pathFinder.partialOrFailed(entity, target, options)  // 强制 partialOnFailure
   d. navigatorManager.navigateOrPartial(entity, result, speed)     // 接受 SUCCESS/PARTIAL
3. stopChase(entity) 取消任务并停止导航
```

**关键设计**：

- **目标供应器**（`Supplier<Vector3>`）：目标位置延迟求值，每次重搜时读取最新值，天然支持移动目标。
- **强制模糊解**：`chase` 内部强制 `options.partialOnFailure = true`，即使某次寻路 `NO_PATH`，也会回退到部分路径，保证实体持续向目标推进。
- **周期重搜**：固定周期重新寻路，修正目标移动产生的路径偏差（无需等当前路径走完）。

### 4. ContinuousNavigator 走完续算（静态目标）

[`ContinuousNavigator`](navigation/ContinuousNavigator.java) 将贪心寻路的短段串联成长距离导航，面向**静态目标**。与 `AnytimePathFinder`（定时重搜追逐移动目标）互补，核心方法 `navigateTo(entity, target, options, speed, maxSegments)`：

```
1. navigateTo(entity, target) 触发首次寻路
2. navigateNextSegment()：
   a. result = strategy.findPath(level, entity, target, options)   // 从实体当前位置寻路
   b. if !result.hasPath() → cleanup()，停止续算
   c. nav = navigatorManager.navigateOrPartial(entity, result, speed)
   d. nav.onComplete(n -> {                                          // 走完回调
        if 距离 ≤ reachRadius → cleanup()，已到达
        else → navigateNextSegment()                                 // 递归续算下一段
      })
3. stop(entity) → task.cancel() + navigatorManager.stop(entity)
```

**关键设计**：

- **事件驱动续算**：通过 `Navigator.onComplete` 回调驱动——实体走完当前段后立即从最新位置重新寻路，无需定时器，段间衔接无缝（贪心计算微秒级，间隙不可感知）。
- **基于最新位置**：每次续算从实体当前实际位置出发，修正 Navigator 行进中的微小偏差，比预计算整条路径更准确。
- **段数上限**：`DEFAULT_MAX_SEGMENTS`（100 段 ≈ 1200 步）防止无限循环；到达目标（距离 ≤ `reachRadius`）/ 寻路失败 / 实体失效时自动停止。
- **线程安全**：任务表使用 `ConcurrentHashMap`，`cancelled` 标志使用 `volatile`，续算回调由 `NavigatorManager` 主线程调度执行。

> **与 AnytimePathFinder 的区别**：`AnytimePathFinder` 面向**移动目标**，基于固定周期重搜（目标可能在走的过程中移动）；`ContinuousNavigator` 面向**静态目标**，基于「走完才续算」事件驱动（目标不动，无需周期重搜，开销更低）。

### 5. WanderBehavior 游荡

[`WanderBehavior`](navigation/WanderBehavior.java) 让实体在范围内随机巡游，核心方法 `wander(entity, radius, speed, pauseTicks)`。内部状态机：

```
状态 MOVING（导航中）：
  - if navigatorManager.isNavigating(entity) == false → 进入 PAUSING，设 pauseCounter = pauseTicks
状态 PAUSING（停留）：
  - pauseCounter--
  - if pauseCounter ≤ 0 → 选取新随机点，navigateTo，进入 MOVING
```

**随机点选取**（极坐标采样）：

```
angle = random × 2π                      // 角度 ∈ [0, 2π)
dist   = radius×0.3 + random×radius×0.7  // 距离 ∈ [radius×0.3, radius]
target = (entity.x + cos(angle)×dist, entity.y, entity.z + sin(angle)×dist)
```

距离下限 `radius×0.3` 避免新点总落在脚下（导致无意义的原地寻路）。

---

## 五、战术评分算法

[`TacticalScanner`](tactical/TacticalScanner.java) 的四个方法共享相同的扫描骨架：**以实体为中心的立方体邻域遍历**（`-r..r` 的 dx/dz 双重循环），对每个候选列调用 `findStandableY` 确定可站立 Y，再按各自评分函数打分，保留最高分。

> **坐标版重载**：`findCover` / `findFleePosition` / `findFlankPosition` / `findSightPosition` 的 `Entity` 威胁版本均为单行委托，转发到对应的 `Vector3` 坐标版本（`(Vector3) threat` 向下转型——Nukkit `Entity` 继承自 `Vector3`）。坐标版本是核心实现，威胁眼部位置由 `eyeOf(Vector3)` 计算（`pos + (0.5, EYE_HEIGHT, 0.5)`）。这使战术方法可用于非实体威胁（爆炸点、岩浆源、固定坐标）。

### 公共常量

| 常量 | 值 | 含义 |
|------|----|------|
| `DEFAULT_RADIUS` | 8.0 | 默认搜索半径 |
| `DEFAULT_ENTITY_HEIGHT` | 2 | 实体高度 |
| `EYE_HEIGHT` | 1.5 | 眼部相对脚部高度 |

### `findStandableY`

在 `(bx, bz)` 列上，以 `centerY` 为基准寻找可站立 Y：同高 → 向上 4 格 → 向下 4 格。返回 `Integer.MIN_VALUE` 表示该列无可站立位置。

### 1. 找掩体（`findCover`）

**目标**：寻找威胁看不到的位置。

```
候选条件：LineOfSight.hasLineOfSight(threatEye, candidateEye) == false  // 视线被阻挡（必须）
评分：score = distToThreat - distToSelf × 0.5
      （离威胁越远越好，离自身越近越好——便于快速到达）
```

### 2. 远离（`findFleePosition`）

**目标**：寻找离威胁最远的位置。

```
评分：score = distToThreat   （越远越好，直接以距离为分）
```

### 3. 包抄（`findFlankPosition`）

**目标**：寻找位于目标侧方的位置。

```
前向单位向量 f = normalize(target - self)       // 自身→目标方向
idealDist = max(2.0, |target-self| × 0.5)        // 理想包抄距离（约当前距离一半）

对每个候选：
  c = normalize(candidate - self)                // 候选相对自身的方向
  dot = c · f                                    // 1=正前, 0=正侧, -1=正后
  perpendicularity = 1 - |dot|                   // 侧方程度（0=前后, 1=正侧）
  distFit = 1 - min(1, |distToTarget - idealDist| / idealDist)   // 距离适配度
  score = perpendicularity × 0.7 + distFit × 0.3  // 侧方为主，距离为辅
```

### 4. 寻找高地（`findHighGround`）

**目标**：寻找比当前位置更高的可站立位置。

```
候选条件：advantage = candidateY - selfY ≥ minAdvantage   （高度优势达标）
评分：score = advantage - distToSelf × 0.2
       （高度优势越大越好，离自身越近越好）
```

### 5. 占据视野点（`findSightPosition`）

**目标**：寻找一个能看到目标且距离合适的可站立位置——`findCover` 的反向操作（前者要"看不到威胁"，本方法要"能看到目标"）。

```
候选条件：LineOfSight.hasLineOfSight(candidateEye, targetEye)   （候选点能看到目标）
评分：distFit = 1 - |distToTarget - idealDistance| / idealDistance
       score = distFit × 10.0 - distToSelf × 0.3
       （距目标越接近理想距离越好，离自身越近越好）
```

默认理想距离 10 格（`DEFAULT_IDEAL_SIGHT_DISTANCE`）。常用于弓箭手/哨兵"抢占射击位"。

### 6. 模糊位置选取（`findApproximatePosition`）

**目标**：当目标位置**不精确**（玩家未完全暴露——仅知最后已知位置 / 听到声响）时，在不确定区域内选取一个可站立的搜索点，使 AI 到达目标大致所在区域即可开始搜查。

```
候选条件：圆形邻域（offX² + offZ² ≤ radius²）内可站立位置
评分：score = -distToSelf   （负距离，越大 = 越近 = 越好）
```

与其它战术不同，本方法以**外部传入的 center** 为扫描圆心（而非实体自身位置），扫描范围是"目标可能所在的不确定区域"。默认半径 5 格（`DEFAULT_UNCERTAINTY_RADIUS`）。

配套方法 [`hasReachedApproximate`](tactical/TacticalScanner.java) 判断实体是否已进入模糊目标区域（水平距离 ≤ 半径），可用于停止导航、切换到搜查状态。其纯坐标重载（包级可见）便于单元测试，不依赖 Level/Entity。

### 7. 团队战术（`TeamTactics`）

[`TeamTactics`](tactical/TeamTactics.java) 面向**一组实体**的协同行为，与单实体 `TacticalScanner` 互补。核心思路：先以单实体战术（如 `findFlankPosition`）确定基准方向，再为每个成员叠加**协同偏移**，使团队分散而非聚堆。

#### 协同包抄（`flankTarget`）

```
对第 i 个成员（i 从 0 起）：
  side = (i 偶数) ? +1 : -1                 // 交替左右翼
  magnitude = 90° + (i/2) × 30°             // 偏移量递增：90°, 90°, 120°, 120°, 150°, 150°...
  angle = baseAngle + side × magnitude      // baseAngle = atan2(target - self)
  候选点 = target + (cos(angle), sin(angle)) × radius
```

成员越多，偏移角度越大，使后续成员站到更靠后的侧翼，避免前排遮挡后排。

#### 包围（`surroundTarget`）

```
对第 i 个成员（共 n 个）：
  angle = i × (360° / n)                     // 均匀分布
  候选点 = target + (cos(angle), sin(angle)) × radius
```

#### 集结（`rally`）

```
offsets = formationOffsets(formation, n, spacing)   // 纯几何阵型偏移
对第 i 个成员：
  候选点 = rallyPoint + offsets[i]
```

#### 阵型几何（`formationOffsets`）

[`FormationType`](tactical/FormationType.java) 五种阵型的偏移计算（纯几何，无 Level 依赖，可单元测试）：

| 阵型 | 偏移规则 |
|------|----------|
| `LINE`（横队） | `(i - (n-1)/2) × spacing, 0`，沿 X 轴居中排列 |
| `COLUMN`（纵队） | `0, (i - (n-1)/2) × spacing`，沿 Z 轴居中排列 |
| `WEDGE`（楔形） | `((i%2==0?+1:-1) × ((i+1)/2) × spacing, -((i+1)/2) × spacing)`，前窄后宽 |
| `CIRCLE`（圆环） | `(cos(2πi/n) × r, sin(2πi/n) × r)`，`r = spacing × n / (2π)` |
| `SQUARE`（方阵） | 螺旋填充方阵边长 `side = ceil(√n)`，逐格排列 |

---

## 六、战斗瞄准算法

[`CombatActions`](combat/CombatActions.java) 的射箭/投掷共享瞄准逻辑 [`computeAimMotion`](combat/CombatActions.java)：

### 常量

| 常量 | 值 | 含义 |
|------|----|------|
| `EYE_HEIGHT` | 1.5 | 发射点眼部高度 |
| `DEFAULT_ARROW_SPEED` | 1.5 | 默认箭初速度 |
| `DEFAULT_INACCURACY` | 0.2 | 默认散布 |
| `GRAVITY_COMPENSATION` | 0.03 | 每方块距离的向上补偿量 |

### `computeAimMotion` 流程

```
1. 发射点 = shooter 眼部 (x, y+1.5, z)
2. 方向向量 d = target眼部 - shooter眼部
3. dist = |d|（3D 距离）
4. 归一化：n = d / dist
5. 重力补偿：n.y += dist × 0.03   // 距离越远，向上分量越大，抵消抛射物下坠
6. 重新归一化并缩放：motion = normalize(n) × speed
7. 散布（inaccuracy > 0 时）：motion 各分量 += (random-0.5) × inaccuracy
```

> **说明**：这是**经验性**补偿，非精确弹道解算。它使远距离射击大致命中，配合 `inaccuracy` 模拟 AI 的不精确瞄准。若需精确命中，可将 `inaccuracy` 设为 0 并自行实现弹道解算。

### 抛射物创建

```java
Position launchPos = new Position(shooter.x, shooter.y + EYE_HEIGHT, shooter.z, shooter.getLevel());
Entity projectile = Entity.createEntity(projectileType, launchPos, shooter);  // shooter 作为归属
projectile.setMotion(motion);
projectile.spawnToAll();
```

`shooter` 作为 `createEntity` 的额外参数传入，用于击杀归属（谁射出的箭）。

---

## 七、工具类

### BlockChecks

[`BlockChecks`](util/BlockChecks.java) 提供方块可通行性判定，被寻路与战术层共用，保证两者判定一致：

| 方法 | 判定 |
|------|------|
| `isSolid(level, x, y, z)` | `!block.canPassThrough()` |
| `isPassable(level, x, y, z)` | `block.canPassThrough()` |
| `isStandable(level, x, y, z, height)` | 脚部+头部可穿过 && 脚下为固体 |

边界保护：`MIN_Y = -64`，`MAX_Y = 320`。

### LineOfSight

[`LineOfSight`](util/LineOfSight.java) 采用**等距采样射线投射**判定两点间视线：

```
step = 0.5（默认采样步长，方块）
从 from 到 to，每隔 step 取一个点：
  if 该点所在方块 isSolid → 视线被阻挡，返回 false
全部采样点都通透 → 返回 true（可见）
```

简单可靠，适合战术层的视线遮挡判定。精度取决于 `step`（越小越精确，开销越大）。

### VisionSensor

[`VisionSensor`](util/VisionSensor.java) 在 `LineOfSight` 的几何视线上叠加**距离**与**视野角度（FOV）**约束，构成完整的"生物视野"模型：

```
canSee(observer, target, maxDistance, fovDegrees):
  1. 距离检测：horizontalDistance(observer, target) ≤ maxDistance
  2. 视野角度：angleTo(observer, target) ≤ fovDegrees / 2
  3. 视线遮挡：LineOfSight.hasLineOfSight(observerEye, targetEye)
  三者全部满足 → true
```

**FOV 计算原理**：Nukkit yaw 约定（0° 朝 +Z，顺时针为正），朝向单位向量 `forward = (-sin(yaw), cos(yaw))`。目标方向单位向量 `dir = (target - observer) / |target - observer|`。点积 `dot = forward · dir = cos(夹角)`，夹角 ≤ halfFov 即在视野锥内。

- `canSee360(observer, target, maxDistance)`：全向视野（FOV=360°，跳过角度判断），适合哨塔 / 感知型实体。
- `angleTo(observer, target)`：返回夹角度数 [0, 180]，可用于调试或决策（如"侧后方的敌人优先转身"）。

### PathVisualizer

[`PathVisualizer`](util/PathVisualizer.java) 用 Nukkit 粒子（`FlameParticle`）沿寻路路径节点生成可视化标记，用于调试与演示：

- `showPathOnce(entity)`：一次性在当前路径每个节点生成一个火焰粒子（`node + (0.5, 1.0, 0.5)`，方块顶部）。不依赖调度器，直接调用 `level.addParticle`。
- `showPath(entity, durationSeconds)`：**持续显示**——注册每 10 tick 刷新一次粒子的循环任务，并在 `duration` 秒后自动停止。依赖 `NavigatorManager.getPlugin()` 获取调度器，因此须在 `bindPlugin` 之后调用。
- `stopShowPath(entity)` / `stopAll()`：手动停止；`isShowingPath(entity)`：查询状态。

> 内部用 `ConcurrentHashMap<Long, TaskHandler>` 按实体 ID 管理刷新任务，重复调用 `showPath` 会先停止旧任务再启动新任务。

---

## 八、Spring 装配机制

模块遵循 JFrame 的统一装配模式：**`@Configuration` + `@ImportResource` + 纯 XML 声明 Bean**。

### 1. 配置类

[`AiSpringConfig`](config/AiSpringConfig.java)：

```java
@Configuration
@ImportResource("classpath:ai-spring.xml")
public class AiSpringConfig { }
```

### 2. Bean 声明（`ai-spring.xml`）

```xml
<!-- 核心层 -->
<bean id="pathFinder" class="io.github.JiangHu.jframe.ai.pathfinding.PathFinder"/>
<bean id="navigatorManager" class="io.github.JiangHu.jframe.ai.navigation.NavigatorManager"/>
<!-- 行为层 -->
<bean id="tacticalScanner" class="io.github.JiangHu.jframe.ai.tactical.TacticalScanner"/>
<bean id="combatActions" class="io.github.JiangHu.jframe.ai.combat.CombatActions"/>
<!-- 中层（依赖核心层 + 调度层） -->
<bean id="anytimePathFinder" class="io.github.JiangHu.jframe.ai.navigation.AnytimePathFinder">
    <constructor-arg ref="pathFinder"/>
    <constructor-arg ref="navigatorManager"/>
</bean>
<bean id="wanderBehavior" class="io.github.JiangHu.jframe.ai.navigation.WanderBehavior">
    <constructor-arg ref="pathFinder"/>
    <constructor-arg ref="navigatorManager"/>
</bean>
<bean id="teamTactics" class="io.github.JiangHu.jframe.ai.tactical.TeamTactics"/>
<!-- 门面层（聚合全部） -->
<bean id="aiAPI" class="io.github.JiangHu.jframe.ai.AiAPI">
    <constructor-arg ref="pathFinder"/>
    <constructor-arg ref="navigatorManager"/>
    <constructor-arg ref="tacticalScanner"/>
    <constructor-arg ref="combatActions"/>
    <constructor-arg ref="anytimePathFinder"/>
    <constructor-arg ref="wanderBehavior"/>
    <constructor-arg ref="teamTactics"/>
</bean>
```

> **注意**：组件类**不加** `@Component`，统一由 XML 声明。这是 JFrame 的约定（参见 `jframe_command` 等模块）。

### 3. 集成到 jframe_main

- [`ConfigEnum`](../main/utils/ConfigEnum.java)：添加 `AI(AiSpringConfig.class)` 枚举值。
- [`MainSpringConfig`](../main/config/MainSpringConfig.java)：`@Import` 列表添加 `AiSpringConfig.class`。
- [`JFrameMain`](../main/JFrameMain.java)：添加 `getAiAPI()` 门面方法，并在 `registerServices()` 中注册到 Nukkit `ServiceManager`。
- `NavigatorManager` 实现 `PluginAware`，框架启动时自动扫描并调用 `bindPlugin`，启动调度任务。

---

## 九、扩展指南

### 1. 添加新的战术行为

在 [`TacticalScanner`](tactical/TacticalScanner.java) 中添加新方法，遵循现有模式：

```java
public TacticalPosition findXxx(Entity self, Entity target, double radius) {
    // 1. 空值/世界校验
    // 2. 立方体邻域遍历（复制现有 for 循环结构）
    // 3. findStandableY 确定候选 Y
    // 4. 按你的战术意图计算 score
    // 5. 保留最高分
}
```

然后在 [`AiAPI`](AiAPI.java) 添加对应的委托方法即可。

### 2. 添加新的战斗动作

在 [`CombatActions`](combat/CombatActions.java) 中添加方法，参考 `meleeAttack` / `shootArrow` 的模式：先 `faceTo` 转向，再执行动作。

### 3. 自定义寻路行为

通过 [`PathfinderOptions`](pathfinding/PathfinderOptions.java) 调整参数即可覆盖大多数场景。若需更深定制（如支持游泳、飞行），可继承或包装 [`PathFinder`](pathfinding/PathFinder.java)，重写 `Search` 内部的 `isWalkable` / `findLanding`。

### 4. 接入行为树 / 状态机

本模块提供的是**原子行为**，不含决策框架。推荐做法：

- 用 FSM（有限状态机）或 BT（行为树）组织决策。
- 在决策节点调用 `AiAPI` 的原子方法（`navigateTo` / `findCover` / `meleeAttack` 等）。
- 参考 [`README.md`](README.md) 第九节的 `MonsterBrain` 示例（简化 FSM）。

---

## 十、性能考量

| 操作 | 复杂度 | 建议 |
|------|--------|------|
| A* 寻路 | O(maxSearchNodes × log) | 单次寻路通常 < 1ms；长距离调大 `maxSearchNodes` |
| 战术扫描 | O(radius²) | radius=8 → 289 次方块查询；在决策节点（非每 tick）调用 |
| 视线检测 | O(distance / step) | step=0.5；距离 20 → 40 次查询 |
| 导航 tick | O(1) per entity | 单实体开销极小，可支持数十并发导航 |

### 优化建议

1. **寻路缓存**：若多个实体走向同一目标，可缓存 `PathResult` 复用（注意起点不同需重算）。
2. **战术节流**：战术扫描不必每 tick 执行，可每 10~20 tick 或状态变化时执行一次。
3. **导航上限**：监控 `activeNavigatorCount()`，避免同时导航过多实体导致 tick 超时。
4. **异步寻路**：长距离寻路可考虑放到异步线程计算，完成后再提交导航（注意 Nukkit 实体操作须在主线程）。

---

## 十一、测试

测试位于 [`PathFinderLogicTest`](../../../test/java/io/github/JiangHu/jframe/ai/pathfinding/PathFinderLogicTest.java)，采用 JUnit 5（Jupiter），聚焦**纯逻辑**（不依赖运行中的 Nukkit 服务端）。

### 测试覆盖

| 测试类 | 覆盖内容 |
|--------|----------|
| `BlockNodeTest` | 节点坐标、fCost 计算、equals/hashCode、距离计算 |
| `HeuristicTest` | 三种启发式的估值正确性 |
| `OptionsTest` | 参数默认值、fluent setter、下限保护 |
| `PathResultTest` | 成功/失败/PARTIAL 工厂、isSuccess/isPartial/hasPath 语义、状态枚举 |
| `TacticalPositionTest` | record 字段、empty/isPresent、转换方法 |
| `ExtendedPathfindingLogicTest` | PARTIAL 状态判定、`PathfinderOptions` 新字段（stepCostFunction/partialOnFailure）链式配置、`StepCostFunction` 函数式行为 |
| `TeamTacticsLogicTest` | 五种阵型（LINE/COLUMN/WEDGE/CIRCLE/SQUARE）偏移几何正确性、边界情况（空列表/单成员） |

共 **52 个测试方法**（25 原有 + 27 新增），全部通过：

```bash
mvn -pl jframe_ai test
# Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
```

### 运行测试

```bash
# 仅 AI 模块
mvn -pl jframe_ai test

# 全量（含依赖模块）
mvn -pl jframe_ai -am test
```

> 涉及 Nukkit 实体/世界的集成测试（如真实寻路、导航驱动）需要运行中的服务端，当前以纯逻辑测试为主。可在测试服务器中手动验证端到端行为。
