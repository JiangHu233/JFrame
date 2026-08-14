package io.github.JiangHu.jframe.data.core.meta;

import io.github.JiangHu.jframe.data.annotation.SaveField;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类元数据缓存（线程安全）。
 * <p>
 * 首次查询某个类时，反射扫描该类及所有父类的字段，提取
 * {@link SaveField @SaveField} 标注的非静态、非 transient 字段，
 * 构建 {@link ClassMetadata} 并缓存。后续查询直接返回缓存结果。
 * <p>
 * 使用 {@link Optional} 包装：有 {@code @SaveField} 字段时缓存非空 Optional，
 * 无字段时缓存空 Optional —— 这样"无保存字段"的判断也只做一次反射扫描。
 *
 * <h3>扫描规则</h3>
 * <ul>
 *   <li>从当前类向上遍历到 {@code Object}（不含），收集所有 declared fields</li>
 *   <li>只保留标注了 {@code @SaveField} 的字段</li>
 *   <li>跳过 {@code static} 和 {@code transient} 修饰的字段</li>
 *   <li>每个字段调用 {@code setAccessible(true)} 以支持 private 字段访问</li>
 *   <li>父类字段排在子类字段之前（声明顺序）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>
 * 使用 {@link ConcurrentHashMap#computeIfAbsent} 保证同一类的扫描只执行一次。
 * {@link FieldMetadata} 和 {@link ClassMetadata} 均为不可变对象，可安全共享。
 *
 * @see ClassMetadata
 * @see FieldMetadata
 * @see SaveField
 */
public final class MetadataCache {

    /** 类 → 元数据的缓存（Optional.empty 表示该类无 @SaveField 字段） */
    private final ConcurrentHashMap<Class<?>, Optional<ClassMetadata>> cache = new ConcurrentHashMap<>();

    /**
     * 获取指定类的保存元数据。
     * <p>
     * 首次调用时反射扫描并缓存；后续调用直接返回缓存。
     *
     * @param clazz 目标类
     * @return 元数据 Optional；有 @SaveField 字段时非空，否则空
     */
    public Optional<ClassMetadata> get(Class<?> clazz) {
        return cache.computeIfAbsent(clazz, this::scan);
    }

    /**
     * 反射扫描类层次结构，构建元数据。
     *
     * @param clazz 目标类
     * @return 元数据 Optional；无 @SaveField 字段时返回 empty
     */
    private Optional<ClassMetadata> scan(Class<?> clazz) {
        List<FieldMetadata> fields = new ArrayList<>();

        // 从当前类向上遍历到 Object（不含），收集父类字段在前
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            hierarchy.add(current);
            current = current.getSuperclass();
        }
        // 反转：父类在前，子类在后
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            for (Field field : hierarchy.get(i).getDeclaredFields()) {
                SaveField annotation = field.getAnnotation(SaveField.class);
                if (annotation == null) {
                    continue;
                }
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(new FieldMetadata(field, annotation));
            }
        }

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClassMetadata(clazz, fields));
    }
}
