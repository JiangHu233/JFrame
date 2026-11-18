package io.github.JiangHu.jframe.db.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 关联表标记 — 「会被别处引用的独立实体」列表通过关联表多对多挂接（不级联）。
 * <p>
 * 与 {@link ChildTable @ChildTable}（自有数据拆行）的区别：元素是独立实体
 * （有自己的表与生命周期），本表只存<b>引用关系</b>（owner_key + ref_key 双列）。
 * <p>
 * 语义：
 * <ul>
 *   <li>关联表名 = {@code 主表名_字段别名}；列为 owner_key（索引）、ref_key</li>
 *   <li>读：按 owner_key 取关联行 → 按 ref_key 批量加载 ref 实体装配容器
 *       （元素缺失的 ref 跳过）</li>
 *   <li>写：<b>只维护关联行</b>（差集删除/新增），<b>不级联保存</b> ref 实体——
 *       ref 实体由它自己的 DataManager 存取</li>
 * </ul>
 * 必须与 {@code @SaveField} 同时使用；元素类型必须是实现
 * {@link io.github.JiangHu.jframe.serialize.SaveIdentifiable} 的实体，
 * 且该实体自身不得再含 @ChildTable / @RelationTable 字段（防装配环，
 * 违者映射期 fail-fast）。
 *
 * <pre>{@code
 * public class PlayerData implements SaveIdentifiable {
 *     @SaveField @RelationTable(refTable = "titles", refEntity = Title.class)
 *     private List<Title> titles;   // → 关联表 player_data_titles（不级联存 Title）
 *     ...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RelationTable {

    /**
     * 被引用实体所在的表名（同库）。
     *
     * @return 表名
     */
    String refTable();

    /**
     * 被引用实体表的主键列名（须与该表 manager 使用的一致）。
     *
     * @return 主键列名，默认 "id"
     */
    String refKeyColumn() default "id";

    /**
     * 被引用实体的类型（须实现 SaveIdentifiable 且为扁平实体）。
     *
     * @return 实体类
     */
    Class<?> refEntity();
}
