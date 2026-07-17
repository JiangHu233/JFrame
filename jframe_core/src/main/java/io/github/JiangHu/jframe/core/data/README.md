# jframe_core · data 数据类型

本包提供框架级的通用数据容器，位于 `io.github.JiangHu.jframe.core.data`，按用途划分为两个子包：

```
data/
├── map/        # 键值映射数据结构
│   ├── AbstractFunctionalMap.java
│   ├── LruCacheMap.java
│   └── PlayerDataMap.java
├── reactive/   # 响应式 / 纠缠值
│   ├── Entangled.java          # 无类型根接口（异构纠缠基础）
│   ├── EntangledValue.java     # 可「纠缠」的响应式值容器
│   ├── EntangledChannel.java   # 纠缠通道（协调中枢，一等公民）
│   ├── EntangledEvent.java     # 纠缠值变化事件
│   └── Role.java               # 成员收发角色（BOTH/SOURCE/SINK/MUTE）
└── README.md
```

## 子包一览

### `map` —— 键值映射

| 类型 | 说明 |
|------|------|
| [`AbstractFunctionalMap`](map/AbstractFunctionalMap.java) | 功能可插拔的 Map 抽象基类（键提取 / 加载 / 过期 / 淘汰回调） |
| [`LruCacheMap`](map/LruCacheMap.java) | 线程安全的 LRU 缓存映射 |
| [`PlayerDataMap`](map/PlayerDataMap.java) | 以玩家为键、玩家退出自动清理的映射 |

### `reactive` —— 响应式纠缠值

| 类型 | 说明 |
|------|------|
| [`EntangledValue`](reactive/EntangledValue.java) | 可「纠缠」的响应式值容器（泛型 `<T>`） |
| [`EntangledChannel`](reactive/EntangledChannel.java) | 纠缠通道：多个成员的协调中枢，独立一等公民 |
| [`Entangled`](reactive/Entangled.java) | 无类型根接口，使不同 `T` 的值可纠缠到同一通道（异构） |
| [`EntangledEvent`](reactive/EntangledEvent.java) | 纠缠值变化事件（`source` / `oldValue` / `newValue`） |
| [`Role`](reactive/Role.java) | 成员在通道内的收发方向：`BOTH` / `SOURCE` / `SINK` / `MUTE` |

---

## EntangledValue：纠缠值

灵感取自**量子纠缠**：多个 `EntangledValue` 对象可以「纠缠」在一起，此后组内**任意一个**对象的值更新，都会**通知**组内所有其他对象。适合「一处变化、多处感知」的场景——例如同一份血量数据同时驱动 HUD、Boss 血条、计分板，任一处修改后其余自动刷新。

### 核心概念

- **纠缠通道（Channel）**：纠缠关系的载体。纠缠本质是「若干成员加入同一通道」。通道是独立的一等公民，可单独创建、持有、传递，并可选挂名称与归属对象。
- **纠缠组**：纠缠是等价关系。`a` 纠缠 `b`、`b` 纠缠 `c`，则三者同通道，`a` 更新时 `c` 也会收到通知。
- **通知模式**：通道内某成员 `set` 新值后，**仅通知**其他成员（触发监听器），但**不改变**其他成员自身的值。各成员的值相互独立，由监听器决定如何响应。
- **成员角色（Role）**：每个成员在通道内有收发方向，可在加入时指定或运行时调整（见下文）。

### 快速上手

```java
// 1. 创建几个纠缠值
var hp   = EntangledValue.of(100);
var hud  = EntangledValue.of(100);   // HUD 显示
var boss = EntangledValue.of(100);   // Boss 血条显示

// 2. 纠缠在一起（可变参数，一次纠缠多个）
hp.entangle(hud, boss);

// 3. HUD 监听血量变化并刷新
hud.addListener(e -> refreshHud(e.newValue()));

// 4. 血量更新 → HUD、Boss 条自动收到通知（各自值不变，由监听器响应）
hp.set(80);
```

> ⚠️ **注意**：请使用实例方法 `a.entangle(b, c)` 来纠缠多个值。不要定义形如 `EntangledValue.entangle(first, rest...)` 的静态重载——它会与实例可变参数方法 `entangle(others...)` 产生**重载遮蔽**（单参调用 `a.entangle(b)` 会被解析到静态方法上），导致纠缠静默失效。

### 异构纠缠：不同类型同通道

通过 [`Entangled`](reactive/Entangled.java) 无类型根接口，不同 `T` 的值可纠缠到同一通道。常用于「一个数值源 + 一个文本展示」联动：

```java
var num = EntangledValue.of(1);          // Integer
var txt = EntangledValue.of("one");      // String
num.entangle(txt);                       // 异构纠缠（类型不同也无妨）
num.set(2);
// txt 会收到事件（其自身值不变，由监听器决定如何展示）
```

### 事件语义

监听器收到的 [`EntangledEvent`](reactive/EntangledEvent.java) 始终描述**触发者**的值变化：
`source = 触发者`, `oldValue = 触发者旧值`, `newValue = 触发者新值`。

- 用 `event.source() == myValue` 判断是否由自身触发；
- 需要读取自己当前值时调用 `get()`。

---

## EntangledChannel：纠缠通道

通道是纠缠体系的**协调中枢**，独立于任何成员。可直接创建并显式管理成员，适合需要「中心化协调」的场景。

### 两类监听点（双注册）

| 监听点 | 注册方式 | 执行身份 | 触发时机 |
|--------|----------|----------|----------|
| **通道级** | `channel.subscribe(fn)` | 以**通道身份**，能看到整组变化 | 广播阶段①（最先） |
| **成员级** | `value.addListener(fn)` | 以**对象身份**，各成员各自处理 | 广播阶段② |

广播顺序：**成员 set → 通道.broadcast → ① 通道级监听器 → ② 统一遍历所有成员（含触发者），按各自「接收」开关派发**。

> 触发者与其他成员**一视同仁**——统一按「接收」开关决定是否派发事件。无接收权（`receive=false`）的成员（含触发者自身）不会收到事件。

### 成员角色（收发方向）

每个成员在通道内绑定一个 [`Role`](reactive/Role.java)：

| 角色 | 接收他人通知 | 自身 set 广播 | 典型用途 |
|------|:---:|:---:|------|
| `BOTH` | ✅ | ✅ | 默认，双向联动 |
| `SOURCE` | ❌ | ✅ | 传感器：只产出，不消费（自身 set 也不触发自己的监听器） |
| `SINK` | ✅ | ❌ | 显示器：只消费，不产出（自身 set 不产生任何广播） |
| `MUTE` | ❌ | ❌ | 暂时静默（可随时切回） |

### 对象侧与通道侧（对偶 API）

绑定关系可从任一侧发起，结果一致：

| 操作 | 对象侧（`EntangledValue`） | 通道侧（`EntangledChannel`） |
|------|------|------|
| 加入（默认 BOTH） | `value.join(ch)` | `ch.add(value)` |
| 加入为只发 | `value.joinAsSource(ch)` | `ch.addSource(value)` |
| 加入为只听 | `value.joinAsSink(ch)` | `ch.addSink(value)` |
| 离开 | `value.leave()` | `ch.remove(value)` |
| 改角色 | `value.setRole(role)` | `ch.setRole(value, role)` |
| 改接收开关 | `value.setReceiving(b)` | `ch.setReceiving(value, b)` |
| 改发送开关 | `value.setSending(b)` | `ch.setSending(value, b)` |

### 显式通道示例：传感器 → 显示器

```java
var sensor  = EntangledValue.of(0);   // 只发不收
var display = EntangledValue.of(0);   // 只听不发

EntangledChannel ch = new EntangledChannel("hp-channel");
sensor.joinAsSource(ch);     // 或 ch.addSource(sensor)
display.joinAsSink(ch);      // 或 ch.addSink(display)

// 通道级监听器：以通道身份看到整组变化（最先触发）
ch.subscribe(e -> log("通道感知: " + e.source() + " → " + e.newValue()));

sensor.set(5);               // display 收到 5；sensor 自己不收（SOURCE）
```

---

## API 速览

### [`EntangledValue`](reactive/EntangledValue.java)

| 方法 | 说明 |
|------|------|
| `of(v)` / `empty()` | 工厂方法 |
| `get()` / `getOrDefault(def)` / `isPresent()` | 读取 |
| `set(v)` | 设值并广播通知 |
| `mutate(fn)` | 对值（如 `List`/`Map`）原地修改并广播（不受 `distinct` 影响） |
| `setSilently(v)` | 设值但不触发监听器、不广播 |
| `entangle(others...)` | 建立纠缠（可变参数，支持异构） |
| `unentangle()` / `leave()` | 解除纠缠 / 离开通道 |
| `join(ch)` / `joinAsSource(ch)` / `joinAsSink(ch)` | 加入通道（指定角色） |
| `setRole(role)` / `setReceiving(b)` / `setSending(b)` | 运行时调整收发 |
| `getRole()` / `isReceiving()` / `isSending()` | 查询收发状态 |
| `addListener(fn)` / `removeListener(fn)` / `clearListeners()` | 成员级监听器管理 |
| `setDistinct(b)` | 去重：新旧值相等时不通知 |
| `setErrorHandler(fn)` | 自定义监听器异常处理 |
| `isEntangled()` / `isEntangledWith(o)` / `partners()` / `getChannel()` | 关系 / 通道查询 |

### [`EntangledChannel`](reactive/EntangledChannel.java)

| 方法 | 说明 |
|------|------|
| `add(m)` / `addSource(m)` / `addSink(m)` / `add(m, role)` | 加入成员（指定角色） |
| `remove(m)` | 移除成员 |
| `setRole(m, role)` / `setReceiving(m, b)` / `setSending(m, b)` | 调整成员收发 |
| `roleOf(m)` / `isReceiving(m)` / `isSending(m)` | 查询成员收发 |
| `subscribe(fn)` / `unsubscribe(fn)` / `clearSubscribers()` | 通道级监听器管理 |
| `setName(s)` / `setOwner(o)` | 标识 / 归属（不影响逻辑） |
| `size()` / `isEmpty()` / `contains(m)` / `members()` | 成员查询 |

---

## 线程安全

- 所有结构变更（加入 / 离开 / 角色调整 / 通道合并）与广播快照，通过静态锁 `EntangledChannel.LOCK`（整个纠缠体系共用）串行化。
- 通道级监听器列表为 `CopyOnWriteArrayList`；监听器回调在**锁外**执行。
- 单个监听器抛出的 `RuntimeException` 由 `errorHandler` 捕获，**不会**中断其他监听器。

## 注意事项

- **避免回调递归**：不要在监听器中对**同一通道**的值再次 `set`，否则会引发连锁广播、无限递归。
- 纠缠关系基于对象身份（`==`），而非值相等。
- `leave()`（或 `unentangle()`）后该对象不再收发原通道的通知。
- 合并两个通道时，所有成员归入同一通道。

## 运行测试

测试沿用项目惯例（`main` + `check()` 断言，无 JUnit 依赖）：

```bash
mvn -pl jframe_core test-compile -am
java -cp "jframe_core/target/classes;jframe_core/target/test-classes" \
     io.github.JiangHu.jframe.core.data.reactive.EntangledValueTest
```

覆盖 17 类场景、65 个断言：基础读写、通知模式、可传递性、组合并、解纠缠、事件内容、去重、静默更新、异常隔离、监听器增删、关系查询、异构纠缠、容器突变、角色门控（SOURCE/SINK 统一收发）、通道级监听与两阶段广播、收发开关、显式通道 join/add/leave。
