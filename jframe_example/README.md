# JFrame ECS 示例插件（jframe_example）

> 一个完整可运行的 Nukkit 插件样例，用于**实机测试 jframe_ecs 模块**（实体-组件-系统引擎）：
> 四组演示场景 + `/ecs` 命令控制台，把引擎全部特性变成游戏内"点一下就见效"的现象。

---

## 📑 目录

- [一、它演示什么](#一它演示什么)
- [二、目录结构](#二目录结构)
- [三、构建与部署](#三构建与部署)
- [四、游戏内命令](#四游戏内命令)
- [五、实机验收清单](#五实机验收清单)
- [六、常见现象与排查](#六常见现象与排查)

---

## 一、它演示什么

四组场景覆盖 jframe_ecs 引擎的全部特性，全部"零门槛触发"（右键箱子、打一下僵尸、敲一条命令），
反馈全部走聊天栏消息 + 控制台日志：

| 场景 | 触发方式 | 覆盖的引擎特性 |
|------|---------|---------------|
| **A 魔法箱** | 右键 / 打开一个箱子 | 内置 `blockentity-class` 选择器（存量+后放全识别）、方法内首行守卫（右键回话/左键不回话）、事件桥内置 `block-interact` 解析器、自定义解析器 `chest-holder`（库存持有者这种"歪门邪道对象"） |
| **B 精英僵尸** | 刷一只僵尸、打它一下 | 内置 `entity-class` 选择器、系统 query（名牌显血）、方法内首行守卫（重击阈值直接读 `this` 字段） |
| **C 守卫区** | 在出生点 64 格内右键箱子 | 自定义参数化选择器 `near-spawn`（distance/tag 注入）、**多定义并集**（同一事件两条消息同时出现） |
| **D 幽灵波次** | `/ecs wave`，杀光幽灵 | 自定义生成器 `ghost-pack`（参数化生成）、**纯虚拟 gameobject**（波次管理器）、系统消费引擎 diff 卸载语义做波次结算 |
| **E 进服播报** | 任意玩家进服 | **系统事件**（`@OnEcsEvent` 上系统类，全局处理器无需解析器）、`EcsEngineAware` 处理器内全局 query |

另由 `/ecs start` 演示**存量回溯**（启动瞬间统计已有箱子/僵尸挂载数）与 `/ecs stop` 演示
**逆序卸载**（控制台逐条 onUnmount 日志）。

---

## 二、目录结构

```
jframe_example/
├── pom.xml                              # provided 依赖 jframe_main（spring-expression 显式 provided 防类冲突）
└── src/main/
    ├── resources/
    │   ├── plugin.yml                   # depend [JFrame]；根命令 /ecs 由命令模块动态注册
    │   └── gameobjects/example.yml      # 五条 GameObject 定义（打进 jar，classpath 扫描加载）
    └── java/.../example/
        ├── ExamplePlugin.java           # 主类：扫描五类注解 + 加载定义（不启动，/ecs start 手动启动）
        └── ecs/
            ├── command/EcsTestController.java    # /ecs 命令控制台
            ├── component/                        # 5 组件
            │   ├── MagicChestComponent.java      #   魔法箱（首行守卫 + 双解析链路）
            │   ├── EliteComponent.java           #   精英（首行守卫 + lastPercent 判重）
            │   ├── GuardAuraComponent.java       #   守卫光环（并集第二组件）
            │   ├── GhostMarkComponent.java       #   幽灵标记（倒排查询锚点）
            │   └── WaveStateComponent.java       #   波次状态（挂纯虚拟实体）
            ├── selector/NearSpawnSelector.java   # 自定义参数化选择器
            ├── spawner/GhostPackSpawner.java     # 自定义生成器
            ├── resolver/ChestHolderResolver.java # 自定义宿主解析器
            └── system/
                ├── EliteTagSystem.java           # 每秒名牌显血（interval 20）
                ├── WaveSystem.java               # 每 2 秒波次结算（interval 40）
                └── JoinEchoSystem.java           # 系统事件：进服播报（无 tick、无解析器）
```

---

## 三、构建与部署

```bash
mvn -pl jframe_example -am package
```

部署（顺序重要）：

1. `jframe_main/target/jframe_main.jar` → 服务器 `plugins/`（框架 + ECS 引擎，先放）
2. `jframe_example/target/jframe_example.jar` → 服务器 `plugins/`（本示例，后放）
3. 启动服务器，控制台出现 `ECS 示例插件已启用：已注册 5 条定义但引擎未启动` 即部署成功

---

## 四、游戏内命令

| 命令 | 行为 |
|------|------|
| `/ecs` | 显示帮助 |
| `/ecs start` | 启动引擎，**回显存量识别统计**（魔法箱/精英/守卫/幽灵/管理器各挂载几个） |
| `/ecs stop` | 停止引擎（全部组件逆序卸载，控制台逐条可见） |
| `/ecs status` | 运行状态 + 各组件挂载数 + 当前波次 |
| `/ecs list` | 已加载的 5 条定义（选择器/生成器/组件明细） |
| `/ecs spawn <定义名>` | 以自身位置现场生成（`night-ghosts` / `wave-manager`；**未 start 也可用**） |
| `/ecs wave` | 一键波次：自动补建管理器 + 生成 2 只幽灵 |
| `/ecs refresh` | 全实体手动重评（演示"只补挂不卸载"） |

---

## 五、实机验收清单

按顺序走一遍，每步都有肉眼可见的现象：

1. `/ecs list` → 显示 5 条定义（all-chests / elite-zombies / guardian-chests / night-ghosts / wave-manager）；
2. `/ecs start` → 回显"存量识别：魔法箱 N、精英僵尸 M…"（世界里已有的箱子/僵尸立即全部挂载）；
3. **后放识别**：新放一个箱子 → 首次互动当 tick 挂载（零延迟）；未互动 ≤5 秒对账收养日志；砸掉 → 即时卸载日志；
4. **魔法箱**：右键箱子 → 连续两条消息（触碰 + 打开，事件双连发属预期）；左键 → 无消息（方法内守卫）；
5. **守卫区并集**：在出生点 64 格内右键箱子 → **两条**消息（magic-chest + guard-aura）；64 格外 → 仅一条（自定义选择器边界）；
6. **精英僵尸**：刷一只僵尸 → 名牌 1 秒内出现"§c精英 §7HP 100%"且**受击后数值刷新**（变化才更新，无闪烁）；打它一下（空手/剑均可）→ 全服"精英受到重击"（方法内首行守卫）；）；
7. **幽灵波次**：`/ecs wave` → 生成 2 只"幽灵"（循环单生成，计数精确）；每杀一只全服广播"剩余幽灵 N"；杀光 → 广播波次 +1；
8. **系统事件**：任意玩家进服 → 全服播报"欢迎 XX！当前跟踪实体 N / 魔法箱 M / 精英 K"（无解析器事件的全局处理器）；
9. **零延迟跟踪**：放箱后**首次互动（右键/打开）当 tick 挂载并立即回话**；未互动箱子 ≤5 秒对账收养（控制台日志）；砸箱即时卸载；
10. **停止**：`/ecs status` 各计数实时变化；`/ecs stop` → 控制台逐条输出卸载日志。

---

## 六、常见现象与排查

| 现象 | 说明 |
|------|------|
| 右键箱子一次出**两条**消息 | 预期：先 `PlayerInteractEvent`（触碰）后 `InventoryOpenEvent`（打开），两条解析链路各回一条 |
| 左键箱子无消息 | 预期：`onInteract` 方法内首行守卫拦截（仅右键方块放行） |
| 名牌血量不是每秒变 | 预期：变化才更新+重发（Nukkit `setNameTag` 不广播，每秒重发会闪烁） |
| `/ecs spawn` 未 start 也能生成 | 预期：spawn 只依赖定义加载，不依赖引擎主循环 |
| 同一实体命中多条定义且组件 id 相同 | 只挂**首个**（按定义加载顺序）；差异化请用不同组件 id（本示例 guard-aura 即为此设计） |
| 幽灵用 Skeleton 而非 Zombie | 刻意规避：僵尸会被 elite-zombies 选择器同时命中（名牌互相覆盖、重击消息串台） |
| 改了 example.yml 数值不生效 | 定义在插件启用时一次性加载，不热更——重启服务器（或重载插件）生效 |
| 控制台大量"魔法箱挂载/卸载"日志 | 世界 diff 正常行为；区块加载/卸载也会触发，观察完可 `/ecs stop` 安静 |

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)；引擎原理与完整 API 见
> [jframe_ecs README](../jframe_ecs/src/main/java/io/github/JiangHu/jframe/ecs/README.md)。
