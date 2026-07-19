package io.github.JiangHu.jframe.data.core;

import io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter;
import io.github.JiangHu.jframe.data.annotation.SaveField;
import io.github.JiangHu.jframe.data.exception.DataException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 单个保存字段的反射元数据（不可变值对象）。
 * <p>
 * 在 {@link MetadataCache} 扫描类时为每个 {@link SaveField @SaveField} 标注的字段创建一个实例，
 * 缓存反射 {@link Field} 引用（已 {@code setAccessible(true)}）以及注解配置（别名、必需性、适配器），
 * 避免每次序列化/反序列化时重复反射查找。
 *
 * @see SaveField
 * @see ClassMetadata
 * @see MetadataCache
 * @see SaveFieldAdapter
 */
public final class FieldMetadata {

    /** 反射字段引用（已 setAccessible） */
    private final Field field;

    /** JSON 键别名（注解 value()，为空时用字段名） */
    private final String alias;

    /** 是否必需（注解 required()） */
    private final boolean required;

    /** 字段级自定义适配器（null 表示无适配器，委托 Gson 默认序列化） */
    private final SaveFieldAdapter<?> adapter;

    /**
     * 构造字段元数据。
     *
     * @param field    反射字段（调用方负责 setAccessible）
     * @param annotation 字段上的 @SaveField 注解
     */
    public FieldMetadata(Field field, SaveField annotation) {
        this.field = field;
        this.alias = annotation.value().isEmpty() ? field.getName() : annotation.value();
        this.required = annotation.required();
        this.adapter = resolveAdapter(annotation.adapter());
    }

    /**
     * 解析并实例化适配器。
     * <p>
     * {@link SaveFieldAdapter.None} 表示不使用适配器，返回 null。
     * 其他类型通过无参构造器实例化（支持 private 构造器）。
     *
     * @param adapterClass 注解指定的适配器类
     * @return 适配器实例，或 null（None 标记）
     */
    @SuppressWarnings("unchecked")
    private static SaveFieldAdapter<?> resolveAdapter(Class<? extends SaveFieldAdapter> adapterClass) {
        if (adapterClass == SaveFieldAdapter.None.class) {
            return null;
        }
        try {
            Constructor<?> constructor = adapterClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (SaveFieldAdapter<?>) constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new DataException("字段适配器 " + adapterClass.getName() +
                    " 缺少无参构造器，无法实例化", e);
        } catch (Exception e) {
            throw new DataException("实例化字段适配器 " + adapterClass.getName() + " 失败", e);
        }
    }

    /**
     * @return 反射字段引用
     */
    public Field field() {
        return field;
    }

    /**
     * @return JSON 键别名
     */
    public String alias() {
        return alias;
    }

    /**
     * @return 是否必需
     */
    public boolean required() {
        return required;
    }

    /**
     * @return 该字段是否指定了自定义适配器
     */
    public boolean hasAdapter() {
        return adapter != null;
    }

    /**
     * @return 字段级自定义适配器（未指定时为 null）
     */
    public SaveFieldAdapter<?> adapter() {
        return adapter;
    }

    @Override
    public String toString() {
        return "FieldMetadata{" + field.getName() + " → '" + alias + "'" +
                (required ? " (required)" : "") +
                (adapter != null ? " (adapter=" + adapter.getClass().getSimpleName() + ")" : "") +
                "}";
    }
}
