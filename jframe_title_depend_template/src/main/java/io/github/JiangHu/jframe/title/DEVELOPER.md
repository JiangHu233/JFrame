# jframe_title 开发者文档

> 面向模块维护者与二次开发者：Nukkit 适配、目录结构、类职责、核心设计、Spring 集成、扩展与调试。
> 用户向使用说明见同目录 [README.md](README.md)。

---

## 目录

- [Nukkit API 对接（实测签名）](#nukkit-api-对接实测签名)
- [目录结构](#目录结构)
- [类职责矩阵](#类职责矩阵)
- [核心设计](#核心设计)
- [Spring 集成（Bean 链 + 五步曲）](#spring-集成bean-链--五步曲)
- [模板引擎依赖（列格式 / 元数据）](#模板引擎依赖列格式--元数据)
- [扩展指南](#扩展指南)
- [调试技巧](#调试技巧)

---

## Nukkit API 对接（实测签名）

模块内**所有** Nukkit 依赖收敛在 `nukkit` 包两个 Default 实现中，实测 Nukkit MOT API 签名：

**发包（`DefaultTitleSender` → `Player`）**

```java
Player.sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut)
Player.sendActionBar(String text)
Player.clearTitle()
```

**调度（`DefaultTitleScheduler` → `ServerScheduler`）**

```java
ServerScheduler.scheduleDelayedTask(Runnable, int)   // → TaskHandler
ServerScheduler.scheduleRepeatingTask(Runnable, int) // → TaskHandler
TaskHandler.cancel()
Server.isPrimaryThread()
```

要点：

- `clearTitle()` 只清除 title/subtitle 通道；**动作栏无清除 API**，停止续发后自然消失（`deactivate` 的语义据此设计）
- `Server` 未初始化（单元测试环境）时 `isPrimaryThread()` 捕获 `IllegalStateException` 返回 true，使 `runOnPrimaryThread` 退化为同步执行——纯逻辑测试无需 Nukkit 运行时
- `cancel(handle)` 幂等：null 静默忽略、已取消的 `TaskHandler` 跳过

---

## 目录结构

```
io.github.JiangHu.jframe.title
├── TitleAPI.java            # 门面：全部公开 API 唯一入口（委托 Manager）
├── TitleManager.java        # 模板注册表 + 双槽位视图管理 + KeepAlive
├── TitleView.java           # 单玩家单模板视图：通道级 diff + 主线程调度
├── TitleTemplate.java       # Template + 时序 + 通道映射的不可变打包（Builder + 三级融合）
├── TitleTiming.java         # 时序参数（fadeIn/stay/fadeOut，tick）
├── TitleMode.java           # 显示模式枚举：PERSISTENT / TRANSIENT
├── TitleConstants.java      # 内置默认值（三级融合兜底层）
├── TitlePluginScope.java    # 插件作用域：绑定 ClassLoader 的模板加载代理
├── TitleLog.java            # 日志工具（TAG 前缀输出）
├── channel/                 # 通道子包：元数据解析 + 渲染结果切分（纯逻辑，零 Nukkit 依赖）
│   ├── TitleMetaKeys.java       # 9 个 title.* key 常量 + 已知 key 全集
│   ├── TitleMetaConfig.java     # 元数据解析结果（Optional 字段容器）
│   ├── TitleMetaResolver.java   # Template 元数据 → TitleMetaConfig（容错解析）
│   ├── TitleChannels.java       # 三通道文本载体 + 通道级 diff 比较方法
│   └── TitleChannelMapper.java  # RenderResult → TitleChannels 纯切分
├── nukkit/                  # Nukkit 适配子包：接口 + Default 实现（可注入桩）
│   ├── TitleSender.java         # 发包适配接口（sendTitle/sendActionBar/clearTitle）
│   ├── DefaultTitleSender.java  # 默认实现：委托 Player
│   ├── TitleScheduler.java      # 调度适配接口（延迟/周期/取消/主线程）
│   └── DefaultTitleScheduler.java # 默认实现：委托 ServerScheduler
└── config/
    └── TitleSpringConfig.java   # Spring 配置入口（@ImportResource title-spring.xml）

resources/title-spring.xml   # Bean DAG：templateEngine → titleManager → titleAPI
```

---

## 类职责矩阵

| 类 | 职责 | 关键依赖 | 线程安全 |
|----|------|---------|---------|
| `TitleAPI` | 门面：委托转发，实现 `ForPlugin<TitlePluginScope>` | TitleManager、TemplateEngine | 由 Manager 保证 |
| `TitleManager` | 模板注册表；TITLE/ACTIONBAR 槽位占用表；视图缓存；KeepAlive 切换；瞬时到期回调清理 | TemplateEngine、TitleSender 工厂、TitleScheduler | ConcurrentHashMap 全程 |
| `TitleView` | 视图生命周期（show/deactivate/dispose）；增量渲染触发；通道级 diff 输出；定时任务（到期/续期） | IncrementalRenderer、TitleChannelMapper、TitleSender、TitleScheduler、EffectScope | 生命周期方法 synchronized；发包经主线程调度 |
| `TitleTemplate` | 不可变配置打包；Builder 三级融合；fail-fast 校验；槽位占用判定 | Template、TitleMetaResolver、TitleConstants | 不可变对象 |
| `TitleTiming` | 时序参数值对象（含 `totalTicks()`） | — | 不可变 |
| `TitleMode` | PERSISTENT / TRANSIENT 枚举 | — | — |
| `TitleMetaResolver` | `Template.getMetadata()` → `TitleMetaConfig`；非 title.* 忽略、未知 title.* 告警 | TitleMetaKeys | 无状态 |
| `TitleChannelMapper` | 渲染结果 → 三通道文本（区间切分/行提取，越界裁剪） | TitleTemplate | 无状态静态 |
| `TitleChannels` | 三通道文本 + `titleOrSubtitleChanged` / `actionbarChanged` diff 方法 | — | 不可变 |
| `TitlePluginScope` | 插件 ClassLoader 绑定 + 前缀（默认 `templates/`）+ 加载注册 | TemplateEngine.forPlugin | 每次新建实例 |
| `TitleSender` / `DefaultTitleSender` | 发包适配 / Player 委托 | Player | 单玩家绑定 |
| `TitleScheduler` / `DefaultTitleScheduler` | 调度适配 / ServerScheduler 委托 | Server | 委托线程安全 |

---

## 核心设计

### 1. 三层架构（API → Manager → View）

```
TitleAPI（门面，零业务逻辑）
   └── TitleManager（注册表 + 槽位调度，全 ConcurrentHashMap）
         └── TitleView ×N（每玩家每模板一个，synchronized 生命周期）
               └── TitleSender + TitleScheduler（Nukkit 适配，可注入桩）
```

- 门面只做委托转发，公开 API 面与 ScoreboardAPI 逐方法对齐（loadTemplate×3 / show / showIf / showAll / hide / hideAll / updatePlayer / update / updateAll / updateGlobal / updateTemplate + Map 变体 / onPlayerQuit / forPlugin / create）
- Manager 持有 `senderFactory`（`Function<Player, TitleSender>`）与 `scheduler`，全参构造供测试注入桩；包级 `show(UUID, String, TitleSender)` / `hide(UUID)` / `update(UUID, ...)` / `onPlayerQuit(UUID)` 核心逻辑供测试脱离 `Player` 对象驱动

### 2. 通道映射（channel 包）

渲染结果 → 三通道的**纯切分**（无渲染逻辑）：

- 主标题 ← `<title>` 标签产物
- 副标题 ← `lines[subtitleFrom, subtitleTo)` 半开区间，多行 `\n` 拼接，越界自动裁剪（不抛异常）
- 动作栏 ← `lines[actionbarLine]`；`-1`（禁用）或行不存在 → 该通道 `null`

列对齐等格式化**已由引擎渲染阶段完成**（见[模板引擎依赖](#模板引擎依赖列格式--元数据)），Mapper 只做行到通道的切分——保证 title 模块对渲染细节零耦合。

### 3. 三级配置融合（TitleTemplate.Builder.build）

优先级从高到低：

1. **Builder 显式设置**（`timing(...)` / `subtitleLines(...)` 等；时序为整体覆盖）
2. **模板元数据**（`<meta key="title.*" value="..."/>`，经 `TitleMetaResolver` 解析；时序逐 key 融合，缺项回落默认）
3. **内置默认**（`TitleConstants`：时序 10/70/20，副标题 `[0,1)`，动作栏 `lines[1]`，常驻，到期自动清除，不续期）

fail-fast 校验（`IllegalArgumentException`）：`from < 0`、`to < from`、`actionbar < -1`、**`actionbar ∈ [from, to)`（通道互斥）**、`refresh < 0`；副标题行数 > `SUGGESTED_MAX_SUBTITLE_LINES(8)` 仅告警。

槽位占用判定：`occupiesTitleSlot() = from < to`；`occupiesActionBarSlot() = actionbarLine >= 0`。

### 4. 双模式双槽位（TitleManager + TitleView）

**双槽位**：每玩家两张占用表 `activeTitleView` / `activeActionBarView`（UUID → 模板名）。`show` 流程：

1. 按目标模板占用情况 `evictSlot` 停用同槽位当前视图（deactivate，KeepAlive）
2. 缓存命中且未 dispose → `reactivate`（重复 show 同一模板先 deactivate 强制清屏重发）；否则新建视图（parent 为玩家全局层）并 `show`
3. 更新占用标记

副标题区间为空（`from == to`）的模板不参与 TITLE 槽争用——**常驻动作栏 HUD 与瞬时公告双槽共存**。

**双模式定时任务**（activate 时按模式调度）：

- TRANSIENT：`scheduleDelayed(onExpire, timing.totalTicks())`；到期时（autoClear 开启且占 TITLE 槽）`clearTitle()` → `dispose` → 经回调 `onTransientExpired` 移除 Manager 缓存与槽位标记
- PERSISTENT：占 ACTIONBAR 槽且 `refresh > 0` 时 `scheduleRepeating(renewActionBar, refresh)`；续期**不重新渲染**，纯重发 `lastChannels.actionbar`

### 5. 通道级 diff（TitleView）

- `lastChannels` 镜像保存上次发送的三通道文本
- **title + subtitle 是一个发送单元**（一次 `sendTitle` 携带时序），`titleOrSubtitleChanged` 任一变化即整单元重发；actionbar 独立 `actionbarChanged`
- 增量刷新路径：`DataContext.onChange → refresh(ChangeSet) → scheduler.runOnPrimaryThread → IncrementalRenderer.renderIncremental → hasChanges? → applyChannels`；渲染无变更时**零发包**
- `activate` 恒全量重发（客户端状态未知），但**只发本视图占用的槽位通道**，避免顶掉另一槽位的共存视图

### 6. KeepAlive 与 EffectScope

三段式生命周期：

| 方法 | 显示 | dataContext | 渲染监听 | 定时任务 | 用途 |
|------|-----|------------|---------|---------|------|
| `show` / `reactivate` | 显示 | 不动 | 注册 | 重建 | 进入或恢复激活 |
| `deactivate` | 清除 | **保留** | 移除 | 取消 | 槽位切换 / 隐藏 |
| `dispose` | 清除 | 销毁 | 全部摘除 | 取消 | 玩家退出 / 到期 |

- deactivate 保留 `dataContext` / `incrementalRenderer` / `lastChannels`，再次 show 同名模板直接复用（零重渲染成本恢复显示）
- 构造时将 `dataContext::dispose` 与两个任务取消注册进 `EffectScope`，dispose 自动执行清理链——保证与玩家全局层的监听引用**必定断开**，杜绝内存泄漏
- `renewActionBar` 入口防御取消竞态（任务触发时视图可能已停用）

---

## Spring 集成（Bean 链 + 五步曲）

### Bean DAG（`title-spring.xml`，单向无循环）

```
templateEngine（TemplateEngine，来自 jframe_template）
      ↓ constructor-arg
titleManager（TitleManager）
      ↓ constructor-arg ×2（manager + engine）
titleAPI（TitleAPI，公开入口）
```

`TitleSpringConfig` 以 `@Configuration + @ImportResource("classpath:title-spring.xml")` 导入装配。

### jframe_main 五步曲接入（阶段三已完成）

| 步骤 | 文件 | 内容 |
|------|------|------|
| ① 模块 SpringConfig | `config/TitleSpringConfig.java` | 模块内提供（阶段二） |
| ② spring XML | `resources/title-spring.xml` | 模块内提供（阶段二） |
| ③ Maven 依赖 | `jframe_main/pom.xml` | 添加 `jframe_title` 依赖（对齐 scoreboard 依赖写法） |
| ④ 枚举注册 | `jframe_main/.../utils/ConfigEnum.java` | `TITLE(TitleSpringConfig.class)`（对齐 SCOREBOARD 项） |
| ④ 配置导入 | `jframe_main/.../config/MainSpringConfig.java` | `@Import(... TitleSpringConfig.class)` |
| ⑤ 门面 + Service | `jframe_main/.../JFrameMain.java` | `getTitleAPI()` getter + `ServiceManager.register(TitleAPI.class, ...)` + `onDisable` 中 `disposeAll()` |

`titleAPI` Bean 获取方式与 scoreboard 一致：`applicationContext.getBean(TitleAPI.class)`。

---

## 模板引擎依赖（列格式 / 元数据）

title 模块消费 jframe_template 阶段一引入的两项引擎能力：

### 元数据标签（`<meta key value/>`）

- 引擎侧：`TemplateParser` 收集为不可变 Map，`Template.getMetadata()` / `getMetadata(String)` → `Optional<String>` 暴露
- title 侧：`TitleMetaResolver` 只认 `title.` 前缀；非 title.* 静默忽略（其他模块命名空间），title.* 未知 key 告警不抛异常；解析产物 `TitleMetaConfig` 为 Optional 字段容器，参与三级融合第 2 层

### 列格式（`columns` 根属性 + `<columns>` 配置块）

- 引擎侧：渲染阶段按 `ColumnLayout`（分隔符 + 列策略列表）对每行做显示宽度感知的切分对齐（中文占 2 格），输出已对齐的行文本
- title 侧：**零感知**——`TitleChannelMapper` 拿到的 `RenderResult.getLines()` 已是对齐后的文本，副标题多行列表（如排行榜）天然获得列对齐效果
- 语法：`<template columns="|">` 声明分隔符（多字符如 `;;` 可用，空串解析异常）；`<columns><column align="left|center|right" width="auto|正整数"/></columns>` 按序声明策略（缺省 left + auto）；配置块单独出现隐式启用（默认分隔符 `|`）；与根属性组合时分隔符取根属性、策略取配置块；重复 `<columns>` 块解析异常
- 向后兼容：无 `columns` 属性的旧模板列格式禁用（`ColumnLayout.DISABLED`），行为不变

---

## 扩展指南

### 自定义发包适配（TitleSender）

替换默认发送器（如批量代理、协议修改、录制回放）：

```java
Function<Player, TitleSender> factory = player -> new MyTitleSender(player);
TitleManager manager = new TitleManager(engine, factory, TitleScheduler.defaultScheduler());
TitleAPI api = new TitleAPI(manager, engine);
```

实现 `TitleSender` 三个方法即可（`sendTitle` 五参 / `sendActionBar` / `clearTitle`）。

### 自定义调度适配（TitleScheduler）

替换默认调度器（如手动时钟做确定性测试）：

```java
class ManualClock implements TitleScheduler {
    final List<Runnable> pending = new ArrayList<>();
    public Object scheduleDelayed(Runnable task, int ticks) { pending.add(task); return pending.size(); }
    public Object scheduleRepeating(Runnable task, int ticks) { pending.add(task); return pending.size(); }
    public void cancel(Object handle) { }
    public boolean isPrimaryThread() { return true; }
}
```

模块自带测试桩：`RecordingTitleSender`（录制发包序列）与 `ManualTitleScheduler`（手动推进时钟）。

### 新增 title.* 元数据 key

1. `TitleMetaKeys` 增加常量并加入 `KNOWN_KEYS`
2. `TitleMetaConfig` 增加 Optional 字段与 getter
3. `TitleMetaResolver` 增加解析分支
4. `TitleTemplate.Builder` 增加显式覆盖字段并纳入 `build()` 融合与 `validate()`
5. 补 `TitleMetaResolverLogicTest` / `TitleTemplateLogicTest` 用例

### 新增通道（如 BossBar）

参照 channel 包模式：扩展 `TitleChannels` 与 `TitleChannelMapper`，槽位判定加进 `TitleTemplate`，`TitleView.applyChannels` 增加对应 diff 分支——发包适配经 `TitleSender` 扩展方法。

---

## 调试技巧

1. **模板不显示**：先查 `TitleLog` 告警——`模板[x]未注册，无法显示`（show 前未 loadTemplate）或 `模板[x]在引擎中不存在，加载失败`（`loadTemplate(String)` 按名加载失败）
2. **build 抛 IllegalArgumentException**：看消息定位——区间负数 / 终点小于起点 / 动作栏行号非法 / **通道互斥**（actionbar 落入副标题区间）/ 续期负数
3. **动作栏不显示**：检查 `actionbarLine` 是否越界（行不存在时通道静默无内容）；纯动作栏模板记得 `subtitle.from == subtitle.to` 让出 TITLE 槽
4. **动作栏几秒后消失**：常驻模式未配 `title.actionbar.refresh`（默认 0 不续期），客户端动作栏自然超时
5. **公告顶掉 HUD**：确认公告模板 `actionbarLine = -1`（不占 ACTIONBAR 槽），HUD 模板副标题区间为空（不占 TITLE 槽）
6. **副标题显示异常**：行数超过 8 行会有告警日志（`超过建议上限`），客户端可能截断
7. **数据更新无反应**：视图可能处于 deactivate（KeepAlive 停用中不响应数据变化）；用 `getView(player)` / `getActionBarView(player)` 确认激活状态
8. **单元测试**：Manager 包级核心逻辑（`show(UUID, String, TitleSender)` 等）+ `RecordingTitleSender` / `ManualTitleScheduler` 桩可完全脱离 Nukkit 运行时驱动全流程；`DefaultTitleScheduler.isPrimaryThread` 在 Server 未初始化时返回 true（同步执行），纯逻辑测试直接可跑
