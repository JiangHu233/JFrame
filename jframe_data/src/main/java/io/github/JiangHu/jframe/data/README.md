# jframe_data — 注解驱动的 JSON 持久化工具

通过 `@SaveField` 注解标记类中需要保存的属性，使用 `DataSaver` 将对象序列化为 JSON 文件或从 JSON 加载回对象。

## 核心特性

- **注解驱动**：只需在字段上标注 [`@SaveField`](src/main/java/io/github/JiangHu/jframe/data/annotation/SaveField.java)，无需继承基类、无需实现接口
- **别名支持**：通过 `value` 指定 JSON 键别名（如 `player_name`），解耦 Java 命名与存储格式
- **必需校验**：`required = true` 标记关键字段，加载时缺失即报错，防止数据损坏静默失败
- **自动递归**：`List` / `Set` / `Map` / 数组 / 嵌套对象自动递归处理，嵌套类有 `@SaveField` 则同样按注解规则序列化
- **字段级适配器**：通过 `adapter` 指定 [`SaveFieldAdapter`](src/main/java/io/github/JiangHu/jframe/data/adapter/SaveFieldAdapter.java) 自定义单个字段的 JSON 格式（如坐标压缩为 `"x,y"` 字符串）
- **灵活命名**：显式指定文件名、绝对路径文件、或实现 [`SaveIdentifiable`](src/main/java/io/github/JiangHu/jframe/data/SaveIdentifiable.java) 自动命名
- **Spring 集成**：遵循项目 API/Engine/Config 模式，`@ImportResource` 装配 Bean

---

## 快速开始

### 1. 定义数据类

```java
public class PlayerData {

    @SaveField(value = "player_name", required = true)
    private String name;

    @SaveField
    private int level;

    @SaveField
    private double health;

    // 无参构造器（反序列化时通过反射创建实例）
    public PlayerData() {
    }

    public PlayerData(String name, int level, double health) {
        this.name = name;
        this.level = level;
        this.health = health;
    }

    // getter / setter ...
}
```

### 2. 保存与加载

```java
// 获取 DataSaver（Spring 注入或手动创建）
DataSaver saver = ...;

// 保存 → rootDir/players/steve.json
PlayerData data = new PlayerData("Steve", 42, 19.5);
saver.save(data, "players/steve");

// 加载
PlayerData loaded = saver.load(PlayerData.class, "players/steve");
```

生成的 JSON 文件：

```json
{
  "player_name": "Steve",
  "level": 42,
  "health": 19.5
}
```

---

## @SaveField 注解

标注在**字段**上，标记该字段需要被序列化。只有标注了 `@SaveField` 的**非 static、非 transient** 字段才会被保存。

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `value` | `String` | `""` | JSON 键别名。为空时使用 Java 字段名 |
| `required` | `boolean` | `false` | 加载时是否必需。为 `true` 时 JSON 缺少该键或值为 `null` 将抛出 `DataException` |
| `adapter` | `Class<? extends SaveFieldAdapter>` | `None.class` | 字段级自定义序列化适配器。指定后该字段不再走 Gson 默认序列化，而由适配器的 `toJson`/`fromJson` 控制 JSON 格式 |

### 别名示例

```java
@SaveField("player_name")   // JSON 键为 "player_name"
private String name;

@SaveField                   // JSON 键为 "level"（字段名）
private int level;
```

### required 示例

```java
@SaveField(value = "uuid", required = true)
private String uuid;   // 加载时 JSON 必须包含 "uuid"，否则报错
```

### 哪些字段会被忽略

- 未标注 `@SaveField` 的字段 → 序列化时忽略，反序列化时保持默认值
- `static` 字段 → 始终忽略
- `transient` 字段 → 始终忽略

---

## 容器与嵌套对象

`@SaveField` 可标注在任意类型字段上，`DataSaver` 通过 Gson 递归处理：

```java
public class GuildData {

    @SaveField
    private List<String> members;          // List<String>

    @SaveField
    private Map<String, Integer> stats;    // Map<String, Integer>

    @SaveField
    private Location home;                 // 嵌套对象（Location 也有 @SaveField 字段）

    @SaveField
    private List<Location> waypoints;      // 嵌套对象的 List
}

public class Location {
    @SaveField private String world;
    @SaveField private int x;
    @SaveField private int y;
    public Location() {}
}
```

**递归规则**：
- 容器类型（`List` / `Set` / `Map` / 数组）→ Gson 逐元素递归
- 嵌套对象含 `@SaveField` → 按注解规则序列化
- 嵌套对象无 `@SaveField` → 回退 Gson 默认序列化（序列化全部字段）

---

## 字段级自定义适配器（SaveFieldAdapter）

当某个字段需要**非标准的 JSON 格式**时（如把坐标对象压缩成字符串、自定义枚举编码、第三方类型转换），可通过 `adapter` 属性指定一个 [`SaveFieldAdapter`](src/main/java/io/github/JiangHu/jframe/data/adapter/SaveFieldAdapter.java) 实现类，接管该字段的序列化/反序列化。

### 适配器接口

```java
public interface SaveFieldAdapter<T> {

    /** 将字段值转换为 JsonElement（Gson 树模型） */
    JsonElement toJson(T value);

    /** 从 JsonElement 还原字段值 */
    T fromJson(JsonElement json);

    /** 标记类：@SaveField 默认值，表示不使用适配器 */
    final class None implements SaveFieldAdapter<Object> { ... }
}
```

### 示例：坐标压缩为字符串

默认情况下，`Pos` 对象会被序列化为嵌套对象 `{"x":10,"y":20}`。若希望压缩成 `"10,20"` 字符串以节省空间：

```java
// 1. 定义适配器（需有无参构造器，可 private）
public class PosAdapter implements SaveFieldAdapter<Pos> {

    @Override
    public JsonElement toJson(Pos pos) {
        if (pos == null) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive(pos.x + "," + pos.y);
    }

    @Override
    public Pos fromJson(JsonElement json) {
        if (json.isJsonNull()) {
            return null;
        }
        String[] parts = json.getAsString().split(",");
        return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}

// 2. 在字段上指定 adapter
public class PlayerData {

    @SaveField
    private String name;

    @SaveField(adapter = PosAdapter.class)   // ← 该字段走自定义序列化
    private Pos location;
}
```

生成的 JSON：

```json
{
  "name": "Steve",
  "location": "10,20"
}
```

### 使用要点

- **逐字段独立**：`adapter` 作用于单个字段，同一类中不同字段可指定不同适配器；未指定的字段仍走 Gson 默认序列化
- **无参构造器**：适配器类必须有无参构造器（支持 `private`），框架通过反射实例化一次并缓存
- **无状态**：适配器实例被缓存复用，必须线程安全、无状态
- **树模型 API**：适配器操作 `JsonElement`（Gson 树模型），可返回任意 JSON 结构（基本类型、对象、数组、null）
- **默认值**：不指定 `adapter` 时默认为 `SaveFieldAdapter.None.class`，表示走 Gson 默认序列化

---

## SaveIdentifiable 接口（可选）

实现此接口后，可使用无文件名参数的 [`save(obj)`](src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) 方法，文件名由 `saveKey()` 决定：

```java
public class PlayerProfile implements SaveIdentifiable {

    private final UUID uuid;

    @SaveField
    private String name;

    @Override
    public String saveKey() {
        return uuid.toString();   // → rootDir/<uuid>.json
    }
}
```

```java
saver.save(profile);   // 自动使用 profile.saveKey() 作为文件名
```

> **注意**：未实现 `SaveIdentifiable` 时调用 `save(obj)` 会抛出 `DataException`，需改用 `save(obj, fileName)`。

---

## DataSaver API

[`DataSaver`](src/main/java/io/github/JiangHu/jframe/data/DataSaver.java) 是面向用户的统一入口。

### 根路径（rootDir）

所有相对路径的 save/load 都基于 `rootDir` 解析。两种设置方式：

```java
// 方式一：手动设置
saver.setRootDir(new File("plugins/MyPlugin/data"));

// 方式二：通过 PluginAware 自动绑定（推荐）
// JFrameMain 启动时自动调用 bindPlugin，rootDir = plugin.getDataFolder()
```

### 保存

| 方法 | 文件路径 |
|------|----------|
| `save(obj, "players/steve")` | `rootDir/players/steve.json`（自动建父目录） |
| `save(obj, file)` | 直接使用 `file`（绝对路径） |
| `save(obj)` | `rootDir/<obj.saveKey()>.json`（需实现 `SaveIdentifiable`） |

```java
saver.save(data, "players/steve");              // 相对路径，自动追加 .json
saver.save(data, new File("/abs/path.json"));   // 绝对路径
saver.save(profile);                             // SaveIdentifiable 自动命名
```

### 加载

| 方法 | 说明 |
|------|------|
| `load(Class, "players/steve")` | 从相对路径加载，返回新实例 |
| `load(Class, file)` | 从绝对路径文件加载 |
| `loadInto(target, "players/steve")` | 加载并回填到**已有实例**（不创建新对象） |
| `loadInto(target, file)` | 同上，使用绝对路径 |

```java
// 创建新实例
PlayerData loaded = saver.load(PlayerData.class, "players/steve");

// 回填已有实例（适用于对象已被其他系统持有、无法替换引用的场景）
PlayerData existing = getExistingPlayer();
saver.loadInto(existing, "players/steve");
```

### JSON 字符串互转

不涉及文件 IO，直接在内存中转换：

```java
String json = saver.toJson(data);                    // 对象 → JSON 字符串
PlayerData obj = saver.fromJson(json, PlayerData.class);  // JSON 字符串 → 对象
saver.fromJsonInto(existing, json);                  // JSON 字符串 → 回填已有实例
```

---

## 文件路径规则

| 调用方式 | 解析结果 |
|----------|----------|
| `save(obj, "players/steve")` | `rootDir/players/steve.json` |
| `save(obj, "players/steve.json")` | `rootDir/players/steve.json`（已有扩展名则不重复追加） |
| `save(obj, new File(...))` | 直接使用该 File 对象 |
| `save(obj)`（SaveIdentifiable） | `rootDir/<saveKey()>.json` |

- 相对路径自动追加 `.json` 扩展名（若未包含）
- 父目录不存在时自动创建
- 未设置 `rootDir` 时使用相对路径会抛出 `DataException`

---

## Spring 集成

模块遵循项目的 API / SpringConfig / XML 装配模式。

### Bean 装配（DAG）

[`data-spring.xml`](src/main/resources/data-spring.xml) 定义了单向依赖链：

```
metadataCache → dataSaver
```

- [`MetadataCache`](src/main/java/io/github/JiangHu/jframe/data/core/MetadataCache.java)：扫描 `@SaveField` 字段并缓存反射结果（无依赖）
- [`DataSaver`](src/main/java/io/github/JiangHu/jframe/data/DataSaver.java)：构造器注入 `MetadataCache`，创建配置好的 Gson 实例

### 启用模块

在主插件中导入 [`DataSpringConfig`](src/main/java/io/github/JiangHu/jframe/data/config/DataSpringConfig.java)：

```java
@Import({
    CoreSpringConfig.class,
    DataSpringConfig.class,   // 启用数据模块
    // ...
})
```

### 注入使用

```java
@Component
public class PlayerService {

    private final DataSaver dataSaver;

    public PlayerService(DataSaver dataSaver) {
        this.dataSaver = dataSaver;
    }

    public void savePlayer(PlayerData data) {
        dataSaver.save(data, "players/" + data.getName());
    }
}
```

---

## 异常处理

所有错误统一包装为 [`DataException`](src/main/java/io/github/JiangHu/jframe/data/exception/DataException.java)（`RuntimeException`），包含描述性消息和原始原因：

| 场景 | 异常消息 |
|------|----------|
| required 字段缺失 | `必需字段 'xxx' 在 JSON 中缺失或为 null` |
| 类缺少无参构造器 | `类 xxx 缺少无参构造器，无法实例化` |
| 文件不存在 | `文件不存在: /path/to/file.json` |
| 未设置 rootDir | `保存根路径（rootDir）尚未设置` |
| 未实现 SaveIdentifiable | `对象 xxx 未实现 SaveIdentifiable，无法自动确定文件名` |

```java
try {
    PlayerData data = saver.load(PlayerData.class, "players/steve");
} catch (DataException e) {
    plugin.getLogger().error("加载玩家数据失败: " + e.getMessage(), e);
}
```

---

## 架构概览

```
用户代码
   │
   ▼
DataSaver（公开入口：save / load / loadInto / toJson / fromJson）
   │  持有配置好的 Gson 实例
   ▼
Gson + SaveFieldTypeAdapterFactory
   │  拦截含 @SaveField 的类
   ▼
SaveFieldTypeAdapter（按别名序列化/反序列化，委托 Gson 递归处理容器和嵌套）
   │
   ▼
MetadataCache（扫描 @SaveField 字段，ConcurrentHashMap 缓存反射结果）
```

**设计要点**：
- **元数据缓存**：反射扫描只在首次访问某类时执行，结果缓存在 `ConcurrentHashMap` 中，线程安全
- **Gson 委托**：字段值序列化使用 `Field.getGenericType()` 保留泛型签名，Gson 据此正确递归 `List<T>`、`Map<K,V>` 等容器
- **不可变值对象**：`FieldMetadata` / `ClassMetadata` 构建后不可变，可安全跨线程共享
