package io.github.JiangHu.jframe.data.adapter;

import io.github.JiangHu.jframe.data.value.SaveValue;

/**
 * 字段级自定义序列化适配器 — 为单个 {@link io.github.JiangHu.jframe.data.annotation.SaveField @SaveField}
 * 字段提供自定义的序列化/反序列化逻辑。
 * <p>
 * 适配器只负责<b>内存数据 ↔ 通用中间数据（{@link SaveValue}）</b>之间的转换，
 * 不感知任何存储格式与底层序列化库（Gson / SnakeYAML 均被框架隔离）。
 * {@code DataSaver} 负责按当前格式（JSON / YAML）将中间数据转换为文本，或从文本提取中间数据：
 * <pre>
 *   内存对象 ←→ 本适配器（toSave / fromSave）←→ SaveValue 树 ←→ DataSaver 格式层 ←→ json / yml 文本
 * </pre>
 * 因此<b>同一份适配器在 JSON 与 YAML 两种格式下行为完全一致</b>。
 *
 * <h3>使用示例</h3>
 * <p>
 * 假设有一个 {@code Pos} 类，你想把它序列化为 {@code "x,y"} 字符串：
 * <pre>{@code
 * public class PosAdapter implements SaveFieldAdapter<Pos> {
 *     @Override
 *     public SaveValue toSave(Pos value) {
 *         if (value == null) {
 *             return SaveValue.ofNull();
 *         }
 *         return SaveValue.of(value.x + "," + value.y);
 *     }
 *
 *     @Override
 *     public Pos fromSave(SaveValue value) {
 *         if (value.isNull()) {
 *             return null;
 *         }
 *         String[] parts = value.asString().split(",");
 *         return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
 *     }
 * }
 *
 * public class PlayerData {
 *     @SaveField(adapter = PosAdapter.class)
 *     private Pos spawn;   // 保存时用 PosAdapter，而非默认反射
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
 *   <li>字段<b>指定了</b> adapter → 使用适配器的 {@link #toSave} / {@link #fromSave}</li>
 *   <li>字段<b>未指定</b> adapter（默认 {@link None}）→ 委托框架默认序列化（Gson 反射）</li>
 * </ul>
 *
 * @param <T> 字段的实际类型
 * @see SaveValue
 * @see io.github.JiangHu.jframe.data.annotation.SaveField#adapter()
 */
public interface SaveFieldAdapter<T> {

    /**
     * 将字段值（内存数据）转换为通用中间数据。
     *
     * @param value 字段值（可能为 null，实现需自行处理）
     * @return SaveValue 节点（标量 / 列表 / 映射均可，null 语义用 {@link SaveValue#ofNull()}）
     */
    SaveValue toSave(T value);

    /**
     * 从通用中间数据还原字段值（内存数据）。
     *
     * @param value SaveValue 节点（不会为 null，框架已过滤缺失/required 校验；
     *              但可能是 {@link SaveValue.Null}，实现需自行处理）
     * @return 还原后的字段值
     */
    T fromSave(SaveValue value);

    /**
     * 标记类：表示<b>不使用适配器</b>。
     * <p>
     * 作为 {@link io.github.JiangHu.jframe.data.annotation.SaveField#adapter()} 的默认值。
     * 框架遇到此类型时回退默认序列化。调用其方法会抛出异常（不应被调用）。
     */
    final class None implements SaveFieldAdapter<Object> {
        private None() {
        }

        @Override
        public SaveValue toSave(Object value) {
            throw new UnsupportedOperationException("None 是占位标记，不应被调用");
        }

        @Override
        public Object fromSave(SaveValue value) {
            throw new UnsupportedOperationException("None 是占位标记，不应被调用");
        }
    }
}
