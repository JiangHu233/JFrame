# 数据持久化 — 开发者/维护者指南 (DEVELOPER.md)

> 本文档面向**框架的维护者和二次开发者**，目的是让你 100% 理解每个类的内部实现、设计决策、线程安全模型，
> 并能在修改代码时知道**改哪里、为什么改、改了会影响什么**。
>
> 如果你只是想**使用**这个框架，请看 [README.md](README.md)。

---

## 目录

- [1. 设计哲学与核心问题](#1-设计哲学与核心问题)
- [2. 架构总览](#2-架构总览)
- [3. 运行时数据流（完整调用链）](#3-运行时数据流完整调用链)
- [4. 类逐一剖析](#4-类逐一剖析)
- [5. 线程安全分析](#5-线程安全分析)
- [6. 生命周期管理](#6-生命周期管理)
- [7. 扩展点](#7-扩展点)
- [8. 已知限制与陷阱](#8-已知限制与陷阱)
- [9. 修改指南（改代码前必读）](#9-修改指南改代码前必读)

---

## 1. 设计哲学与核心问题

### 1.1 与手写序列化的对比

Nukkit 插件持久化数据的传统做法是**手写 YAML/JSON 读写**：手动 `set("level", data.level)`、手动 `getInt("level")`，
字段一多就变成一长串易错的样板代码，且存储格式（YAML 键名）与 Java 字段名耦合，重构时极易遗漏。

```java
// 传统写法：每个字段手写读写
Config c = new Config(file, Config.JSON);
c.set("player_name", data.name);   // 手动 set
c.set("level", data.level);
c.save();

// 加载
data.name = c.getString("player_name");
data.level = c.getInt("level");
// ... 几十个字段
```

本框架是**注解驱动 + 委托 Gson** 的：字段标注 [`@SaveField`](annotation/SaveField.java) 即声明"我要保存它"，
框架自动完成反射读取、别名映射、容器递归、JSON 序列化，调用方只需 `saver.save(obj)` / `saver.load(C.class, "f")`。

#### 架构与编程模型对比

| 维度 | 手写 Config | 本框架 |
|------|-------------|--------|
| 声明方式 | 手写 set/get 样板 | 字段标注 `@SaveField` |
| 键名映射 | 手动维护字符串 | `value` 别名声明式 |
| 容器/嵌套 | 手动遍历逐元素 | Gson 自动递归 |
| 必需校验 | 手动 `if (c.exists(...))` | `required = true` 声明式 |
| 缺失防护 | 静默返回默认值，数据损坏难发现 | required 缺失即抛异常 |
| 自定义格式 | 手写转换 | `adapter` 字段级适配器 |

### 1.2 本框架的核心设计思路

**"注解即契约"** —— 只有标注了 `@SaveField` 的非 static、非 transient 字段才参与序列化。
未标注的字段在序列化时被忽略（不出现在 JSON 中），在反序列化时保持对象初始值。这让"哪些字段要存"成为显式、可审计的声明。

**"扫描一次，缓存反射"** —— [`MetadataCache`](core/meta/MetadataCache.java) 首次访问某类时反射扫描其类层次结构，
构建不可变的 [`ClassMetadata`](core/meta/ClassMetadata.java) / [`FieldMetadata`](core/meta/FieldMetadata.java) 并缓存到 `ConcurrentHashMap`。
后续每次序列化/反序列化只查缓存，零反射开销。

**"拦截 + 委托 Gson"** —— 通过注册 [`SaveFieldTypeAdapterFactory`](core/engine/SaveFieldTypeAdapterFactory.java) 到 Gson，
拦截"含 `@SaveField` 字段的类"用自定义 [`SaveFieldTypeAdapter`](core/engine/SaveFieldTypeAdapter.java) 处理（按别名序列化），
其余类型（容器、基本类型、无注解类）**原样委托 Gson 默认适配器**。这样既获得注解控制力，又复用 Gson 成熟的容器/泛型递归能力。

**"单向 DAG，无循环依赖"** —— `MetadataCache`（无依赖）← `DataSaver`（依赖 Cache）。
两层职责清晰，Spring 可按顺序构造。

### 1.3 为什么是 3 个注解属性 + 1 个接口？

| 元素 | 职责 | 为什么独立 |
|------|------|-----------|
| [`@SaveField`](annotation/SaveField.java) `value` | JSON 键别名 | 解耦 Java 命名与存储格式 |
| [`@SaveField`](annotation/SaveField.java) `required` | 必需校验 | 防止关键字段缺失导致数据损坏静默失败 |
| [`@SaveField`](annotation/SaveField.java) `adapter` | 字段级自定义序列化 | 接管单个字段的 JSON 格式（如坐标压缩为 `"x,y"`） |
| [`SaveIdentifiable`](SaveIdentifiable.java) | 动态文件命名 | 把"文件名策略"从静态注解迁移到动态方法，支持同类型多实例各自命名 |

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户代码层                                    │
│                                                                     │
│  @SaveField 标注的数据类 + DataSaver.save/load/loadOrSave/exists    │
│     ↓ saver.save(obj, "players/steve")                              │
├─────────────────────────────────────────────────────────────────────┤
│                         公开入口层 (L1)                               │
│                                                                     │
│  DataSaver (implements PluginAware)                                 │
│  ├── save/load/loadInto/exists/loadOrSave：路径解析 + 文件 IO         │
│  ├── toJson/fromJson/toYaml/fromYaml：委托 Gson + 格式层序列化       │
│  ├── setFormat/withFormat：存储格式切换（JSON / YAML）               │
│  ├── sub/parent/root/forPlugin：路径导航与跨插件隔离                  │
│  └── 持有配置好的 Gson 实例（注册了 TypeAdapterFactory）              │
│     ↓ serialize(obj)：gson.toJsonTree(obj) → codec.write(JsonElement)│
│     ↓ parseToTree(content)：codec.read(content) → JsonElement       │
├─────────────────────────────────────────────────────────────────────┤
│                         格式层 (L1.5)                                │
│                                                                     │
│  SaveFormatCodec（格式存取器接口：write(JsonElement)/read→JsonElement）│
│  ├── JsonCodec（JsonElement ↔ JSON 文本，Gson 直出/直析）            │
│  └── YamlCodec（JsonElement ↔ YAML 文本，经 SnakeYAML 驱动）         │
│  SaveValue（适配器字段级通用数据模型：Null/Bool/Num/Str/List/Map）    │
│  SaveValueBridge（JsonElement ↔ SaveValue 字段边界转换桥）           │
│  SaveFormat（枚举：JSON / YAML，含扩展名 + 关联 codec）               │
│     ↓                                                                │
├─────────────────────────────────────────────────────────────────────┤
│                         序列化引擎层 (L2)                             │
│                                                                     │
│  SaveFieldTypeAdapterFactory (Gson TypeAdapterFactory)               │
│  ├── create()：询问 cache，类含 @SaveField → 返回自定义适配器         │
│  └── 否则返回 null → Gson 默认反射适配器                              │
│     ↓                                                                │
│  SaveFieldTypeAdapter<T> (Gson TypeAdapter)                          │
│  ├── write()：遍历 ClassMetadata.fields()，按别名写值                │
│  │     └── 字段值再次 gson.toJson(value, genericType) 委托递归        │
│  └── read()：解析为 JsonObject，按别名取值，required 校验，反射 set   │
│     ↓                                                                │
│  MetadataCache（扫描 @SaveField 字段，ConcurrentHashMap 缓存反射）    │
└─────────────────────────────────────────────────────────────────────┘
```

**序列化宏观路线**（用户可见层面）：

```
对象属性 ←→ [SaveValue 通用数据，仅当字段绑定 SaveFieldAdapter] ←→ 格式存取器 ←→ 文本(json/yml)
```

- 未绑定适配器的属性**直接往返**于格式存取器，不经过任何中间数据
- `SaveFieldAdapter` 只负责"内存数据 ↔ 通用数据"（字段边界），不感知最终文件格式
- `SaveFormatCodec` 只负责"树模型 ↔ 文件文本"，不感知 Java 对象；主干货币为 Gson `JsonElement`（框架内部实现细节，用户 API 与适配器均不接触）
- 两个 JSON/YAML codec 是**同级对称**实现，新增格式只需再写一个 codec，无需触碰序列化引擎

### 依赖关系图

```
MetadataCache (无依赖)
      ↑
DataSaver (构造注入 MetadataCache，构建 Gson 时注入 SaveFieldTypeAdapterFactory(cache))
```

> **关键：** 依赖是单向的。`MetadataCache` 不知道 `DataSaver` 的存在。
> `DataSaver` 持有 `MetadataCache` 引用，构建 Gson 时把 cache 传给 `SaveFieldTypeAdapterFactory`，
> Gson 在序列化任意类型时回调工厂询问"这个类要不要用自定义适配器"。

---

## 3. 运行时数据流（完整调用链）

### 3.1 启动阶段（Spring 容器初始化）

```
Spring 容器启动
    │
    ├── 创建 MetadataCache Bean（无依赖，最先创建）
    │
    └── 创建 DataSaver Bean
          └── 构造注入 MetadataCache
          └── buildGson(cache)：
                ├── new SaveFieldTypeAdapterFactory(cache)
                └── GsonBuilder.registerTypeAdapterFactory(factory)
                      .setPrettyPrinting()
                      .create()
```

### 3.2 保存阶段（save 热路径）

```
用户调用 saver.save(playerData, "players/steve")
    │
    ├── resolveRelativeFile("players/steve")
    │     └── rootDir + "players/steve" + ".json" → File
    │
    └── save(obj, file)
          ├── toJson(obj) → gson.toJson(obj)
          │     │
          │     ↓ Gson 询问 SaveFieldTypeAdapterFactory.create(PlayerData)
          │     │
          │     ├── cache.get(PlayerData.class)
          │     │     └── 首次：scan() 反射扫描类层次 → ClassMetadata（缓存）
          │     │     └── 后续：直接返回缓存
          │     │
          │     ├── 有 @SaveField 字段 → 返回 SaveFieldTypeAdapter
          │     │     └── write()：
          │     │           ├── 遍历 ClassMetadata.fields()
          │     │           ├── 对每个 FieldMetadata：
          │     │           │   ├── field.get(obj) 反射读取值
          │     │           │   ├── 有 adapter → adapter.toSave(value) → SaveValue
          │     │           │   └── 无 adapter → gson.toJson(value, field.getGenericType())
          │     │           │         └── 容器/嵌套对象递归（再次命中工厂）
          │     │           └── out.name(alias).value(...)
          │     │
          │     └── 返回 JSON 字符串（pretty printing）
          │
          └── writeStringToFile(json, file)
                └── Files.createDirectories(parent) + Files.writeString(UTF_8)
```

### 3.3 加载阶段（load 热路径）

```
用户调用 saver.load(PlayerData.class, "players/steve")
    │
    ├── resolveRelativeFile(...) → File
    │
    └── load(clazz, file)
          ├── file.exists() == false → 返回 null（不抛异常，便于区分"缺失"与"读取失败"）
          ├── readStringFromFile(file)
          │     └── Files.readString(UTF_8)
          │
          └── fromJson(json, clazz) → gson.fromJson(json, clazz)
                │
                ↓ Gson 询问工厂 → 返回 SaveFieldTypeAdapter
                │
                └── read()：
                      ├── JsonParser.parseReader → JsonObject（树模型）
                      ├── createInstance() → 无参构造反射创建实例
                      └── 遍历 ClassMetadata.fields()：
                            ├── jsonObject.get(alias) 取值
                            ├── null/缺失 + required → 抛 DataException
                            ├── null/缺失 + 非 required → 跳过（保持初始值）
                            ├── 有 adapter → adapter.fromSave(SaveValueBridge.fromGson(element))
                            └── 无 adapter → gson.fromJson(element, genericType)
                            └── field.set(instance, value) 反射回填
```

### 3.4 加载或新建阶段（loadOrSave）

```
用户调用 saver.loadOrSave(Config.class, "config", Config::new)   ← 显式文件名
    │
    ├── resolveRelativeFile("config") → File
    │
    └── loadOrSave(clazz, file, supplier)
          ├── file.exists() == true → load(clazz, file)   ← 走 3.3 加载路径
          │
          └── file.exists() == false（首次运行）：
                ├── supplier.get() → 默认值（惰性，仅此分支调用）
                ├── null 校验 → 抛 DataException
                ├── save(defaultValue, file)               ← 走 3.2 保存路径，落盘
                └── return defaultValue

用户调用 saver.loadOrSave(new PlayerData(uuid, ...))   ← 自动命名
    │   （T 须实现 SaveIdentifiable）
    │
    └── loadOrSave(defaultObj)
          ├── null 校验 → 抛 DataException
          ├── resolveSaveKey(defaultObj) → key
          ├── resolveRelativeFile(key) → File
          ├── file.exists() == true → load(defaultObj.getClass(), file)   ← 走 3.3 加载路径
          └── file.exists() == false → save(defaultObj, file) + return defaultObj
```

> ⚠️ **两种重载的参数形式不同**：显式文件名重载用 `Supplier`（惰性，文件存在则不调 supplier）；
> 自动命名重载直接传默认对象（因为确定文件名必须先读取 `saveKey()`，`Supplier` 在此无法提供惰性收益）。

### 3.5 文件存在性判断（exists）

```
saver.exists("players/steve")      → resolveRelativeFile(...).exists()
saver.exists(file)                 → file != null && file.exists()
saver.exists(saveIdentifiableObj)  → resolveSaveKey(obj) → exists(key)
```

---

## 4. 类逐一剖析

### 4.1 [`MetadataCache`](core/meta/MetadataCache.java) — 反射扫描 + 缓存

**职责**：首次访问某类时反射扫描其类层次结构，提取 `@SaveField` 字段，构建并缓存元数据。

**核心方法**：
- [`get(clazz)`](core/meta/MetadataCache.java:53) — `ConcurrentHashMap.computeIfAbsent`，保证同类只扫描一次
- [`scan(clazz)`](core/meta/MetadataCache.java:63) — 反射扫描实现

**扫描规则**（[`scan()`](core/meta/MetadataCache.java:63)）：
1. 从当前类向上遍历到 `Object`（不含），收集每层 `getDeclaredFields()`
2. 反转顺序：**父类字段在前，子类字段在后**（声明顺序）
3. 只保留标注了 `@SaveField` 的字段
4. 跳过 `static` 和 `transient` 修饰的字段
5. 每个字段 `setAccessible(true)`（支持 private 字段）

**Optional 包装的妙用**：缓存 `Optional<ClassMetadata>` 而非 `ClassMetadata`。
"无 `@SaveField` 字段"的判断也只做一次反射扫描（缓存空 Optional），避免每次序列化都反射。

> **不可变**：`ClassMetadata` / `FieldMetadata` 构建后不变，缓存后可安全跨线程共享。

---

### 4.2 [`ClassMetadata`](core/meta/ClassMetadata.java) / [`FieldMetadata`](core/meta/FieldMetadata.java) — 不可变值对象

**[`ClassMetadata`](core/meta/ClassMetadata.java:17)**：持有目标 `Class` + 该类（含父类）所有 `@SaveField` 字段的 `List<FieldMetadata>`。
构造时 `List.copyOf` 防御性拷贝，保证不可变。

**[`FieldMetadata`](core/meta/FieldMetadata.java:22)**：单个字段的反射元数据，缓存四样东西避免重复反射：
- `field` — 反射 `Field` 引用（已 `setAccessible`）
- `alias` — JSON 键别名（`value()` 为空时用字段名）
- `required` — 是否必需
- `adapter` — 字段级自定义适配器实例（`None` 时为 null）

**适配器实例化**（[`resolveAdapter()`](core/meta/FieldMetadata.java:59)）：
扫描时通过无参构造器反射实例化适配器（支持 private 构造器），缓存复用。
因此适配器实现**必须无状态、线程安全**。

---

### 4.3 [`SaveFieldTypeAdapterFactory`](core/engine/SaveFieldTypeAdapterFactory.java) — Gson 拦截入口

**职责**：注册到 Gson 后，Gson 序列化/反序列化任何类型时都会先询问本工厂。

**[`create()`](core/engine/SaveFieldTypeAdapterFactory.java:61) 决策**：
- `cache.get(type)` 非空（类含 `@SaveField`）→ 返回 `SaveFieldTypeAdapter`（`.nullSafe()` 包装）
- `cache.get(type)` 空（无 `@SaveField`）→ 返回 `null`，交由 Gson 默认反射适配器

> **这是递归的枢纽**：适配器处理字段值时调用 `gson.toJson(value, fieldType)`，
> Gson 会再次询问工厂 —— 容器元素、嵌套对象若含 `@SaveField` 就递归命中本工厂。

---

### 4.4 [`SaveFieldTypeAdapter`](core/engine/SaveFieldTypeAdapter.java) — 序列化/反序列化核心

**职责**：仅序列化 `@SaveField` 字段，使用别名作为 JSON 键。

**[`write()`](core/engine/SaveFieldTypeAdapter.java:80)（序列化）**：
```
beginObject()
  for each FieldMetadata:
    fieldValue = field.get(obj)                      // 反射读取
    name(alias)
    if hasAdapter: gson.toJson(SaveValueBridge.toGson(adapter.toSave(value))) // 自定义格式
    else:          gson.toJson(value, genericType)    // 委托 Gson（保留泛型）
endObject()
```

**[`read()`](core/engine/SaveFieldTypeAdapter.java:98)（反序列化）**：
```
jsonObject = JsonParser.parseReader(in).asJsonObject()
instance = createInstance()                          // 无参构造
  for each FieldMetadata:
    element = jsonObject.get(alias)
    if null/缺失:
      if required → throw DataException              // 关键字段缺失防护
      else continue                                  // 保持初始值
    value = hasAdapter ? adapter.fromSave(SaveValueBridge.fromGson(element))
                       : gson.fromJson(element, genericType)
    field.set(instance, value)                       // 反射回填
return instance
```

**关键设计**：使用 [`Field.getGenericType()`](core/engine/SaveFieldTypeAdapter.java:91) 而非 `getType()`，
保留泛型签名（如 `List<String>`），Gson 据此正确选择元素适配器，避免 `List<Object>` 退化为 `List<LinkedTreeMap>`。

**实例化**（[`createInstance()`](core/engine/SaveFieldTypeAdapter.java:196)）：通过 `getDeclaredConstructor()` + `setAccessible(true)`，
支持非 public 类（包级私有、私有嵌套类）。缺少无参构造器时抛出带提示的 `DataException`。

---

### 4.5 [`DataSaver`](DataSaver.java) — 公开入口

**职责**：面向用户的统一入口，封装路径解析、文件 IO、Gson 委托、路径导航。

**四类公开 API**：

| 分类 | 方法 | 说明 |
|------|------|------|
| 保存 | [`save(obj, String)`](DataSaver.java:283) / [`save(obj, File)`](DataSaver.java:294) / [`save(obj)`](DataSaver.java:305) | 相对路径 / 绝对路径 / SaveIdentifiable 自动命名 |
| 加载 | [`load(Class, String)`](DataSaver.java:319) / [`load(Class, File)`](DataSaver.java:332) / [`loadInto(...)`](DataSaver.java:349) | 新实例 / 回填已有实例 |
| 存在性 | [`exists(String)`](DataSaver.java:376) / [`exists(File)`](DataSaver.java:386) / [`exists(Object)`](DataSaver.java:402) | 加载前探测，避免抛异常 |
| 文件解析 | [`fileOf(Object)`](DataSaver.java:427) | 通过 `saveKey()` 反查对象对应的存储文件（绝对路径） |
| 加载或新建 | [`loadOrSave(Class, String, Supplier)`](DataSaver.java:461) / [`loadOrSave(Class, File, Supplier)`](DataSaver.java:478) / [`loadOrSave(T)`](DataSaver.java:526) | 不存在则用默认值创建并落盘；第三种直接传默认对象、依其 `saveKey()` 自动命名（须 `SaveIdentifiable`） |
| 字符串互转 | `toJson` / `fromJson` / `fromJsonInto` | 不涉及文件 IO |

**路径导航**（[`sub`](DataSaver.java) / [`parent`](DataSaver.java) / [`root`](DataSaver.java)）：
- `sub(first, more)` 拼接子目录，返回**子保存器**（共享 cache/gson，持有父级引用，**继承父级格式**）
- 子保存器的 `setRootDir()` 会抛异常 —— 根路径是固定基准点，不可被 sub 篡改
- `forPlugin(plugin)` 创建**独立根保存器**，根路径为插件数据目录，跨插件隔离（**继承父级格式**）

**存储格式**（[`setFormat`](DataSaver.java) / [`withFormat`](DataSaver.java)）：
- `setFormat(SaveFormat)` 修改当前保存器的默认格式，影响后续所有 save/load（文件扩展名随之变化）
- `withFormat(SaveFormat)` 返回使用指定格式的**独立保存器**，不改变当前保存器的格式状态（按调用覆盖）
- `sub()` / `forPlugin()` 创建的保存器会**继承**父级的 `format` 字段

**内部工具方法**：
- [`serialize(obj)`](DataSaver.java) — 格式感知序列化：`gson.toJsonTree(obj)` → `SaveValueBridge.fromGson` → `format.getCodec().write` 文本
- [`parseToValue(content)`](DataSaver.java) — 格式感知解析：文本 → `SaveValue` 树（`format.getCodec().read`）
- [`fromValue(value, clazz)`](DataSaver.java) — 从 `SaveValue` 树反序列化（`SaveValueBridge.toGson` 后交 Gson，共用核心）
- [`fromValueInto(target, value)`](DataSaver.java) — 从 `SaveValue` 树回填已有实例（`fromJsonInto`/`fromYamlInto`/`loadInto` 共用）
- [`resolveRelativeFile()`](DataSaver.java) — 相对文件名 → File（按格式追加 `.json` / `.yml`，智能识别已有扩展名）
- [`hasKnownExtension()`](DataSaver.java) — 判断文件名是否已含 `.json` / `.yml` / `.yaml`
- [`resolveSaveKey()`](DataSaver.java) — SaveIdentifiable → 文件名（统一校验，供 `save(obj)` 与 `exists(obj)` 共用）
- [`writeStringToFile()`](DataSaver.java) — 写文件（自动建父目录）
- [`readStringFromFile()`](DataSaver.java) — 读文件（不存在抛异常）

---

### 4.6 [`@SaveField`](annotation/SaveField.java) — 字段级注解

**三个属性**：
- `value` — JSON 键别名（默认 `""` = 字段名）
- `required` — 加载时是否必需（默认 `false`）
- `adapter` — 字段级自定义适配器类（默认 `SaveFieldAdapter.None`）

`@Target(FIELD)` + `@Retention(RUNTIME)`，仅字段、运行时保留。

---

### 4.7 [`SaveFieldAdapter`](adapter/SaveFieldAdapter.java) — 字段级自定义序列化

**职责**：为单个字段提供自定义的 `toSave` / `fromSave`，操作框架自有的 [`SaveValue`](value/SaveValue.java) 中间数据模型（而非 Gson/SnakeYAML 类型）。

**中间数据定位**：适配器只负责"内存数据 ↔ 通用中间数据"的转换，不感知最终文件格式——因此同一个适配器在 JSON 与 YAML 两种格式下通用。"中间数据 ↔ 文件文本"由格式层（`SaveFormatCodec`）完成。

**实例化要求**：必须有无参构造器（可 private），扫描时反射实例化并缓存复用 → **必须无状态、线程安全**。

**[`None`](adapter/SaveFieldAdapter.java:74)**：占位标记类，作为 `adapter` 默认值。框架遇到它回退 Gson 默认序列化，
调用其方法会抛 `UnsupportedOperationException`（不应被调用）。

---

### 4.8 [`SaveIdentifiable`](SaveIdentifiable.java) — 动态文件命名

**职责**：可选接口，实现后可用无参 `save(obj)` / `exists(obj)`，文件名由 [`saveKey()`](SaveIdentifiable.java:52) 决定。

**设计动机**：把"文件命名策略"从静态注解迁移到动态方法，
使同类型的多个实例各自生成不同文件名（如按 UUID 区分玩家数据）。

---

### 4.9 [`SaveFormat`](SaveFormat.java) — 存储格式枚举

**职责**：定义支持的存储格式，每种格式绑定一个文件扩展名与一个 [`SaveFormatCodec`](core/format/SaveFormatCodec.java) 实例。

| 枚举值 | 扩展名 | codec | 说明 |
|--------|--------|-------|------|
| `JSON` | `.json` | [`JsonCodec`](core/format/JsonCodec.java) | 默认格式，Gson 驱动 |
| `YAML` | `.yml` | [`YamlCodec`](core/format/YamlCodec.java) | SnakeYAML 驱动 |

**设计要点**：格式与扩展名、codec 绑定（`getCodec()` 获取），切换格式时文件名自动变化、文本转换自动切换，保证 save 与 load 的路径一致。两个 codec 是**同级对称**实现，无主次之分。

---

### 4.10 [`SaveValue`](value/SaveValue.java) / [`SaveValueBridge`](core/engine/SaveValueBridge.java) — 适配器通用数据层

**[`SaveValue`](value/SaveValue.java)**：框架自有的密封接口通用数据模型，6 种节点对应 6 类数据（Null / Bool / Num / Str / List / Map）。**仅出现在绑定 `SaveFieldAdapter` 字段的边界处**，是适配器契约的货币——未绑定适配器的属性不经过此模型。

- 标量节点为不可变 record；List / Map 节点为可变构建器（`add` / `put` 链式构建，LinkedHashMap 保序）
- 工厂方法：`of(...)` / `ofNull()` / `list()` / `map()`；判断：`isStr()` / `isMap()` 等；取值：`asString()` / `asInt()` / `asList()` / `asMap()` 等（类型不匹配抛 `DataException`）
- **零第三方依赖**：用户 adapter 只接触此模型，不感知 Gson / SnakeYAML

**[`SaveValueBridge`](core/engine/SaveValueBridge.java)**：`SaveValue` ↔ Gson `JsonElement` 双向转换桥，**字段边界组件**（仅供 `SaveFieldTypeAdapter` 在适配器字段边界使用）：

- `fromGson(JsonElement) → SaveValue`：read 路径，Gson 树转通用数据交给 adapter
- `toGson(SaveValue) → JsonElement`：write 路径，adapter 产出的通用数据转回 Gson 树继续走 Gson 管线

**为什么需要通用数据**：让 `SaveFieldAdapter` 与文件格式彻底解耦——adapter 只做"内存 ↔ 通用数据"（字段边界），codec 只做"树模型 ↔ 文本"，两者可独立演进、自由组合。

---

### 4.11 [`SaveFormatCodec`](core/format/SaveFormatCodec.java) / [`JsonCodec`](core/format/JsonCodec.java) / [`YamlCodec`](core/format/YamlCodec.java) — 格式存取器

**[`SaveFormatCodec`](core/format/SaveFormatCodec.java)**：格式层接口，定义同级对称的两个操作（主干货币为 Gson `JsonElement` 树模型——框架内部实现细节，用户 API 与适配器均不接触）：

- `write(JsonElement) → String`：树模型 → 文本
- `read(String) → JsonElement`：文本 → 树模型

**[`JsonCodec`](core/format/JsonCodec.java)**：JSON 实现，Gson 直出/直析（pretty printing、`serializeNulls` 保证 null 键往返一致）。

**[`YamlCodec`](core/format/YamlCodec.java)**：YAML 实现，经 **Java 原生对象**（Map / List / 标量）作为桥梁（SnakeYAML API 只接受 Java 原生对象，此桥为硬性要求）：

```
JsonElement 树 ←→ Java Map/List/标量 ←→ YAML 文本（SnakeYAML）
```

- 输出：BLOCK 样式、缩进 2、width=MAX_VALUE 不折行
- 数字还原：Gson 的 `LazilyParsedNumber` 统一还原为 Integer / Long / Double，确保整数不带小数点
- 字符串自动加引号（如 `flag: 'yes'`），保证 write → read 往返类型一致
- **YAML 规范**：SnakeYAML 2.x 默认 resolver 沿用 YAML 1.1 隐式类型规则（手写配置中的裸 `yes`/`no`/`on`/`off` 解析为布尔）

**线程安全**：JsonCodec / YamlCodec 均无共享可变状态；YamlCodec 每次调用创建独立的 SnakeYAML `Yaml` 实例（其非线程安全），因此两个 codec 均线程安全。

**设计权衡——JsonElement 主干 + SaveValue 字段边界（JSON / YAML 对称分析）**：

两条格式的完整转换链（write 方向，未绑定适配器字段）：

```
JSON：对象 → JsonElement → JSON 文本   （2 跳，理论最短 2 跳 ✓）
YAML：对象 → JsonElement → Java 原生对象 → YAML 文本 （3 跳，理论最短 3 跳 ✓）
```

- **两条路径均已达理论最短**：JSON 由 Gson 直出；YAML 的 Java 对象桥是 SnakeYAML API 的硬性要求，无法再省
- **绑定适配器字段**：在字段边界额外经过 `SaveValue`（对象属性 ↔ SaveValue ↔ JsonElement），这是适配器契约的必要开销，且与格式无关——同一段代码对 JSON/YAML 通用
- **无按格式特判**：`DataSaver` 通过 `format.getCodec()` 单一入口调度，无 `if (format == JSON)` 分支——新增格式零成本，这正是同级对称架构的核心收益

**等价性断言**：`JsonElement` 是两种格式 `read` 的统一出参，`DualFormatEquivalenceTest` 直接比较两棵树（Gson `JsonPrimitive.equals` 对数值按值比较，`LazilyParsedNumber` 与 `Integer` 表示差异不影响断言）。

> 若未来出现大文件性能需求，可在**不破坏接口**的前提下优化各 codec 内部实现；但不应在 `DataSaver` 层按格式特判绕过 codec——那会破坏对称架构。

---

## 5. 线程安全分析

| 组件 | 可变性 | 并发策略 |
|------|--------|----------|
| `MetadataCache` 缓存表 | 读多写少 | `ConcurrentHashMap.computeIfAbsent`，同类扫描只执行一次 |
| `ClassMetadata` / `FieldMetadata` | 不可变 | 构建后不变，缓存后安全共享 |
| `SaveFieldTypeAdapter` | 不可变 | 持有 gson + metadata 引用，`write/read` 无状态 |
| `SaveFieldAdapter` 实例 | 无状态（契约） | 扫描时实例化一次，被多线程复用，实现必须线程安全 |
| `Gson` 实例 | 线程安全 | Gson 官方文档保证 `toJson/fromJson` 线程安全 |
| `SaveFormat` 枚举 | 不可变 | 枚举常量，天然线程安全 |
| `JsonCodec` / `YamlCodec` | 无状态 | YamlCodec 每次调用创建独立 SnakeYAML `Yaml` 实例 |
| `SaveValue` / `SaveValueBridge` | 不可变 / 无状态 | record 标量 + 无状态静态转换 |
| `DataSaver` | rootDir 写一次读多次；format 可变 | 构造/bindPlugin 时设置 rootDir；`setFormat` 修改 format（设计为单线程配置阶段调用） |

**结论**：序列化/反序列化热路径（`serialize` → `toJsonTree` → `write` / `parseToValue` → `fromJson` → `read`）完全无锁并发安全。
反射扫描只在首次访问某类时执行一次，后续全部命中缓存。`YamlCodec` 每次调用创建独立的 SnakeYAML 实例，避免其非线程安全问题。

> ⚠️ **例外**：`DataSaver` 的 `setRootDir()` / `bindPlugin()` 修改 `rootDir` 字段，`setFormat()` 修改 `format` 字段，
> 这些方法设计为**启动阶段单线程调用**（Spring 初始化 / 插件 onEnable），运行时不再修改。

---

## 6. 生命周期管理

```
Spring 启动
  │
  ├── Bean 创建：MetadataCache → DataSaver
  │     └── buildGson() 注册 TypeAdapterFactory
  │
  ├── bindPlugin(plugin)  ← JFrameMain 自动调用
  │     └── rootDir = plugin.getDataFolder()（仅当 rootDir 未设置）
  │
  ├── 运行时：save/load/loadOrSave/exists
  │     └── 首次访问某类 → MetadataCache.scan() 扫描并缓存
  │     └── 后续访问 → 命中缓存，零反射
  │
  └── forPlugin(plugin)  ← 业务插件按需调用
        └── 创建独立根保存器（共享 cache/gson，独立 rootDir）
```

**缓存生命周期**：`MetadataCache` 的缓存随 Bean 存活（通常 = 插件生命周期）。
类一旦被扫描，元数据永久缓存，不会因类卸载而失效（插件热重载场景需重新创建 DataSaver）。

---

## 7. 扩展点

### 7.1 新增字段级自定义序列化

实现 [`SaveFieldAdapter<T>`](adapter/SaveFieldAdapter.java)，在字段上标注 `@SaveField(adapter = XxxAdapter.class)`。
要求：无参构造器、无状态、线程安全。无需改动框架代码。

### 7.2 新增序列化控制属性

若需在 `@SaveField` 增加新属性（如 `compress`、`encrypt`）：
1. [`@SaveField`](annotation/SaveField.java) 添加属性
2. [`FieldMetadata`](core/meta/FieldMetadata.java) 解析并存储该属性
3. [`SaveFieldTypeAdapter`](core/engine/SaveFieldTypeAdapter.java) 的 `write/read` 中读取并应用

### 7.3 自定义 Gson 配置

修改 [`DataSaver.buildGson()`](DataSaver.java) 添加 Gson 配置（如日期格式、自定义命名策略）。
注意不要移除 `SaveFieldTypeAdapterFactory` 注册，否则注解驱动失效。

### 7.4 新增存储格式

若需支持 JSON / YAML 之外的格式（如 TOML、XML、Properties）：

1. 创建 [`SaveFormatCodec`](core/format/SaveFormatCodec.java) 的新实现（如 `TomlCodec`），实现 `write(JsonElement) → String` / `read(String) → JsonElement`
2. [`SaveFormat`](SaveFormat.java) 枚举新增一个值，绑定对应的文件扩展名与新 codec 实例

`DataSaver` 通过 `format.getCodec()` 统一调度，**无需任何 switch 分支改动**；序列化核心（Gson + `SaveFieldTypeAdapter`）、注解扫描、别名映射、required 校验、字段级 adapter 全部无需触碰。

---

## 8. 已知限制与陷阱

### 8.1 无参构造器是硬性要求

[`SaveFieldTypeAdapter.createInstance()`](core/engine/SaveFieldTypeAdapter.java:196) 通过无参构造反射创建实例。
类缺少无参构造器时，`load` 会抛 `DataException`。**替代方案**：用 `loadInto(target)` 回填已有实例。

### 8.2 `load` 不存在的文件返回 null

[`load(clazz, file)`](DataSaver.java:332) 在文件不存在时**返回 `null`**（不抛异常），以便调用方区分"数据缺失"与"读取失败"。
注意 [`loadInto()`](DataSaver.java:360) 仍会在文件缺失时抛 `DataException`（回填语义要求目标必须存在）；
若需在缺失时自动创建默认值并落盘，用 [`loadOrSave()`](DataSaver.java:436)。

### 8.3 save/load 严格作用于当前目录，不向上回退

子保存器（`sub` 派生）的 `load` 只在子目录查找，**不会自动向上回退**。
子目录无文件时 `load` 返回 `null`、`loadInto` 抛异常。需要访问上级时显式调用 `parent()` 或一步 `root()`。

### 8.4 嵌套对象无 `@SaveField` 时回退 Gson 默认

若嵌套对象的类**没有任何** `@SaveField` 字段，`SaveFieldTypeAdapterFactory.create()` 返回 null，
Gson 用默认反射适配器**序列化其全部字段**（不受注解控制）。要让嵌套对象也按注解规则序列化，其类必须有至少一个 `@SaveField`。

### 8.5 适配器必须无状态

[`FieldMetadata.resolveAdapter()`](core/meta/FieldMetadata.java:59) 扫描时实例化适配器一次并缓存，
序列化/反序列化期间被多线程复用。**适配器绝不能持有可变状态**，否则并发数据竞争。

### 8.6 `saveKey()` 不能返回 null/空串

[`resolveSaveKey()`](DataSaver.java:586) 校验 `saveKey()` 返回值，null 或空串抛 `DataException`。
`SaveIdentifiable` 实现需保证 `saveKey()` 永远返回有效文件名。

### 8.7 `loadOrSave` 的 supplier / defaultObj 不能为 null

显式文件名重载 [`loadOrSave(Class, String/File, Supplier)`](DataSaver.java:453) 在文件不存在时调用 `defaultSupplier.get()`，
返回 null 会抛 `DataException`（无法保存 null）。supplier 为 null 同样抛异常。

> **自动命名重载** [`loadOrSave(T defaultObj)`](DataSaver.java:470) 直接接收默认对象（而非 supplier），
> 因为确定文件名必须先读取 `saveKey()`，supplier 在此无法提供惰性收益。`defaultObj` 为 null 会抛 `DataException`。
> 若默认对象构造开销较大且需要惰性求值，请改用显式文件名重载（`supplier` 仅在文件不存在时才被调用）。

---

## 9. 修改指南（改代码前必读）

### 改字段扫描规则 → [`MetadataCache.scan()`](core/meta/MetadataCache.java:63)

- 改类层次遍历（如支持接口默认字段）
- 改字段过滤（如支持 `@SaveIgnore` 反向注解）
- 改字段顺序（如按 `@SaveField` 的 `order` 排序）

> ⚠️ 扫描结果直接影响 `ClassMetadata.fields()`，连锁影响 `SaveFieldTypeAdapter` 的 `write/read` 遍历顺序。

### 改序列化/反序列化逻辑 → [`SaveFieldTypeAdapter`](core/engine/SaveFieldTypeAdapter.java)

- `write()` 改序列化输出格式（如加版本号字段）
- `read()` 改反序列化策略（如 required 校验时机、缺失字段默认值填充）
- `createInstance()` 改实例化方式（如支持带参构造）

> ⚠️ 改 `read()` 的 required 校验会影响数据兼容性 —— 放宽校验可能导致旧数据静默加载错误值。

### 改路径解析 → [`DataSaver.resolveRelativeFile()`](DataSaver.java:568)

- 改扩展名规则（如支持 `.yml`）
- 改 rootDir 缺失行为

> ⚠️ 路径解析被 `save/load/exists/loadOrSave` 共用，改动影响所有文件操作。

### 改文件 IO → [`writeStringToFile()`](DataSaver.java:603) / [`readStringFromFile()`](DataSaver.java:619)

- 改编码（当前 UTF-8）
- 改原子性（如先写临时文件再 rename）
- 改错误处理（如文件不存在返回 null 而非抛异常）

### 改 Gson 配置 → [`DataSaver.buildGson()`](DataSaver.java)

- 添加 Gson 特性（日期格式、lenient 等）
- 注册其他 TypeAdapterFactory

> ⚠️ **绝不能移除** `SaveFieldTypeAdapterFactory` 注册，否则 `@SaveField` 注解完全失效。

---

## 📂 源码导航

| 包 | 类 | 职责 |
|----|----|------|
| `data` | [`DataSaver`](DataSaver.java) | 公开入口（save/load/exists/loadOrSave + 路径导航） |
| `data` | [`SaveIdentifiable`](SaveIdentifiable.java) | 动态文件命名接口（可选） |
| `data` | [`SaveFormat`](SaveFormat.java) | 存储格式枚举（扩展名 + 关联 codec） |
| `data.annotation` | [`SaveField`](annotation/SaveField.java) | 字段级保存注解（别名/必需/适配器） |
| `data.adapter` | [`SaveFieldAdapter`](adapter/SaveFieldAdapter.java) | 字段级自定义序列化适配器（操作 SaveValue） |
| `data.value` | [`SaveValue`](value/SaveValue.java) | 适配器通用数据模型（Null/Bool/Num/Str/List/Map） |
| `data.core.meta` | [`MetadataCache`](core/meta/MetadataCache.java) | 反射扫描 + ConcurrentHashMap 缓存 |
| `data.core.meta` | [`ClassMetadata`](core/meta/ClassMetadata.java) | 类元数据（不可变值对象） |
| `data.core.meta` | [`FieldMetadata`](core/meta/FieldMetadata.java) | 字段元数据（不可变值对象） |
| `data.core.engine` | [`SaveFieldTypeAdapterFactory`](core/engine/SaveFieldTypeAdapterFactory.java) | Gson 拦截入口 |
| `data.core.engine` | [`SaveFieldTypeAdapter`](core/engine/SaveFieldTypeAdapter.java) | 序列化/反序列化核心 |
| `data.core.engine` | [`SaveValueBridge`](core/engine/SaveValueBridge.java) | SaveValue ↔ JsonElement 字段边界转换桥 |
| `data.core.format` | [`SaveFormatCodec`](core/format/SaveFormatCodec.java) | 格式存取器接口（write/read，主干货币 JsonElement） |
| `data.core.format` | [`JsonCodec`](core/format/JsonCodec.java) | JSON 格式实现（Gson 驱动） |
| `data.core.format` | [`YamlCodec`](core/format/YamlCodec.java) | YAML 格式实现（SnakeYAML 驱动） |
| `data.exception` | [`DataException`](exception/DataException.java) | 统一异常（RuntimeException） |
| `data.config` | [`DataSpringConfig`](config/DataSpringConfig.java) | Spring 配置入口 |

> 用户使用文档请看 [README.md](README.md)。
