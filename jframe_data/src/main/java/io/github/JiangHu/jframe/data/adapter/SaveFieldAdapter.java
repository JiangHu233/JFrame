package io.github.JiangHu.jframe.data.adapter;

import com.google.gson.JsonElement;

/**
 * 字段级自定义序列化适配器 — 为单个 {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 字段提供自定义的序列化/反序列化逻辑。
 * <p>
 * 当某个字段类型无法被 Gson 默认反射正确处理（如第三方库类、无无参构造、需要特殊编码），
 * 或你希望用已有的序列化方法时，实现本接口并通过
 * {@code @SaveField(adapter = XxxAdapter.class)} 指定即可。
 *
 * <h3>使用示例</h3>
 * <p>
 * 假设有一个 {@code Pos} 类，你想把它序列化为 {@code "x,y"} 字符串：
 * <pre>{@code
 * public class PosAdapter implements SaveFieldAdapter<Pos> {
 *     @Override
 *     public JsonElement toJson(Pos value) {
 *         return new JsonPrimitive(value.x + "," + value.y);
 *     }
 *
 *     @Override
 *     public Pos fromJson(JsonElement json) {
 *         String[] parts = json.getAsString().split(",");
 *         return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
 *     }
 * }
 *
 * public class PlayerData {
 *     @SaveField(adapter = PosAdapter.class)
 *     private Pos spawn;   // 保存时用 PosAdapter，而非 Gson 默认反射
 * }
 * }</pre>
 *
 * <h3>实例化要求</h3>
 * <p>
 * 适配器类必须有一个<b>无参构造器</b>（可以是 private）。框架在扫描字段时通过反射实例化并缓存，
 * 序列化/反序列化期间复用同一实例，因此实现应是<b>无状态、线程安全</b>的。
 *
 * <h3>与默认序列化的关系</h3>
 * <ul>
 *   <li>字段<b>指定了</b> adapter → 使用适配器的 {@link #toJson} / {@link #fromJson}</li>
 *   <li>字段<b>未指定</b> adapter（默认 {@link None}）→ 委托 Gson 按字段泛型类型序列化</li>
 * </ul>
 *
 * @param <T> 字段的实际类型
 * @see io.github.JiangHu.jframe.data.annotation.SaveField#adapter()
 */
public interface SaveFieldAdapter<T> {

    /**
     * 将字段值序列化为 JSON 元素。
     *
     * @param value 字段值（可能为 null，实现需自行处理）
     * @return JSON 元素（如 {@link com.google.gson.JsonPrimitive}、{@link com.google.gson.JsonObject} 等）
     */
    JsonElement toJson(T value);

    /**
     * 从 JSON 元素反序列化为字段值。
     *
     * @param json JSON 元素（不会为 null，框架已过滤缺失/required 校验）
     * @return 反序列化后的字段值
     */
    T fromJson(JsonElement json);

    /**
     * 标记类：表示<b>不使用适配器</b>。
     * <p>
     * 作为 {@link io.github.JiangHu.jframe.data.annotation.SaveField#adapter()} 的默认值。
     * 框架遇到此类型时回退到 Gson 默认序列化。调用其方法会抛出异常（不应被调用）。
     */
    final class None implements SaveFieldAdapter<Object> {
        private None() {
        }

        @Override
        public JsonElement toJson(Object value) {
            throw new UnsupportedOperationException("None 是占位标记，不应被调用");
        }

        @Override
        public Object fromJson(JsonElement json) {
            throw new UnsupportedOperationException("None 是占位标记，不应被调用");
        }
    }
}
