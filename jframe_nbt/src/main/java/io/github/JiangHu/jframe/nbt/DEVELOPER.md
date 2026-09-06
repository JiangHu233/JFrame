# jframe_nbt 开发者指南（M1）

面向本模块的维护者与 M2 续作者。设计真相源为模块根 `DESIGN.md`（v1.2），
本文只讲**实现结构、扩展点与平台偏差**。

---

## 1. 架构 DAG（包依赖单向）

```
                NbtAPI（门面）
                 │
        ┌────────┴────────┐
     adapt（目标适配）   core.path（路径引擎）
        │                  │
        │        ┌─────────┼──────────┐
        │   NbtPathParser  NbtPathMatcher → NbtPathWriter
        │        │          │              │
        │   NbtPathSegment(sealed)    NbtWriteOption
        │        │                        
        └──→ core.value（类型识别：NbtValues/CoerceMode/NbtReadOption/ListPolicy）
                     │
              core.filter（SnbtLiteral：过滤字面量模型）
                     
      core（异常：NbtPathException 五件套，被所有包依赖）
```

规则：
- 依赖只允许**向下**；`core.value` 不依赖 `core.path`（`ListPolicy` 因此独立成文件，供
  `NbtWriteOption` 共用）；
- `adapt` 只依赖 `core.value` + 引擎只读接口，不反向；
- 引擎类（Parser/Matcher/Writer）**无状态**，可作 Spring 单例并发复用。

## 2. 核心类职责

| 类 | 职责 | 关键点 |
|---|---|---|
| `NbtPathSegment` | sealed 段模型 | `Key` / `Index` / `AllElements` / `CompoundFilter` / `Predicate`（M2 占位）；新增段类型必须同步 5 处（见 §3.1） |
| `NbtPathParser` | 递归下降解析 | M1 子集；错误带位置与原文；过滤条目间宽容空白 |
| `NbtPath` | 不可变路径 | equals/hashCode 按结构；`expression()` 为规范化重建（非原文）；`parent()` 供 insert 定位父路径 |
| `NbtPathMatcher` | 求值（get） | 段间笛卡尔展开；类型冲突静默 0 命中（读宽容）；`Match(parent, tag, slot)` 记录写回位置 |
| `NbtPathWriter` | 写入（set/insert/merge/delete） | 写严格；List 原位修改经 `getAllUnsafe()`（见 §4 偏差 2） |
| `NbtValues` | 类型识别 | 表 4-1 写入推断 / 读取解包+宽化 / autoFit / List 归一 / convert(Class) |
| `SnbtLiteral` | 过滤字面量 | 类型敏感相等（`1b` ≠ `1`）；`matchesFilter` 在 Matcher 静态调用 |
| `NbtAPI` | 门面 | 全部 M1 签名；含 v1.2 推断糖 `<T> T get(Tag, NbtPath)` |
| `adapt.*` | 目标适配 | `NbtTarget` 接口 + `AbstractNbtTarget` 模板（read→compile→引擎→write）+ `CompoundTarget`/`ItemTarget` |

## 3. 扩展点

### 3.1 如何加新段类型（以 M2 谓词为例）

`Predicate` 段已占位。真正实现时改 5 处：

1. `NbtPathSegment`：给 `Predicate(String expr)` 补组件/语义（或换成 AST）；
2. `NbtPathParser.parseBracketBody`：`?` 分支从"拒绝"改为解析谓词体；
3. `NbtPathMatcher` 段分派：新增 `case Predicate` 求值分支；
4. `NbtPathWriter`：谓词段作为写入路径的命中展开（语义同 `AllElements` 的多命中替换）；
5. `NbtPath.buildExpression`：`case Predicate` 已有 `[?(...)]` 重建逻辑。

sealed + switch 模式匹配保证：漏改任何一处**编译期**即报错（exhaustiveness）。

### 3.2 如何加新的写入选项

`NbtWriteOption` 是 record，加组件 + `withXxx` 即可；引擎内在对应分支读取。
读取侧同理改 `NbtReadOption`。

### 3.3 如何加新的类型映射

写入：`NbtValues.of` 的 instanceof 链按"窄→宽"顺序插入（注意 `Byte` 在 `Integer` 前）；
读取：`asXxx` 系列 + `convert` 的 Class 分派表同步补一行。

## 4. 平台偏差登记（Nukkit MOT）

| # | 偏差 | 处理 |
|---|---|---|
| 1 | **无 `LongArrayTag`**（DESIGN 9.4 预判命中） | `long[]` 写入抛 `NbtTypeMismatchException`；`NbtValueType` 无 LONG_ARRAY 条目。M2 若引入 Cloudburst NBT 可补 |
| 2 | **`ListTag.add(int,T)` 在 index<size 时是 set 语义**（javap 字节码验证：内部走 `List.set`） | Writer 的原位插入/替换一律经 `getAllUnsafe()`（内部列表引用）执行标准 `List.add(int,E)`/`set`；**不要**改回 `ListTag.add(int,T)` |
| 3 | `ListTag` 无 `set(int,T)` | 同上，经 `getAllUnsafe().set()` |
| 4 | 数组元素（ByteArray/IntArray 的 `[n]`）delete 长度语义模糊 | M1 限制：不支持，Matcher 对数组容器的 delete 段静默 0 命中 |
| 5 | 验收③的 ItemCodec 往返（`ItemTarget` ↔ `ItemTagHelper`）依赖服务器类 | M1 以 `ItemTarget` 骨架 + 纯逻辑测试交付；集成验证留 M2（需服务器环境） |

## 5. 测试

纯逻辑测试（不依赖服务器启动），`mvn -pl jframe_nbt -am test`：

| 测试类 | 覆盖 |
|---|---|
| `NbtPathParserLogicTest` | 合法/非法/规范化往返/组合（~49 用例） |
| `NbtPathEngineLogicTest` | get/set/insert/merge/delete 矩阵 + DESIGN 2.4 示例表 + 推断糖三态 + 并发求值（~126 用例，8 线程×200 轮） |
| `NbtValuesLogicTest` | 表 4-1 全映射 / List 归一 / autoFit / 解包宽化 / convert（~54 用例） |

合计 **175 用例全绿**。新增行为请同步补对应用例组（@Nested 分组）。

## 6. M2 待办（从 M1 平滑增量）

1. **CoerceMode 完整化**：SNBT 推断（`ofSnbt`，开放问题 #9 已定 VANILLA）、谓词提升、
   STRICT/LENIENT 全场景核对（骨架已就位，只加分支不改结构）；
2. **谓词 `[?(...)]` 与通配 `*`**：按 §3.1 五处扩展；
3. **ItemTarget 集成验证**：ItemCodec 往返（偏差 5）；
4. 开放问题复议：#4 写入 miss 默认、#10 autoFit 默认、#7 Boolean 语义（README §8 表）；
5. `long[]`：若平台引入 LongArrayTag 再补（偏差 1）。
