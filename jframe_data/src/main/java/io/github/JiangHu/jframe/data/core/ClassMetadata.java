package io.github.JiangHu.jframe.data.core;

import java.util.List;

/**
 * 单个类的保存元数据（不可变值对象）。
 * <p>
 * 持有目标 {@link Class} 引用以及该类（含父类）中所有
 * {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 标注字段的 {@link FieldMetadata} 列表。
 * <p>
 * 由 {@link MetadataCache} 在首次访问时扫描创建并缓存。
 *
 * @see FieldMetadata
 * @see MetadataCache
 */
public final class ClassMetadata {

    /** 目标类 */
    private final Class<?> type;

    /** 该类（含父类）所有 @SaveField 字段的元数据列表 */
    private final List<FieldMetadata> fields;

    /**
     * 构造类元数据。
     *
     * @param type   目标类
     * @param fields 字段元数据列表（不可变副本）
     */
    public ClassMetadata(Class<?> type, List<FieldMetadata> fields) {
        this.type = type;
        this.fields = List.copyOf(fields);
    }

    /**
     * @return 目标类
     */
    public Class<?> type() {
        return type;
    }

    /**
     * @return 所有 @SaveField 字段的元数据列表（不可变）
     */
    public List<FieldMetadata> fields() {
        return fields;
    }

    /**
     * @return 该类是否有任何 @SaveField 字段
     */
    public boolean hasFields() {
        return !fields.isEmpty();
    }
}
