package io.github.JiangHu.jframe.db.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 子表标记 — 把「实体自有」的容器字段拆到独立子表存储（行级）。
 * <p>
 * 与 JSON 列（容器默认存储）的区别：每个元素一行（parent_key + 序号 + 值），
 * 适合元素量大、需要行级可见性的自有列表（如玩家的成就列表）。
 * <p>
 * 语义：
 * <ul>
 *   <li>子表名 = {@code 主表名_字段别名}；列为 parent_key（索引）、
 *       seq（序号）、value（标量元素 → 对应列类型；复杂元素 → JSON 文本列）</li>
 *   <li>读：按 parent_key 拉回全部子行，按 seq 排序装配容器</li>
 *   <li>写：主行 upsert 后<b>重写整片子行</b>（按 parent_key 删旧插新）——
 *       多行操作；适配器实现 {@link io.github.JiangHu.jframe.db.spi.TransactionalStore}
 *       时整体包事务，否则存在半写风险（见适配器指南）</li>
 * </ul>
 * 必须与 {@code @SaveField} 同时使用；仅允许 Collection 字段。
 *
 * <pre>{@code
 * public class PlayerData implements SaveIdentifiable {
 *     @SaveField @ChildTable
 *     private List<Achieve> achieves;   // → 子表 player_data_achieves
 *     ...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ChildTable {
}
