package io.github.JiangHu.jframe.data.core.engine;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import io.github.JiangHu.jframe.data.core.meta.ClassMetadata;
import io.github.JiangHu.jframe.data.core.meta.MetadataCache;

import java.util.Optional;

/**
 * Gson 类型适配器工厂 — 注解驱动的序列化核心。
 * <p>
 * 注册到 {@link Gson} 实例后，Gson 在序列化/反序列化任何类型时都会先询问本工厂：
 * <ul>
 *   <li>若目标类（含父类）存在 {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 *       字段 → 返回 {@link SaveFieldTypeAdapter}，仅序列化标注字段，使用别名作为 JSON 键</li>
 *   <li>若不存在 {@code @SaveField} 字段 → 返回 {@code null}，交由 Gson 默认反射适配器处理</li>
 * </ul>
 *
 * <h3>递归处理容器与嵌套对象</h3>
 * <p>
 * 本工厂创建的适配器在处理每个字段值时，通过 {@code gson.toJson(value, fieldType, writer)}
 * 和 {@code gson.fromJson(element, fieldType)} 委托给 Gson 的类型适配器链。
 * 这意味着：
 * <ul>
 *   <li><b>容器字段</b>（{@code List}、{@code Set}、{@code Map}、数组）：
 *       Gson 的 Collection/Map 适配器逐元素递归，元素类型若含 {@code @SaveField}
 *       会再次命中本工厂</li>
 *   <li><b>嵌套对象字段</b>：若嵌套类含 {@code @SaveField} 则递归使用本适配器，
 *       否则回退 Gson 默认（序列化全部字段）</li>
 *   <li><b>基本类型 / 枚举</b>：Gson 内置适配器直接处理</li>
 * </ul>
 *
 * <h3>注册方式</h3>
 * <pre>{@code
 * Gson gson = new GsonBuilder()
 *         .registerTypeAdapterFactory(new SaveFieldTypeAdapterFactory(metadataCache))
 *         .setPrettyPrinting()
 *         .create();
 * }</pre>
 *
 * @see SaveFieldTypeAdapter
 * @see MetadataCache
 */
public final class SaveFieldTypeAdapterFactory implements TypeAdapterFactory {

    /** 元数据缓存，用于判断类是否有 @SaveField 字段 */
    private final MetadataCache cache;

    /**
     * 构造工厂。
     *
     * @param cache 元数据缓存
     */
    public SaveFieldTypeAdapterFactory(MetadataCache cache) {
        this.cache = cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Optional<ClassMetadata> metadata = cache.get(type.getRawType());
        if (metadata.isEmpty()) {
            // 无 @SaveField 字段，交给 Gson 默认适配器
            return null;
        }
        // ClassMetadata 不携带泛型参数，需 unchecked 强转回 TypeAdapter<T>
        return (TypeAdapter<T>) new SaveFieldTypeAdapter<>(gson, metadata.get()).nullSafe();
    }
}
