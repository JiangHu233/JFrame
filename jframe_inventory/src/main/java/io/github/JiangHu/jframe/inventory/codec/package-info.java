/**
 * 物品 / 物品栏序列化（codec）子包。
 *
 * <h2>概览</h2>
 * <p>
 * 提供将 Nukkit 原生 {@link cn.nukkit.item.Item} 与
 * {@link cn.nukkit.inventory.Inventory} 序列化为字符串、以及反向反序列化的能力，
 * 完整保留物品的 NBT 数据（名称、lore、附魔、自定义标签等）与物品栏的槽位位置关系。
 *
 * <h2>核心组件</h2>
 * <ul>
 *   <li>{@link io.github.JiangHu.jframe.inventory.codec.ItemCodec} ——
 *       单个物品 ↔ Base64-NBT 字符串互转</li>
 *   <li>{@link io.github.JiangHu.jframe.inventory.codec.InventoryCodec} ——
 *       整个物品栏 ↔ 文本字符串互转（保留槽位位置）</li>
 *   <li>{@link io.github.JiangHu.jframe.inventory.codec.InventoryCodecException} ——
 *       统一的非受检异常</li>
 * </ul>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li><b>完整保真</b>：序列化全部 NBT，不丢失附魔/名称/lore/自定义数据</li>
 *   <li><b>位置保留</b>：物品栏序列化记录每个槽位的索引，反序列化精确还原</li>
 *   <li><b>类型无关</b>：任意 Nukkit 原生物品栏（背包/箱子/末影箱/熔炉等）统一处理</li>
 *   <li><b>独立无依赖</b>：不依赖 {@code jframe_data} 的注解框架，仅依赖 Nukkit API 与 JDK</li>
 *   <li><b>可演进</b>：文本格式带版本号（{@code V1}），支持未来向后兼容扩展</li>
 * </ul>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 序列化单个物品
 * Item sword = Item.get(Item.DIAMOND_SWORD);
 * sword.setCustomName("§b屠龙剑");
 * String itemData = ItemCodec.encode(sword);
 * Item restored = ItemCodec.decode(itemData);
 *
 * // 序列化整个物品栏（保留位置）
 * String invData = InventoryCodec.encode(player.getInventory());
 * // ... 持久化到数据库 / 配置文件 ...
 * InventoryCodec.apply(player.getInventory(), invData);
 * }</pre>
 *
 * <h2>格式规范</h2>
 * <ul>
 *   <li>物品：空气 → {@code ""}；非空 → {@code Base64(NBTIO.write(nbt, LITTLE_ENDIAN, network=true))}</li>
 *   <li>物品栏：{@code V1:<size>:<index>=<base64>,<index>=<base64>,...}（稀疏存储，空气槽位省略）</li>
 * </ul>
 *
 * @see io.github.JiangHu.jframe.inventory.codec.ItemCodec
 * @see io.github.JiangHu.jframe.inventory.codec.InventoryCodec
 * @since 1.0
 */
package io.github.JiangHu.jframe.inventory.codec;
