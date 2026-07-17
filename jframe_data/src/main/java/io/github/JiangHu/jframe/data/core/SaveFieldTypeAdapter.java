package io.github.JiangHu.jframe.data.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter;
import io.github.JiangHu.jframe.data.exception.DataException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

/**
 * 注解驱动的 Gson 类型适配器。
 * <p>
 * 由 {@link SaveFieldTypeAdapterFactory} 为每个含
 * {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 字段的类创建。仅序列化/反序列化标注了 {@code @SaveField} 的字段，
 * 使用注解别名作为 JSON 键。
 *
 * <h3>序列化（write）</h3>
 * <p>
 * 遍历 {@link ClassMetadata} 中的字段列表，对每个字段：
 * <ol>
 *   <li>反射读取字段值</li>
 *   <li>调用 {@code gson.toJson(value, fieldType, writer)} 委托 Gson 序列化该值</li>
 * </ol>
 * <b>关键</b>：使用 {@link Field#getGenericType()} 而非 {@code getType()}，
 * 保留泛型签名（如 {@code List<String>}），Gson 据此正确选择元素适配器。
 *
 * <h3>反序列化（read）</h3>
 * <p>
 * 先将 JSON 解析为 {@link JsonObject}（树模型），再遍历字段列表：
 * <ol>
 *   <li>按别名从 JsonObject 取值</li>
 *   <li>{@code required} 字段缺失或为 null → 抛 {@link DataException}</li>
 *   <li>非必需字段缺失 → 跳过（保持对象初始值）</li>
 *   <li>调用 {@code gson.fromJson(element, fieldType)} 反序列化并反射 set 回对象</li>
 * </ol>
 *
 * <h3>容器与嵌套对象的递归</h3>
 * <p>
 * {@code gson.toJson} / {@code gson.fromJson} 会走完整的 Gson 类型适配器链：
 * <ul>
 *   <li>容器类型 → Gson 的 Collection/Map 适配器逐元素递归</li>
 *   <li>嵌套对象含 {@code @SaveField} → 再次命中 {@link SaveFieldTypeAdapterFactory}</li>
 *   <li>嵌套对象无 {@code @SaveField} → Gson 默认反射适配器</li>
 * </ul>
 *
 * @param <T> 目标类型
 * @see SaveFieldTypeAdapterFactory
 * @see ClassMetadata
 * @see FieldMetadata
 */
final class SaveFieldTypeAdapter<T> extends TypeAdapter<T> {

    /** Gson 实例（用于委托字段值的序列化/反序列化） */
    private final Gson gson;

    /** 目标类的保存元数据 */
    private final ClassMetadata metadata;

    /**
     * 构造适配器。
     *
     * @param gson     Gson 实例
     * @param metadata 类元数据
     */
    SaveFieldTypeAdapter(Gson gson, ClassMetadata metadata) {
        this.gson = gson;
        this.metadata = metadata;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
        out.beginObject();
        for (FieldMetadata fm : metadata.fields()) {
            Object fieldValue = getFieldValue(fm.field(), value);
            out.name(fm.alias());
            if (fm.hasAdapter()) {
                // 字段级自定义适配器：先转换为 JsonElement，再写入流
                JsonElement element = writeWithAdapter(fm.adapter(), fieldValue);
                gson.toJson(element, out);
            } else {
                // 委托 Gson 序列化字段值（使用泛型类型，保留 List<T> 等签名）
                gson.toJson(fieldValue, fm.field().getGenericType(), out);
            }
        }
        out.endObject();
    }

    @Override
    public T read(JsonReader in) throws IOException {
        // 解析为 JsonObject（树模型），便于按别名随机访问字段
        JsonElement root = JsonParser.parseReader(in);
        if (root.isJsonNull()) {
            return null;
        }
        JsonObject jsonObject = root.getAsJsonObject();

        T instance = createInstance();

        for (FieldMetadata fm : metadata.fields()) {
            JsonElement element = jsonObject.get(fm.alias());

            if (element == null || element.isJsonNull()) {
                if (fm.required()) {
                    throw new DataException("必需字段 '" + fm.alias() +
                            "' 在 JSON 中缺失或为 null（类: " + metadata.type().getName() + "）");
                }
                // 非必需字段缺失 → 保持对象初始值，跳过
                continue;
            }

            Object fieldValue;
            if (fm.hasAdapter()) {
                // 字段级自定义适配器：从 JsonElement 转换
                fieldValue = readWithAdapter(fm.adapter(), element);
            } else {
                // 委托 Gson 反序列化字段值（使用泛型类型）
                fieldValue = gson.fromJson(element, fm.field().getGenericType());
            }
            setFieldValue(fm.field(), instance, fieldValue);
        }

        return instance;
    }

    /**
     * 使用字段级适配器序列化字段值。
     * <p>
     * 适配器返回 {@link JsonElement}（树模型），再由 Gson 写入流。
     * 异常统一包装为 {@link DataException}。
     */
    @SuppressWarnings("unchecked")
    private JsonElement writeWithAdapter(SaveFieldAdapter<?> adapter, Object fieldValue) {
        try {
            return ((SaveFieldAdapter<Object>) adapter).toJson(fieldValue);
        } catch (Exception e) {
            throw new DataException("字段适配器 " + adapter.getClass().getName() +
                    " 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用字段级适配器反序列化字段值。
     * <p>
     * 从 {@link JsonElement}（树模型）转换为目标值。异常统一包装为 {@link DataException}。
     */
    @SuppressWarnings("unchecked")
    private Object readWithAdapter(SaveFieldAdapter<?> adapter, JsonElement element) {
        try {
            return ((SaveFieldAdapter<Object>) adapter).fromJson(element);
        } catch (Exception e) {
            throw new DataException("字段适配器 " + adapter.getClass().getName() +
                    " 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反射读取字段值，包装异常。
     */
    private Object getFieldValue(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new DataException("无法读取字段 " + field.getName() +
                    "（类: " + field.getDeclaringClass().getName() + "）", e);
        }
    }

    /**
     * 反射设置字段值，包装异常。
     */
    private void setFieldValue(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new DataException("无法设置字段 " + field.getName() +
                    "（类: " + field.getDeclaringClass().getName() + "）", e);
        }
    }

    /**
     * 通过无参构造器创建目标类实例。
     *
     * @return 新实例
     * @throws DataException 如果类缺少无参构造器或实例化失败
     */
    @SuppressWarnings("unchecked")
    private T createInstance() {
        Class<?> type = metadata.type();
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            // 支持非 public 类（如包级私有、私有嵌套类）的实例化
            constructor.setAccessible(true);
            return (T) constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new DataException("类 " + type.getName() +
                    " 缺少无参构造器，无法实例化。请添加无参构造器或使用 loadInto 回填已有实例。", e);
        } catch (Exception e) {
            throw new DataException("实例化类 " + type.getName() + " 失败", e);
        }
    }
}
