# jframe_nbt —— NBT 路径操作库（M1 / MVP）

为 Nukkit MOT 平台提供 **JSONPath 风格的 NBT 路径查询与修改**：
一条路径字符串直达物品 NBT 深处的任意节点，读取宽容、写入严格。

```java
// 读取：物品名
String name = NbtAPI.get(itemNbt, "tag.display.Name", String.class);

// 修改：把第一个物品的数量改为 64（autoFit 自动保持 1b 字节语义）
NbtAPI.set(chestNbt, "Items[0].Count", 64);

// 查询：所有钻石（复合过滤，类型敏感：64b ≠ 64）
List<Tag> diamonds = NbtAPI.get(chestNbt, "Items[{id:\"minecraft:diamond\"}]");

// 删除：清空全部物品（幂等，0 命中不抛）
NbtAPI.delete(chestNbt, "Items");
```

不依赖服务器启动，纯逻辑库；Spring XML 装配开箱即用（见文末）。

---

## 1. 快速上手

### 1.1 依赖与获取 Bean

`jframe_main` 已聚合本模块时，从 Spring 容器取：

```java
NbtAPI nbt = context.getBean(NbtAPI.class);
```

XML 装配文件为 `resources/nbt-spring.xml`（构造器装配，与 command-spring.xml 同风格）。

### 1.2 五分钟示例

以箱子物品 NBT（DESIGN.md 2.4 示例树）为例：

```java
CompoundTag root = ...; // {version:1, Items:[{id:"diamond",Count:64b,tag:{...}}, ...], data:[B@..]}

// ① get：多命中按文档序返回
List<Tag> all = NbtAPI.get(root, "Items[].id");        // 全部物品 id
List<Tag> one  = NbtAPI.get(root, "Items[0].tag");     // 第一个物品的 tag

// ② 类型化解包（精确类型 + 无损宽化）
int    count = NbtAPI.get(root, "Items[0].Count", int.class);       // ByteTag → int（宽化）
String id    = NbtAPI.get(root, "Items[0].id", String.class);

// ③ find：Optional 风格（0 命中 = empty，不抛）
Optional<String> lore = NbtAPI.find(root, "Items[0].tag.display.Lore[0]", String.class);

// ④ set：Java 值直接写入（表 4-1 推断 + autoFit 旧值适配）
NbtAPI.set(root, "Items[0].Count", 32);                // 推断 IntTag → autoFit 回 ByteTag(32b)
NbtAPI.set(root, "custom.newKey", Map.of("a", 1));     // 0 命中 → 默认抛（见 3.1）

// ⑤ delete：删除全部命中，幂等
NbtAPI.delete(root, "Items[{id:\"minecraft:diamond\"}]");
```

### 1.3 目标类型推断糖（v1.2）

`<T> T get(Tag, NbtPath)` 按目标类型变量直接解包：

```java
String name = NbtAPI.get(root, NbtPath.compile("tag.display.Name")); // T=String
```

- 成功：返回原生解包值（String/Integer/Map/List...）；
- 类型不符：抛 `ClassCastException`（编译期类型即契约）；
- `T` 为 `var`/`Object`：退化为 `unwrap` 通用解包（数值统一 Long/Double，见 2.3）。

---

## 2. 路径语法（M1 子集）

| 语法 | 含义 | 示例 |
|---|---|---|
| `""`（空）/ `root` | 根节点自身 | `""` |
| `key` | Compound 命名键 | `tag` |
| `"quoted key"` | 引号键（含 `.`、空格等特殊字符，`\` 转义） | `"display name"` |
| `[n]` | 数组/List 下标（负数从尾部数） | `Items[0]`、`Items[-1]` |
| `[]` | 全体元素（多命中展开） | `Items[].id` |
| `[{k:v,...}]` | 复合过滤：匹配含指定键值对的 Compound 元素 | `Items[{id:"minecraft:diamond"}]` |
| `{k:v,...}`（开头） | 根过滤：对根的断言，不匹配则全程 0 命中 | `{version:1}.Items` |
| `.` | 段分隔（`]`/`}` 后可省略） | `tag.display.Name` |

**过滤字面量（表 4-4 M1 子集）**：字符串 `"x"`、`true`/`false`、整数（无后缀恒 `1`=IntTag）、
后缀数 `64b`/`10s`/`5L`/`1.5f`/`2.5d`。**过滤是类型敏感的**：`{Count:64}` 匹配不到 `64b`。

**M2 特性（当前明确拒绝并报中文错误）**：通配符 `*` / `[*]`、谓词 `[?(...)]`、嵌套字面量 `[{a:[1]}]`。

---

## 3. 类型映射

### 3.1 写入推断（Java 值 → Tag，表 4-1）

| Java 值 | NBT Tag | 说明 |
|---|---|---|
| `Boolean` | `ByteTag(1/0)` | NBT 无布尔，按 MC 惯例 1/0 |
| `Byte/Short/Integer/Long/Float/Double` | 对应数值 Tag | |
| `String` | `StringTag` | |
| `BigInteger` | `LongTag` | 超 long 值域抛 |
| `BigDecimal` | `DoubleTag` | STRICT 丢精度抛，LENIENT 截断 |
| `byte[]` / `int[]` | `ByteArrayTag` / `IntArrayTag` | |
| `long[]` | ✗ 不支持 | Nukkit MOT 无 LongArrayTag（偏差登记见 DEVELOPER.md） |
| `Map` | `CompoundTag` | 递归推断 |
| `List` / 其他数组 | `ListTag` | 元素最宽公共数值类型归一（见 3.3） |
| `Tag` | 直通 | 幂等 |
| 其他 | ✗ 抛异常 | 不做反射/toString 魔法 |

### 3.2 读取解包（Tag → Java 值）

数值 Tag 读取支持**无损宽化**（byte→short→int→long→float→double，自动）；
窄化/跨类（如 String→int）依 `CoerceMode`：STRICT 抛 `NbtTypeMismatchException`，LENIENT 返回零值/null。
`Boolean` 读取 = 数值非零为真（开放问题 #7 默认值，可在 M2 复议）。

### 3.3 List 归一

`List.of((byte)1, 2)` → 元素最宽公共类型 int → `ListTag<IntTag>`；
无法归一（如 Integer 与 String 混合）默认拒绝（`ListPolicy.REJECT`），
`NbtWriteOption.withListPolicy(ACCEPT_HETEROGENEOUS)` 放行异构。

---

## 4. 写入语义（读宽容、写严格）

| 操作 | 0 命中行为 | 说明 |
|---|---|---|
| `set` | **默认抛 `NbtModifyException`** | 开放问题 #4 默认 fail-fast（可在 M2 复议）；`withCreatePath(true)` 对全键段路径自动建父级 |
| `set` 类型校验 | 旧新 Tag id 不同默认抛 | 防 `1b` 被写成 `1`；`withTypeCheck(false)` 关闭 |
| `autoFit` | **默认开启** | 新值按旧值类型无损适配（IntTag(32)→旧 ByteTag 位→`32b`）；越界抛；`withAutoFit(false)` 关闭（开放问题 #10，可在 M2 复议） |
| `insert` | 抛 | 只作用于 ListTag；尾段下标=该处插入（原元素后移），否则末尾追加；越界默认抛，`withAppend(true)` 改追加 |
| `merge` | 同 set | 仅 Compound；同名双方皆 Compound 递归合并，否则覆盖 |
| `delete` | **静默（幂等）** | 删除全部命中；List 元素按降序执行防错位 |

数组元素（`ByteArray/IntArray` 的 `[n]`）set 按容器元素类型收窄（byte/int）；
数组元素不支持 delete（M1 限制）。

---

## 5. 异常一览（全部非受检、中文消息）

| 异常 | 场景 |
|---|---|
| `NbtPathSyntaxException` | 路径语法错误（含位置与原文） |
| `NbtPathNotFoundException` | `getSingle`/`require` 系 0 命中 |
| `NbtTypeMismatchException` | 类型转换失败（STRICT）/ 值域越界 / 不支持的类型 |
| `NbtModifyException` | 写入结构性拒绝（set 0 命中、根 set、insert 目标非 List 等） |
| `NbtPathTypeException` | set 类型校验失败（`NbtTypeMismatchException` 子类） |

---

## 6. Spring 装配

`nbt-spring.xml`（构造器装配）：

```xml
<bean id="nbtPathParser" class="io.github.JiangHu.jframe.nbt.core.path.NbtPathParser"/>
<bean id="nbtPathMatcher" class="...NbtPathMatcher">
    <constructor-arg ref="nbtPathParser"/>
</bean>
<bean id="nbtPathWriter" class="...NbtPathWriter">
    <constructor-arg ref="nbtPathMatcher"/>
</bean>
<bean id="nbtAPI" class="io.github.JiangHu.jframe.nbt.NbtAPI">
    <constructor-arg ref="nbtPathMatcher"/>
    <constructor-arg ref="nbtPathWriter"/>
</bean>
```

`NbtSpringConfig` 提供 `@ImportResource` 入口。所有引擎类无状态、线程安全，可作单例。

---

## 7. FAQ

**Q：为什么 `set` 找不到节点会抛异常，而不是像 get 一样返回空？**
读取宽容、写入严格：静默丢失写入是数据事故的常见来源。需要"不存在则创建"用
`NbtWriteOption.DEFAULT.withCreatePath(true)`（仅全键段路径）。该默认值（开放问题 #4）可在 M2 复议。

**Q：`64b` 和 `64` 有什么区别？**
NBT 数值是带类型的。过滤 `[{Count:64}]` 匹配不到 `64b`（ByteTag）；写入时 autoFit 会把
Java `int 64` 自动适配为旧值的字节语义，无需手工对齐。

**Q：`NbtAPI.get(root, path, int.class)` 传原始类型可以吗？**
可以，`convert` 已处理原始 Class（返回对应包装值，自动拆箱）。但 LENIENT 失败返回 null，
基本类型接收会 NPE——**基本类型请配 STRICT 或用 `find`**。

**Q：为什么 `long[]` 不支持？**
Nukkit MOT 平台没有 `LongArrayTag`（DESIGN.md 9.4 预判命中）。降级方案与偏差登记见 DEVELOPER.md。

**Q：并发安全吗？**
路径对象不可变；引擎无状态。多线程**读**同一棵树安全；对同一棵树的**写**仍需外部同步
（NBT 树本身是可变对象）。并发求值已有测试覆盖。

**Q：SNBT 字符串能直接转 Tag 吗？**
M2 范围（`NbtValues.ofSnbt`）。M1 的 SNBT 字面量仅用于路径过滤。

---

## 8. 开放问题默认值（均可在 M2 复议）

| # | 问题 | M1 默认 |
|---|---|---|
| 7 | Boolean 读取语义 | 数值非零为真 |
| 8 | LENIENT 失败返回 | Optional 系 empty / 原生系零值 |
| 9 | SNBT 无后缀整数 | VANILLA（恒 IntTag） |
| 10 | autoFit | 默认开启 |
| 4/2 | 写入 miss | fail-fast + createPath 选项 |

更多设计细节见模块根 `DESIGN.md`（v1.2）；架构与扩展指南见同目录 `DEVELOPER.md`。
