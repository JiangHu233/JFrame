package io.github.JiangHu.jframe.data.annotation;

import io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 保存字段注解（字段级别）— 标记类中需要被 {@code DataSaver} 序列化的属性。
 * <p>
 * 只有标注了本注解的<b>非静态、非 transient</b> 字段才会被保存到 JSON / 从 JSON 加载。
 * 未标注的字段在序列化时被忽略（不会出现在 JSON 中），在反序列化时保持默认值不变。
 *
 * <h3>别名（value）</h3>
 * <p>
 * 通过 {@link #value()} 可以为字段指定一个 JSON 键别名。为空时使用 Java 字段名作为 JSON 键。
 * <pre>{@code
 * @SaveField("player_name")   // JSON 键为 "player_name"
 * private String name;
 *
 * @SaveField                   // JSON 键为 "level"（字段名）
 * private int level;
 * }</pre>
 *
 * <h3>必需性（required）</h3>
 * <p>
 * {@link #required()} 为 {@code true} 时，加载阶段若 JSON 中缺少该键（或值为 null），
 * 将抛出 {@link io.github.JiangHu.jframe.data.exception.DataException DataException}。
 * 适用于关键字段（如玩家 UUID），防止数据损坏导致静默错误。
 * <pre>{@code
 * @SaveField(value = "uuid", required = true)
 * private String uuid;   // 加载时 JSON 必须包含 "uuid"，否则报错
 * }</pre>
 *
 * <h3>容器与对象属性</h3>
 * <p>
 * 本注解可标注在任意类型的字段上，包括：
 * <ul>
 *   <li>基本类型（int、String、boolean 等）</li>
 *   <li>容器类型（{@code List}、{@code Set}、{@code Map}、数组）— Gson 递归处理每个元素</li>
 *   <li>对象类型（嵌套对象）— 若嵌套类也有 {@code @SaveField} 字段则递归使用自定义适配器，
 *       否则回退 Gson 默认序列化</li>
 * </ul>
 *
 * @see io.github.JiangHu.jframe.data.DataSaver
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SaveField {

    /**
     * JSON 键别名。
     * <p>
     * 指定该字段在 JSON 中对应的键名。为空字符串时使用 Java 字段名。
     *
     * @return JSON 键别名，默认空字符串（表示使用字段名）
     */
    String value() default "";

    /**
     * 加载时是否必需。
     * <p>
     * 为 {@code true} 时，反序列化过程中若 JSON 缺少该键或值为 null，
     * 将抛出 {@link io.github.JiangHu.jframe.data.exception.DataException DataException}。
     * 为 {@code false}（默认）时，缺失的字段保持对象初始值不变。
     *
     * @return 是否必需，默认 false
     */
    boolean required() default false;

    /**
     * 字段级自定义序列化适配器。
     * <p>
     * 为该字段指定一个 {@link SaveFieldAdapter} 实现类，保存/加载该字段时将调用适配器的
     * {@link SaveFieldAdapter#toJson(Object) toJson} /
     * {@link SaveFieldAdapter#fromJson(JsonElement) fromJson}，
     * 而非委托 Gson 默认反射。
     * <p>
     * 适用于字段类型无法被 Gson 默认处理（如第三方库类、无无参构造、需要特殊编码），
     * 或希望复用已有的序列化方法的场景。适配器类需有无参构造器（可以是 private）。
     * <p>
     * 默认 {@link SaveFieldAdapter.None} 表示不使用适配器，回退 Gson 默认序列化。
     *
     * @return 适配器实现类，默认 {@link SaveFieldAdapter.None}
     * @see SaveFieldAdapter
     */
    Class<? extends SaveFieldAdapter> adapter() default SaveFieldAdapter.None.class;
}
