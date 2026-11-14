# jframe_example — 维护者文档

> 面向模块维护者。记录本示例插件的定位、接入实现细节、场景设计意图与已知边界。
> 使用文档请看 [README.md](README.md)。

---

## 📑 目录

- [一、模块定位](#一模块定位)
- [二、接入实现细节](#二接入实现细节)
- [三、场景 ↔ 引擎特性映射](#三场景--引擎特性映射)
- [四、生命周期职责划分](#四生命周期职责划分)
- [五、已知边界与维护规范](#五已知边界与维护规范)

---

## 一、模块定位

本模块是 **jframe_ecs 引擎的实机测试场**：

- **回归验证**：四组演示场景把引擎全部特性转成游戏内肉眼可见的现象，升级引擎后按
  [README 验收清单](README.md#五实机验收清单) 走一遍即可人工回归；
- **接入范本**：[`ExamplePlugin`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java)
  是"业务插件四行接入 ECS"的标准模板（扫描 → 加载定义 → 命令启动）；
- **积木样例**：`ecs` 包内 12 个文件每类都是一个最小可复制改造的引擎扩展点样例。

> 本模块不是框架核心、不写单元测试（单测由 jframe_ecs 的 56 项用例覆盖）；
> 实机若暴露引擎缺陷，回 jframe_ecs 修，不在本模块打补丁。

---

## 二、接入实现细节

### 1. 手动 start（刻意设计）

`onEnable` 只做**注册**（scan + loadGameObjects），引擎由 `/ecs start` 手动启动：

- 启动前可观察服务器裸跑状态，启动瞬间又能直观看到**存量回溯**（回显挂载统计）；
- `/ecs spawn` 在未 start 时也可用（spawn 只依赖定义加载），演示了引擎各能力的独立可用性。

### 2. 跨插件类加载器（最易翻车）

- 组件/选择器/生成器/解析器/过滤器类由**本插件类加载器**加载，引擎（JFrame 加载器）
  反射实例化 → `ecs` 包内所有注册类**必须 public + public 无参构造**；
- **spring-expression 必须显式 provided**：根 pom 对全部模块默认 compile 注入该件
  （listener 模块的 SpEL 条件依赖它），不降级会被 shade 打进本插件 jar，
  与 jframe_main.jar 同名类形成 Nukkit 多插件类加载器冲突（IllegalAccessError）。
  其余 spring 件同理（见 pom 注释）；
- `EcsAPI` 等框架类运行时来自 jframe_main.jar（已 shade jframe_ecs），本插件不打包。

### 3. 命令注入模式

`jframe_command` 仅支持无参构造 → `EcsTestController` 用静态字段持有 `EcsAPI`，
由 `ExamplePlugin.onEnable` 在 `commandAPI.forPlugin(this).scan(...)` **之前**调用
`setEcs` 注入（与旧 AiController 模式一致）。

---

## 三、场景 ↔ 引擎特性映射

| 场景 | 定义（example.yml） | 验证的引擎语义 |
|------|--------------------|---------------|
| A 魔法箱 | `all-chests`（blockentity-class → magic-chest） | 方块实体选择、enter/回溯双管线、方法内首行守卫（无 SpEL/无 filter 机制）、内置解析器 block-interact、自定义解析器 chest-holder |
| B 精英僵尸 | `elite-zombies`（entity-class → elite） | 实体选择、方法内首行守卫（阈值直接读 `this` 字段）、系统 interval + query（EliteTagSystem）、内置解析器 entity-events、**Nukkit setNameTag 不广播坑**（变化才 spawnToAll） |
| C 守卫区 | `guardian-chests`（near-spawn → guard-aura） | 自定义参数化选择器、**多定义并集**（同事件双消息）、未配置参数保留默认值（seconds） |
| D 幽灵波次 | `night-ghosts`（单只生成）+ `wave-manager`（virtual） | 自定义生成器（一次调用=一个实体，`/ecs wave` 循环）、纯虚拟 gameobject、系统消费 despawn 事件/对账卸载语义做波次结算 |
| F 竞技场 | `arena`（arena-template / arena-recycle） | **POJO 产物代理**（binding()=ArenaRef，不限于 entity）、**spawn/despawn 文件 IO 对**（创建写 marker / 销毁删 marker）、销毁通知驱动回收（destroy 触发、stop 不触发）、组件 onUnmount 先于 despawn 回调 |
| E 进服播报 | （无定义，`JoinEchoSystem` 类订阅） | **系统事件**（`@OnEcsEvent` 上系统类=全局处理器，无需解析器）、`EcsEngineAware` 处理器内全局 query、**无 tick 零轮询**（tick 可选示范） |
| 零延迟跟踪 | （无定义，引擎内置） | `NukkitWorldTracker` 事件跟踪（生成/despawn/区块/破坏）+ `resolveCandidate` 惰性收养（放箱首触当 tick 挂载）+ 100 tick 对账 |
| 命令组 | `/ecs start / stop / refresh` | 存量回溯统计、逆序卸载、手动重评只补挂不卸载 |

---

## 四、生命周期职责划分

| 时机 | 本插件 | 前置 JFrame |
|------|--------|------------|
| JFrame 启用 | — | 创建容器、bindPlugin（含 EcsAPI）、注册服务 |
| 本插件启用 | 扫描命令/ECS 六类注解、加载定义、**不启动** | — |
| `/ecs start`~`stop` | 引擎运行（diff + 事件桥 + 系统 tick） | — |
| JFrame 禁用 | `onDisable` 留空 | **最先** stop ECS 引擎（未 start 时安全）、关闭容器 |

---

## 五、已知边界与维护规范

- **YAML 顺序硬依赖**：`guardian-chests` 必须声明在 `all-chests` 之后
  （near-spawn 依赖前者打的 `chest` 标签；enter 与回溯管线均按文件声明序遍历定义）。
- **幽灵类型必须避开 EntityZombie**（现用 Skeleton）：否则被 elite-zombies 同时命中，
  名牌互相覆盖、重击消息串台。
- **定义不热更**：改 example.yml 需重启/重载插件；定义名冲突引擎会直接拒绝加载。
- **WaveSystem 只认首个管理器**：`/ecs wave` 自动补建，勿引导用户手动重复
  `/ecs spawn wave-manager`。
- **多管理器/重复 spawn** 属演示可接受行为，不写防御代码（保持示例可读性优先）。
- 新增演示场景时：同步更新 README 验收清单与本节映射表；组件类保持
  "public + 无参构造 + 参数即字段"三件套。
