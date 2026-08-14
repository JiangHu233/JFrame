package io.github.JiangHu.jframe.data.value;

import io.github.JiangHu.jframe.data.exception.DataException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * 通用中间数据树 — jframe-data 序列化体系的<b>唯一中间表示</b>（零第三方依赖）。
 * <p>
 * 整个序列化管线围绕本模型分为两段，第三方库被完全隔离在各自边界内：
 * <pre>
 *   内存对象 ←→ {@link io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter} ←→ SaveValue 树
 *   SaveValue 树 ←→ {@link io.github.JiangHu.jframe.data.core.SaveFormatCodec}（JsonCodec / YamlCodec）←→ 文本
 * </pre>
 * 用户实现的 {@link io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter 适配器}只面对本模型，
 * 不感知 Gson / SnakeYAML 等底层库；同一份适配器在 JSON 与 YAML 两种格式下行为完全一致。
 *
 * <h3>节点类型</h3>
 * <table border="1">
 *   <tr><th>节点</th><th>承载内容</th><th>JSON / YAML 表示</th></tr>
 *   <tr><td>{@link Map}</td><td>有序键值对</td><td>对象 / 块映射</td></tr>
 *   <tr><td>{@link List}</td><td>有序列表</td><td>数组 / 块序列</td></tr>
 *   <tr><td>{@link Str}</td><td>字符串</td><td>字符串</td></tr>
 *   <tr><td>{@link Num}</td><td>数值</td><td>数值字面量</td></tr>
 *   <tr><td>{@link Bool}</td><td>布尔</td><td>{@code true} / {@code false}</td></tr>
 *   <tr><td>{@link Null}</td><td>空值</td><td>{@code null}</td></tr>
 * </table>
 *
 * <h3>构建示例</h3>
 * <pre>{@code
 * SaveValue.Map root = SaveValue.map()
 *         .put("name", SaveValue.of("Steve"))
 *         .put("level", SaveValue.of(88))
 *         .put("vip", SaveValue.of(true))
 *         .put("tags", SaveValue.list()
 *                 .add(SaveValue.of("pvp"))
 *                 .add(SaveValue.of("builder")));
 * }</pre>
 *
 * <h3>读取示例</h3>
 * <pre>{@code
 * if (root.get("name").isStr()) {
 *     String name = root.get("name").asString();
 * }
 * int level = root.get("level").asInt();
 * for (SaveValue tag : root.get("tags").asList().values()) {
 *     System.out.println(tag.asString());
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>
 * 标量节点（{@link Str}/{@link Num}/{@link Bool}/{@link Null}）为不可变 record，可安全共享；
 * 容器节点（{@link Map}/{@link List}）为可变构建器，<b>非线程安全</b>，构建完成后仅应读取。
 *
 * @see io.github.JiangHu.jframe.data.adapter.SaveFieldAdapter
 * @see io.github.JiangHu.jframe.data.core.SaveFormatCodec
 */
public sealed interface SaveValue
        permits SaveValue.Null, SaveValue.Bool, SaveValue.Num,
        SaveValue.Str, SaveValue.List, SaveValue.Map {

    // ========== 工厂方法 ==========

    /**
     * 创建空值节点。
     *
     * @return Null 节点（缓存实例）
     */
    static Null ofNull() {
        return Null.INSTANCE;
    }

    /**
     * 创建字符串节点。
     *
     * @param value 字符串值（不能为 null）
     * @return Str 节点
     * @throws DataException 若 value 为 null（null 语义请用 {@link #ofNull()}）
     */
    static Str of(String value) {
        if (value == null) {
            throw new DataException("字符串值不能为 null（null 语义请使用 SaveValue.ofNull()）");
        }
        return new Str(value);
    }

    /**
     * 创建数值节点。
     *
     * @param value 数值（不能为 null，接受 Integer/Long/Double/Float/Short/Byte/BigInteger/BigDecimal）
     * @return Num 节点
     * @throws DataException 若 value 为 null
     */
    static Num of(Number value) {
        if (value == null) {
            throw new DataException("数值不能为 null（null 语义请使用 SaveValue.ofNull()）");
        }
        return new Num(value);
    }

    /**
     * 创建布尔节点。
     *
     * @param value 布尔值
     * @return Bool 节点
     */
    static Bool of(boolean value) {
        return new Bool(value);
    }

    /**
     * 创建空的可变列表节点（链式构建用）。
     *
     * @return List 节点
     */
    static List list() {
        return new List();
    }

    /**
     * 创建空的可变映射节点（链式构建用）。
     *
     * @return Map 节点
     */
    static Map map() {
        return new Map();
    }

    // ========== 类型判断 ==========

    /**
     * @return 是否为 {@link Null} 节点
     */
    default boolean isNull() {
        return this instanceof Null;
    }

    /**
     * @return 是否为 {@link Bool} 节点
     */
    default boolean isBool() {
        return this instanceof Bool;
    }

    /**
     * @return 是否为 {@link Num} 节点
     */
    default boolean isNum() {
        return this instanceof Num;
    }

    /**
     * @return 是否为 {@link Str} 节点
     */
    default boolean isStr() {
        return this instanceof Str;
    }

    /**
     * @return 是否为 {@link List} 节点
     */
    default boolean isList() {
        return this instanceof List;
    }

    /**
     * @return 是否为 {@link Map} 节点
     */
    default boolean isMap() {
        return this instanceof Map;
    }

    // ========== 类型取值（不匹配时抛 DataException） ==========

    /**
     * 以字符串读取（宽容策略：{@link Str} 返回原文，{@link Num}/{@link Bool} 返回字面量表示）。
     *
     * @return 字符串值
     * @throws DataException 若节点为 {@link Null} 或容器类型
     */
    default String asString() {
        if (this instanceof Str s) {
            return s.value();
        }
        if (this instanceof Num n) {
            return n.value().toString();
        }
        if (this instanceof Bool b) {
            return Boolean.toString(b.value());
        }
        throw mismatch("Str/Num/Bool", "String");
    }

    /**
     * 以布尔读取。
     *
     * @return 布尔值
     * @throws DataException 若节点不是 {@link Bool}
     */
    default boolean asBool() {
        if (this instanceof Bool b) {
            return b.value();
        }
        throw mismatch("Bool", "boolean");
    }

    /**
     * 以数值读取。
     *
     * @return 数值
     * @throws DataException 若节点不是 {@link Num}
     */
    default Number asNumber() {
        if (this instanceof Num n) {
            return n.value();
        }
        throw mismatch("Num", "Number");
    }

    /**
     * 以 int 读取（经 {@link Number#intValue()} 转换，可能截断）。
     *
     * @return int 值
     * @throws DataException 若节点不是 {@link Num}
     */
    default int asInt() {
        return asNumber().intValue();
    }

    /**
     * 以 long 读取（经 {@link Number#longValue()} 转换）。
     *
     * @return long 值
     * @throws DataException 若节点不是 {@link Num}
     */
    default long asLong() {
        return asNumber().longValue();
    }

    /**
     * 以 double 读取（经 {@link Number#doubleValue()} 转换）。
     *
     * @return double 值
     * @throws DataException 若节点不是 {@link Num}
     */
    default double asDouble() {
        return asNumber().doubleValue();
    }

    /**
     * 以列表节点读取。
     *
     * @return List 节点
     * @throws DataException 若节点不是 {@link List}
     */
    default List asList() {
        if (this instanceof List l) {
            return l;
        }
        throw mismatch("List", "List");
    }

    /**
     * 以映射节点读取。
     *
     * @return Map 节点
     * @throws DataException 若节点不是 {@link Map}
     */
    default Map asMap() {
        if (this instanceof Map m) {
            return m;
        }
        throw mismatch("Map", "Map");
    }

    /**
     * 构造「类型不匹配」异常（内部工具）。
     */
    private DataException mismatch(String expected, String want) {
        return new DataException("SaveValue 类型不匹配：需要 " + expected +
                " 才能作为 " + want + " 读取，实际节点为 " + typeName());
    }

    /**
     * 返回节点类型名（内部工具，用于异常消息）。
     */
    private String typeName() {
        return switch (this) {
            case Null n -> "Null";
            case Bool b -> "Bool";
            case Num n -> "Num";
            case Str s -> "Str";
            case List l -> "List";
            case Map m -> "Map";
        };
    }

    // ========== 节点实现 ==========

    /**
     * 空值节点（单例）。
     */
    record Null() implements SaveValue {
        /** 缓存实例（Null 无状态，全局共享） */
        static final Null INSTANCE = new Null();
    }

    /**
     * 布尔节点。
     *
     * @param value 布尔值
     */
    record Bool(boolean value) implements SaveValue {
    }

    /**
     * 数值节点。
     *
     * @param value 数值（Integer/Long/Double/Float/Short/Byte/BigInteger/BigDecimal）
     */
    record Num(Number value) implements SaveValue {
    }

    /**
     * 字符串节点。
     *
     * @param value 字符串值
     */
    record Str(String value) implements SaveValue {
    }

    /**
     * 可变列表节点 — 有序保存子节点，支持链式构建。
     * <p>
     * 非线程安全；构建完成后通过 {@link #values()} 获取只读视图。
     */
    final class List implements SaveValue {

        private final ArrayList<SaveValue> values = new ArrayList<>();

        List() {
        }

        /**
         * 追加子节点（链式）。
         *
         * @param value 子节点
         * @return 自身（支持链式调用）
         */
        public List add(SaveValue value) {
            values.add(Objects.requireNonNull(value, "List 元素不能为 null（null 语义请使用 SaveValue.ofNull()）"));
            return this;
        }

        /**
         * 获取指定下标的子节点。
         *
         * @param index 下标
         * @return 子节点
         */
        public SaveValue get(int index) {
            return values.get(index);
        }

        /**
         * @return 元素数量
         */
        public int size() {
            return values.size();
        }

        /**
         * @return 是否为空列表
         */
        public boolean isEmpty() {
            return values.isEmpty();
        }

        /**
         * @return 只读元素视图
         */
        public java.util.List<SaveValue> values() {
            return Collections.unmodifiableList(values);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof List other)) return false;
            return values.equals(other.values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public String toString() {
            return "SaveValue.List" + values;
        }
    }

    /**
     * 可变映射节点 — 有序保存键值对（LinkedHashMap，保持插入顺序），支持链式构建。
     * <p>
     * 非线程安全；构建完成后通过 {@link #keys()} / {@link #entries()} 获取只读视图。
     */
    final class Map implements SaveValue {

        private final LinkedHashMap<String, SaveValue> values = new LinkedHashMap<>();

        Map() {
        }

        /**
         * 放入键值对（链式）。
         *
         * @param key   键（不能为 null）
         * @param value 子节点（不能为 null，null 语义请用 {@link SaveValue#ofNull()}）
         * @return 自身（支持链式调用）
         */
        public Map put(String key, SaveValue value) {
            values.put(Objects.requireNonNull(key, "Map 键不能为 null"),
                    Objects.requireNonNull(value, "Map 值不能为 null（null 语义请使用 SaveValue.ofNull()）"));
            return this;
        }

        /**
         * 获取指定键的子节点。
         *
         * @param key 键
         * @return 子节点；键不存在时返回 null
         */
        public SaveValue get(String key) {
            return values.get(key);
        }

        /**
         * 判断是否包含指定键。
         *
         * @param key 键
         * @return 包含返回 true
         */
        public boolean containsKey(String key) {
            return values.containsKey(key);
        }

        /**
         * 移除指定键。
         *
         * @param key 键
         * @return 被移除的子节点；键不存在时返回 null
         */
        public SaveValue remove(String key) {
            return values.remove(key);
        }

        /**
         * @return 键值对数量
         */
        public int size() {
            return values.size();
        }

        /**
         * @return 是否为空映射
         */
        public boolean isEmpty() {
            return values.isEmpty();
        }

        /**
         * @return 只读键视图
         */
        public java.util.Set<String> keys() {
            return Collections.unmodifiableSet(values.keySet());
        }

        /**
         * @return 只读键值对视图
         */
        public java.util.Set<java.util.Map.Entry<String, SaveValue>> entries() {
            return Collections.unmodifiableSet(values.entrySet());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Map other)) return false;
            return values.equals(other.values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public String toString() {
            return "SaveValue.Map" + values;
        }
    }
}
