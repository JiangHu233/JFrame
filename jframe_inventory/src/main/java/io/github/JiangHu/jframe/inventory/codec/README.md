# jframe_inventory · codec 子包

> 物品 / 物品栏的序列化（编解码）能力，完整保留 NBT 数据与槽位位置关系。

## 目录

- [简介](#简介)
- [特性](#特性)
- [快速开始](#快速开始)
- [API 详解](#api-详解)
  - [ItemCodec](#itemcodec)
  - [InventoryCodec](#inventorycodec)
- [文本格式规范](#文本格式规范)
- [JSON 格式（可读）](#json-格式可读)
- [两种格式对比](#两种格式对比)
- [异常处理](#异常处理)
- [设计说明](#设计说明)
- [测试](#测试)

---

## 简介

`codec` 子包提供将 Nukkit 原生 [`Item`](../../../../../cn/nukkit/item/Item.java) 与
[`Inventory`](../../../../../cn/nukkit/inventory/Inventory.java) 序列化为字符串、以及反向
反序列化的能力。适用于**持久化玩家物品栏到数据库/配置文件**、**跨服同步背包**、
**离线数据迁移**等场景。

本子包**独立于** `jframe_data` 的注解驱动序列化框架，仅依赖 Nukkit API 与 JDK，
可单独使用。

每种编解码器同时提供**两种输出格式**：

- **紧凑 Base64 文本格式** —— 存储体积小，适合入库
- **可读 JSON 格式** —— 结构清晰，适合调试、日志、跨系统交换

两种格式还原结果完全等价。

## 特性

| 特性 | 说明 |
|------|------|
| **完整保真** | 序列化全部 NBT（名称、lore、附魔、自定义标签），不丢失任何附加数据 |
| **位置保留** | 物品栏序列化记录每个槽位的索引，反序列化精确还原到原位置 |
| **类型无关** | 任意 Nukkit 原生物品栏（背包/箱子/末影箱/熔炉等）统一处理 |
| **稀疏存储** | 空气槽位不写入，节省存储空间 |
| **版本可演进** | 两种格式均带版本号（`V1`），支持未来向后兼容扩展 |
| **容量校验** | 反序列化应用时校验目标物品栏容量，防止越界 |
| **双格式输出** | 紧凑 Base64 文本（存储紧凑）+ 可读 JSON（调试友好），还原结果等价 |
| **无外部依赖** | 仅依赖 Nukkit API 与 JDK（`java.util.Base64`、Gson 由 Nukkit 传递提供） |

## 快速开始

### 序列化单个物品

```java
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.codec.ItemCodec;

Item sword = Item.get(Item.DIAMOND_SWORD);
sword.setCustomName("§b屠龙剑");
sword.setLore("§7传说武器", "§c攻击力 +100");

// —— 紧凑 Base64 文本格式（适合入库） ——
String data = ItemCodec.encode(sword);
Item restored = ItemCodec.decode(data);

// —— 可读 JSON 格式（适合调试/日志） ——
String json = ItemCodec.encodeJson(sword);
Item fromJson = ItemCodec.decodeJson(json);
```

### 序列化整个物品栏（保留位置）

```java
import io.github.JiangHu.jframe.inventory.codec.InventoryCodec;

// —— 紧凑文本格式 ——
String invData = InventoryCodec.encode(player.getInventory());
InventoryCodec.apply(player.getInventory(), invData);

// —— JSON 格式 ——
String invJson = InventoryCodec.encodeJson(player.getInventory());
InventoryCodec.applyJson(player.getInventory(), invJson);
```

### 仅解码为映射（自行处理）

```java
import java.util.Map;

// 文本格式
Map<Integer, Item> slots = InventoryCodec.decode(invData);

// JSON 格式
Map<Integer, Item> slotsFromJson = InventoryCodec.decodeJson(invJson);

for (Map.Entry<Integer, Item> entry : slots.entrySet()) {
    int slotIndex = entry.getKey();
    Item item = entry.getValue();
    // 自定义处理逻辑
}
```

## API 详解

### ItemCodec

单个物品 ↔ 字符串互转，提供 Base64 与 JSON 两套方法。

| 方法签名 | 说明 |
|----------|------|
| `static String encode(Item item)` | 序列化为 Base64-NBT 字符串；`null`/空气返回 `""` |
| `static Item decode(String encoded)` | 反序列化 Base64 字符串；空字符串返回空气物品 |
| `static String encodeJson(Item item)` | 序列化为可读 JSON 字符串；`null`/空气返回 `"null"` |
| `static Item decodeJson(String json)` | 反序列化 JSON 字符串；`null`/空白/`"null"` 返回空气物品 |

**Base64 编码规则：**

| 输入 | 输出 |
|------|------|
| `null` 或空气物品 | `""`（空字符串） |
| 非空物品 | `Base64( NBTIO.write(nbt, LITTLE_ENDIAN, network=true) )` |

**Base64 NBT 结构（手动对称构造）：**

```
{
  id: <short>,          // 物品 id
  Damage: <short>,      // meta/damage
  Count: <byte>,        // 数量
  tag: <CompoundTag>    // 附加 NBT（可选，含名称/lore/附魔/自定义数据）
}
```

### InventoryCodec

整个物品栏 ↔ 字符串互转，保留槽位位置，提供 Base64 与 JSON 两套方法。

| 方法签名 | 说明 |
|----------|------|
| `static String encode(Inventory inventory)` | 序列化整个物品栏（紧凑文本） |
| `static String encodeSlots(Item[] slots, int size)` | 序列化物品数组（紧凑文本，无需 Inventory 实例） |
| `static Map<Integer, Item> decode(String encoded)` | 反序列化文本为「槽位索引 → 物品」映射（不可变） |
| `static Map<Integer, Item> decodeSlots(String encoded)` | 同 `decode`（语义别名） |
| `static int decodeSize(String encoded)` | 读取 header 中的物品栏容量 |
| `static void apply(Inventory inventory, String encoded)` | 反序列化文本并应用到目标物品栏（清空+填充+容量校验） |
| `static String encodeJson(Inventory inventory)` | 序列化整个物品栏（可读 JSON） |
| `static String encodeSlotsJson(Item[] slots, int size)` | 序列化物品数组（可读 JSON） |
| `static Map<Integer, Item> decodeJson(String json)` | 反序列化 JSON 为「槽位索引 → 物品」映射（不可变） |
| `static void applyJson(Inventory inventory, String json)` | 反序列化 JSON 并应用到目标物品栏（清空+填充+容量校验） |

**`apply` / `applyJson` 执行步骤：**

1. 解析 header，校验 `size` 与目标物品栏容量一致（不一致抛异常）
2. 调用 `inventory.clearAll()` 清空
3. 按槽位索引逐一 `setItem` 填充

## 文本格式规范

```
V1:<size>:<index>=<base64>,<index>=<base64>,...
```

| 字段 | 含义 |
|------|------|
| `V1` | 格式版本号，预留向后兼容 |
| `size` | 物品栏容量（槽位数），反序列化时用于校验 |
| `index=base64` | 槽位条目：`index` 为 0-based 下标，`base64` 为 `ItemCodec.encode` 输出 |

**示例：**

```
V1:27:0=CgE...,5=DAM...,26=EQE...     # 27 格箱子，0/5/26 槽有物品
V1:27:                                 # 27 格空物品栏
V1:0:                                  # 0 格物品栏
```

**分隔符安全性：** Base64 字符集为 `[A-Za-z0-9+/=]`，不含 `:`、`,`；
槽位索引为纯数字，不含 `=`。因此 `:`、`,`、`=` 作为分隔符不会与数据冲突。

## JSON 格式（可读）

JSON 格式结构清晰，便于调试查看、日志输出与跨系统交换。附加 NBT 由
[`NbtJsonConverter`](NbtJsonConverter.java) 展开为**可读 JSON 结构**（而非 Base64 乱码）：
常见类型（string/int/double/compound/list）直接映射为 JSON 原生类型，byte/short/long/float
与数组类型用 `{"__nbt":"<类型>","value":...}` 标记保真（详见下表）。

**物品 JSON 结构：**

```json
{
  "id": 276,
  "damage": 0,
  "count": 1,
  "nbt": {
    "display": { "Name": "§b屠龙剑", "Lore": ["§7传说武器"] },
    "rarity": "legendary",
    "kill_count": 0
  }
}
```

| 字段 | 含义 |
|------|------|
| `id` | 物品 id（int） |
| `damage` | meta/damage（int，缺省 0） |
| `count` | 数量（int，缺省 1） |
| `nbt` | 附加 NBT 的**可读 JSON 结构**（无附加 NBT 时省略） |

**NBT → JSON 类型映射（由 `NbtJsonConverter` 处理，确保往返保真）：**

| NBT 类型 | JSON 表示 | 说明 |
|----------|-----------|------|
| `CompoundTag` | JSON object | 递归每个键值 |
| `ListTag` | JSON array | 递归每个元素 |
| `StringTag` | JSON string | 直接 |
| `IntTag` | JSON number（整数） | 最常见整型，直接映射 |
| `DoubleTag` | JSON number（小数） | 最常见浮点，直接映射 |
| `ByteTag` | `{"__nbt":"byte","value":N}` | 常作布尔标志 |
| `ShortTag` | `{"__nbt":"short","value":N}` | 附魔 ID 等 |
| `LongTag` | `{"__nbt":"long","value":N}` | 可能超 int 范围 |
| `FloatTag` | `{"__nbt":"float","value":N}` | 精度不同于 double |
| `ByteArrayTag` | `{"__nbt":"byte[]","value":[...]}` | 每个 byte 转 0~255 |
| `IntArrayTag` | `{"__nbt":"int[]","value":[...]}` | 直接 |

反向解析：JSON 整数 → `IntTag`，小数 → `DoubleTag`，boolean → `ByteTag`（1/0），
含 `__nbt` 的对象按标记还原对应类型，确保 `NBT → JSON → NBT` 往返等价。

空气物品序列化为 JSON `null`。

**物品栏 JSON 结构：**

```json
{
  "version": "V1",
  "size": 27,
  "slots": [
    { "index": 0, "item": { "id": 1, "damage": 0, "count": 10 } },
    { "index": 5, "item": { "id": 4, "damage": 0, "count": 20, "nbt": { "owner": "Steve" } } }
  ]
}
```

| 字段 | 含义 |
|------|------|
| `version` | 格式版本号（当前 `V1`） |
| `size` | 物品栏容量（反序列化时用于校验） |
| `slots` | 非空槽位数组，空气槽位稀疏省略 |
| `slots[].index` | 槽位下标（0-based） |
| `slots[].item` | 物品 JSON 对象（结构同上） |

**示例（27 格箱子，0/5 槽有物品）：**

```json
{"version":"V1","size":27,"slots":[{"index":0,"item":{"id":1,"damage":0,"count":10}},{"index":5,"item":{"id":4,"damage":2,"count":20}}]}
```

## 两种格式对比

| 维度 | 紧凑文本格式 | JSON 格式 |
|------|--------------|-----------|
| **方法后缀** | （无）/ `encode` / `decode` / `apply` | `Json` / `encodeJson` / `decodeJson` / `applyJson` |
| **可读性** | 低（Base64 乱码） | 高（结构化键值） |
| **体积** | 较小 | 较大（键名、括号开销） |
| **典型用途** | 数据库存储、配置文件、跨服同步 | 调试日志、跨系统交换、人工检视 |
| **NBT 保真** | Base64 完整保真 | 可读结构 + `__nbt` 标记保真 |
| **还原等价性** | — | 与紧凑格式还原结果完全一致 |

选择建议：**持久化优先用紧凑文本格式**（省空间）；**调试或需要人眼可读时用 JSON 格式**。

## 异常处理

所有编解码错误统一包装为 [`InventoryCodecException`](InventoryCodecException.java)
（继承 `RuntimeException`，非受检）。

| 场景 | 异常消息示例 |
|------|--------------|
| 空编码字符串 | `物品栏编码为空` / `物品栏 JSON 编码为空` |
| 不支持的版本 | `不支持的格式版本: V2（当前支持 V1）` |
| 缺少字段 | `物品栏编码格式非法（缺少字段）` / `物品栏 JSON 缺少 version 字段` |
| 非法 size | `物品栏容量解析失败: abc` |
| 非法槽位条目 | `槽位条目格式非法: noEqualsSign` |
| 容量不匹配 | `物品栏容量不匹配：编码=27，目标=9` |
| 非法 Base64 / JSON | `物品反序列化失败（NBT 解析异常）` / `物品栏 JSON 解析失败` |

```java
try {
    InventoryCodec.apply(inventory, dataFromDb);
} catch (InventoryCodecException e) {
    // 记录日志、回退到默认物品栏等
    plugin.getLogger().error("物品栏反序列化失败: " + e.getMessage(), e);
}
```

## 设计说明

### 为什么手动构造 NBT 而不用 `item.saveToNBT()`？

不同 Nukkit 分支（Nukkit / PowerNukkit / Nukkit-MOT）的 `Item ↔ NBT` 方法名不一致
（`saveToNBT` / `fromNBT` / `getNamedTag` 等）。为避免分支兼容问题，本实现**手动构造
对称的 CompoundTag**，仅依赖最稳定的 API：

- `Item.getId()` / `Item.getDamage()` / `Item.getCount()`
- `Item.hasCompoundTag()` / `Item.getNamedTag()` / `Item.setNamedTag(CompoundTag)`
- `Item.get(int, int, int)`

### 为什么用 `LITTLE_ENDIAN` + `network=true`？

基岩版协议采用小端序 + network 格式的 NBT。这与项目内既有的 NBT 写入方案一致
（参见 `FakeBlockHelper`），确保跨版本稳定。

### 为什么 JSON 格式的 NBT 展开为可读结构？

为了让 JSON 格式真正「人眼可读」（调试、日志、跨系统交换时一目了然），附加 NBT 由
[`NbtJsonConverter`](NbtJsonConverter.java) 展开为可读 JSON 结构，而非 Base64 乱码。

由于 JSON 原生类型无法区分 NBT 的 byte/short/int/long/float/double 等数值子类型，
转换器采用「常见类型直接映射 + 罕见类型用 `__nbt` 标记」的折中策略：
- `StringTag`/`IntTag`/`DoubleTag`/`CompoundTag`/`ListTag` 直接映射为 JSON 原生类型（最可读）
- `ByteTag`/`ShortTag`/`LongTag`/`FloatTag`/`ByteArrayTag`/`IntArrayTag` 用 `{"__nbt":"...","value":...}` 包装（保类型）

这样既保证可读性，又确保 `NBT → JSON → NBT` 往返时类型完全保真（NBT 二进制按类型写入
不同字节宽度，类型丢失会导致游戏行为异常）。详见上方「NBT → JSON 类型映射」表。

### 为什么空气槽位稀疏存储？

物品栏通常大部分为空。稀疏存储（只写非空槽位）可显著减小字符串体积，
尤其适合存入数据库列或配置文件。两种格式均采用稀疏存储。

## 测试

测试覆盖 58 个用例，全部通过：

```bash
mvn -pl jframe_inventory test
```

| 测试类 | 覆盖场景 |
|--------|----------|
| [`ItemCodecTest`](../../../../../../../../test/java/io/github/JiangHu/jframe/inventory/codec/ItemCodecTest.java) | 空气/null 边界、基础物品往返、带 NBT 物品往返（含嵌套）、JSON 往返（含可读 NBT 结构）、JSON 与 Base64 等价性、非法输入异常 |
| [`InventoryCodecTest`](../../../../../../../../test/java/io/github/JiangHu/jframe/inventory/codec/InventoryCodecTest.java) | 文本/JSON 格式结构、稀疏存储、槽位位置往返、容量校验、`apply`/`applyJson` 对真实 `BaseInventory` 的填充、两种格式等价性、异常输入 |
| [`NbtJsonConverterTest`](../../../../../../../../test/java/io/github/JiangHu/jframe/inventory/codec/NbtJsonConverterTest.java) | NBT↔JSON 类型保真：常见类型直接映射、byte/short/long/float/数组用 `__nbt` 标记保真、JSON 字面量推断、复杂嵌套往返、异常输入 |
| [`CodecDemoTest`](../../../../../../../../test/java/io/github/JiangHu/jframe/inventory/codec/CodecDemoTest.java) | 7 个带控制台输出的演示场景：物品双格式、边界约束、物品栏位置保留、稀疏存储、格式等价性、异常处理、离线迁移 |

测试使用 [`FakeInventory`](../../../../../../../../test/java/io/github/JiangHu/jframe/inventory/codec/FakeInventory.java)
（继承 `BaseInventory` 的最小可实例化子类）验证 `apply` / `applyJson` 行为，无需启动 Nukkit Server。
