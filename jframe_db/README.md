# jframe_db — 零数据库依赖的数据存取模块

JFrame 自身**不持有任何数据库连接**：依赖 JFrame 的插件实现适配器（SPI）并注册，
业务代码即可获得开箱即用的存取能力。换数据库环境 = 重写一个适配器类，业务代码零改动。

```
业务插件 ── DbAPI（门面）── DataManager<T>（列映射 ORM + 三层缓存 + 异步）
                             RegionHandle（跨服共享数据池）
                             RedisStore 直用（防重/互斥/跨服消息）
                                    │ 只调用接口
                                    ▼
                          适配器实现（你写的，如 fapeft 桥接 NsGameBase/WcpeLib）
```

## 快速上手（业务插件视角）

```java
DbAPI db = jframe.getDbAPI();

// 实体：复用 serialize 内核的 @SaveField；@Indexed/@Column/@ChildTable 是列修饰符
public class PlayerData implements SaveIdentifiable {
    @SaveField(required = true) private String uuid;
    @SaveField @Indexed @Column(length = 50)  private String name;   // VARCHAR(50) + 索引
    @SaveField @Column(precision = 10, scale = 2) private BigDecimal balance;
    @SaveField                     private Map<String,Integer> stats; // JSON 列
    @SaveField @ChildTable         private List<Achieve> achieves;    // 自动子表
    @Override public String saveKey() { return uuid; }
}

DataManager<PlayerData> players = db.manage(PlayerData.class)
        .relational(games, "players")            // 必需：store 令牌（或注册名）+ 表名
        .keyColumn("uuid")                        // 可选，默认 "id"
        .cache(cache, Duration.ofMinutes(10))     // 可选：Redis 层——死期必填（无默认值）
        .localCache()                             // 可选：本机层——默认无期限
        .build();                                 // 自动建表 + 幂等补列

players.get("steve");  players.loadOrCreate("a", () -> new PlayerData(...));
players.save(pd);      players.findBy("name", "Steve");   // @Indexed 列查询
players.getAsync("steve").thenAccept(pd -> ...);           // 虚拟线程异步
```

## 适配器编写指南（本文重点）

### 1. 实现两个 SPI（按数据库类型选择）

| SPI | 适配对象 | 方法面 |
|---|---|---|
| `spi.RelationalStore` | MySQL 型库 | `ensureTable(TableDef)` → `RelationalTable`：get / upsert / delete / exists / count / forEach 流式 / getBatch(IN) / findBy / findByIn |
| `spi.RedisStore` | Redis 实例（或 db 编号） | get / set(可带TTL) / setnx / delete / expire / publish / subscribe |

可选能力：`spi.TransactionalStore`（`inTransaction(work)`）——实现了则
`@ChildTable`/`@RelationTable` 的多行写入自动包事务；**未实现时存在半写风险**
（主行 upsert 后子表重写中途失败会留下部分数据），强烈建议实现。

### 2. 实现约定（务必遵守）

- **参数绑定**：所有带值方法必须参数绑定执行（PreparedStatement / MyBatis `#{}`），
  **禁止拼接值**（SQL 注入 + 规范红线）
- **幂等建表**：`ensureTable` 表不存在则建、缺列则补（幂等 ALTER + 异常忽略的线上实践）；
  jframe_db 在 manager build 时调用
- **逻辑类型 → 方言 DDL**：jframe 不出现 JDBC 类型，列以 `ColumnType` 表达：

| ColumnType | Java 值 | MySQL 参考 |
|---|---|---|
| STRING | String | 带 length → VARCHAR(n)；无 length → TEXT；**索引列无 length → VARCHAR(191)**（utf8mb4 索引长度惯例） |
| TEXT | String | TEXT / mediumtext |
| INT / LONG / DOUBLE / BOOLEAN | Integer / Long / Double / Boolean | INT / BIGINT / DOUBLE / TINYINT(1) |
| DECIMAL | BigDecimal | DECIMAL(precision, scale)（缺省 10/2） |
| JSON | String（JSON 文本） | JSON / mediumtext |

- **单行 upsert 原子**：主表 upsert 必须是数据库单行原子操作（`INSERT ... ON DUPLICATE KEY UPDATE`）
- **subscribe 线程模型**：回调在适配器的订阅线程（如 Jedis 独立订阅连接推送线程）执行，
  不要在回调里做长阻塞；回服务器主线程由业务自行调度
- **保留前缀**：`__jframe_db__` 为框架保留（缓存键/频道/区域键），适配器与业务禁用同前缀

### 3. 注册与生命周期

```java
public class MyPlugin extends PluginBase {
    private JFrameMain jframe;

    @Override
    public void onEnable() {
        jframe = (JFrameMain) getServer().getPluginManager().getPlugin("JFrame");
        DbAPI db = jframe.getDbAPI();

        // 无名注册：返回实例即令牌，仅本插件用
        RelationalStore games = db.registerRelational(new MyMysqlStore(...));
        // 命名注册：开放给其他插件按名查找（跨插件共享通道）
        db.registerRedis("主缓存", new MyRedisStore(...));
    }

    @Override
    public void onDisable() {
        // 注销即清理：句柄失效化、本地缓存清空、区域 release、广播退订
        db.unregister(games);
        db.unregister("主缓存");
        // 注意：不会清共享 Redis 缓存（多实例共享，单台无权替全服释放——释放保底靠死期）
    }
}
```

`plugin.yml`：`depend: [JFrame]`；适配器插件须以 **JDK 24+ 工具链编译**
（jframe 字节码版本 68，JDK 17 的 javac 读不了；`--release` 可仍为低版本）。
类加载器：SPI 接口由 JFrame 加载器加载，适配器 depend JFrame 后类型自然可见。

### 4. TTL 与释放权最佳实践

> **删除是优化，过期才是释放。** 显式删除不免疫崩溃（进程死在删除前 key 悬空）；
> 到期自灭是唯一对崩溃免疫的机制。

- **凡缓存必带 TTL**：TTL 是绕过框架的外部写（OA/运维直改正本）时旧值的存活上限，
  也是悬空数据的最终一致保底
- 框架缓存键（`__jframe_db__:{表}:{键}`）死期必填，jframe 不代填默认值
- Redis 永久 key 仅用于「它就是正本且由持有者显式管理删除」的小体量数据
- Redis 内存上限与淘汰策略（`maxmemory-policy`）属服务端运维配置，非框架职责
- 多实例防重一行化：`redis.setnx("birthday:"+year+":"+player, 服名, 7*24*3600)`

### 5. 心跳键模式（锁/选主/子服在线注册表）

需要「随持有者生灭」的独占协调时，不要发明机制，用现成组合：

```java
// 认领（setnx 抢占，30 秒死期）
boolean got = redis.setnx("lock:shop-settle", myServerId, 30);
// 保活（周期任务续命；持有者崩溃 → 无人续 → 30 秒自动开放）
redis.expire("lock:shop-settle", 30);
// 释放（正常路径顺手删）
redis.delete("lock:shop-settle");
```

### 6. 数据区域（跨服共享数据池）

```java
RegionHandle pool = db.region("匹配池", cache, Duration.ofMinutes(5)).claim(); // 死期必填
pool.put("room:123", roomObj);      // 多插件/多服可同时认领（共享语义）
Room r = pool.get("room:123", Room.class);
pool.release();                     // 末位 release 立即删键；全部崩溃 → 无人续死期 → 整片蒸发
```

容量边界：单键 JSON 模式适合**条目百级、单值 KB 级**（匹配池/房间状态/共享开关）；
万条级大池会 bigkey（全量读写放大），需要时另行扩展多键模式。

### 7. 已知限制（v1）

- 整行 upsert 为 last-write-wins：多实例并发写同主键存在丢更新，规避模式 =
  玩家维度单写者 / Redis 原子 / 适配器专业 API
- `@RelationTable` 不级联保存 ref 实体（各自存各自的表）；ref 实体必须扁平
  （自身不得再含 @ChildTable/@RelationTable）
- 自由 WHERE/JOIN/分页查询不在 SPI（无法泛化），走适配器方自己的专业 API
- clearCache 只能删除本进程已知键（SPI 无前缀枚举），未知键靠死期自然回收

## 架构与依赖

```
jframe_core ← jframe_serialize ← jframe_db（本模块，不依赖 jframe_file/jframe_async）
```

包结构：根 = 对外 API（DbAPI / DataManager / RegionHandle）；
`annotation`（@Indexed/@Column/@ChildTable/@RelationTable）；`spi`（适配器契约）；
`builder`（链式构建）；`internal`/`mapping`/`cache`/`support`（内部实现与内存假实现）。

内存假实现（`support`）供测试与演示复用：`InMemoryRelationalStore` /
`InMemoryRedisStore`（见 jframe_example 的 `/dbdemo`）。
