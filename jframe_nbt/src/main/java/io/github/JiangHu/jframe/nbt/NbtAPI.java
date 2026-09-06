package io.github.JiangHu.jframe.nbt;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.core.data.map.LruCacheMap;
import io.github.JiangHu.jframe.nbt.adapt.CompoundTarget;
import io.github.JiangHu.jframe.nbt.adapt.ItemTarget;
import io.github.JiangHu.jframe.nbt.adapt.NbtTarget;
import io.github.JiangHu.jframe.nbt.core.NbtPathNotFoundException;
import io.github.JiangHu.jframe.nbt.core.path.NbtPath;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathMatcher;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathParser;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathWriter;
import io.github.JiangHu.jframe.nbt.core.path.NbtWriteOption;
import io.github.JiangHu.jframe.nbt.core.value.CoerceMode;
import io.github.JiangHu.jframe.nbt.core.value.NbtReadOption;
import io.github.JiangHu.jframe.nbt.core.value.NbtValues;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * jframe_nbt 对外门面（DESIGN.md 3.4）—— 全模块唯一推荐入口，对标 jframe_ai 的 {@code AiAPI}。
 *
 * <p>三层结构：{@code NbtAPI（门面/缓存/默认值）→ NbtPath（路径对象）→ 引擎（Matcher/Writer）}。
 * 门面自身无状态（除 volatile 默认转换模式与 LRU 编译缓存），线程安全。
 *
 * <h3>读取家族速查</h3>
 * <ul>
 *   <li>{@link #get(Tag, String)} —— 全命中 Tag 列表（0 命中 = 空列表，读宽容）；</li>
 *   <li>{@link #get(Tag, NbtPath)} —— <b>目标类型推断糖</b>（v1.2）：按赋值目标解包首命中为原生
 *       Java 值；类型不符抛 {@link ClassCastException}（编译期目标类型即契约）；
 *       {@code var} 接收时退化为 {@code Object}（通用解包）；</li>
 *   <li>{@link #get(Tag, String, Class)} / {@link #get(Tag, NbtPath, Class, NbtReadOption)} ——
 *       显式类型 + 无损宽化 + 模式/默认值控制；</li>
 *   <li>{@link #find(Tag, String)} —— LENIENT 语义：0 命中或类型不符一律 {@code Optional.empty()}
 *       （开放问题 #8 建议默认值，可在 M2 复议）；</li>
 *   <li>{@code getString/getInt/getDouble/getBoolean} —— 便捷原生读取，0 命中返回
 *       {@code null/0/0.0/false}；Boolean 读取 = 数值非零为真（开放问题 #7，可在 M2 复议）。</li>
 * </ul>
 *
 * <h3>写入家族速查</h3>
 * <ul>
 *   <li>{@link #set(Tag, String, Object)} —— Java 值按表 4-1 推断 + autoFit 旧值无损适配
 *       （默认开启，开放问题 #10，可在 M2 复议）；{@code null} 等价删除；</li>
 *   <li>{@link #insert(Tag, String, Object)} —— 列表插入/追加；</li>
 *   <li>{@link #merge(Tag, String, CompoundTag)} —— 深合并；</li>
 *   <li>{@link #delete(Tag, String)} —— 幂等删除。</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * NbtAPI nbt = ...; // Spring 注入
 * CompoundTag root = ...;
 * String name = nbt.get(root, nbt.compile("display.Name"));    // 推断糖（先 compile）
 * int slot = nbt.getInt(root, "SelectedItemSlot");              // miss → 0
 * nbt.set(root, "display.Name", "铁剑");                        // 推断 + autoFit
 * nbt.of(itemStack).set("display.Lore", List.of("第一行"));      // 物品目标
 * }</pre>
 *
 * @author JiangHu
 */
public final class NbtAPI {

    /** 编译缓存容量（高频路径复用不可变 NbtPath 实例）。 */
    private static final int COMPILE_CACHE_SIZE = 256;

    private final NbtPathParser parser;
    private final NbtPathMatcher matcher;
    private final NbtPathWriter writer;
    private final LruCacheMap<String, NbtPath> compileCache = new LruCacheMap<>(COMPILE_CACHE_SIZE);

    /** 门面级默认转换模式（便捷读取家族使用；开放问题 #8，可在 M2 复议）。 */
    private volatile CoerceMode defaultCoerceMode = CoerceMode.STRICT;

    /**
     * Spring 构造器装配入口（nbt-spring.xml：parser → matcher → writer → api）。
     *
     * @param parser  路径编译器
     * @param matcher 读取引擎
     * @param writer  写入引擎
     */
    public NbtAPI(NbtPathParser parser, NbtPathMatcher matcher, NbtPathWriter writer) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    // ==================== 编译与目标 ====================

    /**
     * 编译路径表达式（LRU 缓存复用；等价表达式命中同一实例的概率受缓存容量影响）。
     *
     * @param expression 路径表达式，如 {@code "Items[0].tag.display.Name"}
     * @throws io.github.JiangHu.jframe.nbt.core.NbtPathSyntaxException 表达式非法
     */
    public NbtPath compile(String expression) {
        NbtPath cached = compileCache.get(expression);
        if (cached != null) {
            return cached;
        }
        NbtPath parsed = parser.parse(expression);
        compileCache.put(expression, parsed);
        return parsed;
    }

    /** 包装纯复合树为操作目标（就地修改）。 */
    public NbtTarget of(CompoundTag root) {
        return new CompoundTarget(root, parser);
    }

    /** 包装物品为操作目标（无 NBT 时读空树、写入时回写 {@code setNamedTag}）。 */
    public NbtTarget of(Item item) {
        return new ItemTarget(item, parser);
    }

    // ==================== 读取：Tag 家族 ====================

    /** 全命中 Tag 列表（文档序；0 命中返回空列表——读宽容）。 */
    public List<Tag> get(Tag root, String path) {
        return matcher.select(root, compile(path));
    }

    /**
     * 恰好一个命中 → 返回该 Tag；0 个或多于一个 → 抛 {@link NbtPathNotFoundException}。
     */
    public Tag getSingle(Tag root, String path) {
        return getSingle(root, compile(path));
    }

    /**
     * {@link NbtPath} 重载版（跳过表达式缓存）。
     */
    public Tag getSingle(Tag root, NbtPath path) {
        return path.selectSingle(root)
                .orElseThrow(() -> new NbtPathNotFoundException("路径 0 命中或命中多个: " + path));
    }

    // ==================== 读取：目标类型推断糖（v1.2） ====================

    /**
     * 目标类型推断糖：解包<b>首命中</b>为原生 Java 值，按赋值目标静态类型交付。
     *
     * <p>解包规则（{@code NbtValues.toJavaValue}）：数值 Tag → 对应包装类型、StringTag → String、
     * ByteArray → byte[]、List → List<Tag>（浅）、Compound → CompoundTag。
     *
     * <p>行为契约：
     * <ul>
     *   <li>0 命中 → {@code null}；</li>
     *   <li>目标类型与实际不符 → {@link ClassCastException}（编译期类型即契约，fail-fast）；</li>
     *   <li>{@code var x = nbt.get(root, path)} → T 推断为 {@code Object}，返回通用解包值
     *       （var 退化用例，永不 CCE）。</li>
     * </ul>
     *
     * @param <T>  赋值目标类型（由调用方上下文推断）
     * @param root 根节点
     * @param path 已编译路径
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Tag root, NbtPath path) {
        List<Tag> hits = matcher.select(root, path);
        if (hits.isEmpty()) {
            return null;
        }
        return (T) NbtValues.toJavaValue(hits.get(0));
    }

    // ==================== 读取：显式类型家族 ====================

    /**
     * 显式类型读取（0 命中返回 null；基本类型请用包装类，避免拆箱 NPE）。
     * <p>M1 支持精确类型 + 无损宽化（如 ByteTag → Integer），模式取 {@link #getDefaultCoerceMode()}。
     */
    public <T> T get(Tag root, String path, Class<T> type) {
        return get(root, compile(path), type, NbtReadOption.of(defaultCoerceMode));
    }

    /**
     * 显式类型读取（全控制版）。
     *
     * @param option 读取选项：模式（STRICT 抛 / LENIENT 降级）、0 命中默认值、深解包
     */
    public <T> T get(Tag root, NbtPath path, Class<T> type, NbtReadOption option) {
        List<Tag> hits = matcher.select(root, path);
        if (hits.isEmpty()) {
            return type.cast(option.defaultValue());
        }
        return NbtValues.convert(hits.get(0), type, option.coerceMode());
    }

    /**
     * LENIENT 查找：0 命中或类型不符一律 {@code Optional.empty()}，不抛异常
     * （开放问题 #8 建议默认值，可在 M2 复议）。
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Tag root, String path) {
        List<Tag> hits = get(root, path);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable((T) NbtValues.toJavaValue(hits.get(0)));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    // ==================== 读取：便捷原生家族 ====================

    /** 读取字符串（0 命中 → null；非 StringTag 按默认模式：STRICT 抛 / LENIENT null）。 */
    public String getString(Tag root, String path) {
        Tag tag = firstOrNull(root, path);
        return tag == null ? null : NbtValues.asString(tag, defaultCoerceMode);
    }

    /** 读取 int（0 命中 → 0；非数值按默认模式：STRICT 抛 / LENIENT 0）。 */
    public int getInt(Tag root, String path) {
        Tag tag = firstOrNull(root, path);
        return tag == null ? 0 : NbtValues.asInt(tag, defaultCoerceMode);
    }

    /** 读取 double（0 命中 → 0.0；非数值按默认模式：STRICT 抛 / LENIENT 0.0）。 */
    public double getDouble(Tag root, String path) {
        Tag tag = firstOrNull(root, path);
        return tag == null ? 0.0 : NbtValues.asDouble(tag, defaultCoerceMode);
    }

    /**
     * 读取 boolean（0 命中 → false；数值<b>非零为真</b>——开放问题 #7 建议默认值，可在 M2 复议）。
     */
    public boolean getBoolean(Tag root, String path) {
        Tag tag = firstOrNull(root, path);
        return tag != null && NbtValues.asBoolean(tag, defaultCoerceMode);
    }

    /** 首命中或 null（多命中取文档序第一个）。 */
    private Tag firstOrNull(Tag root, String path) {
        List<Tag> hits = get(root, path);
        return hits.isEmpty() ? null : hits.get(0);
    }

    // ==================== 写入 ====================

    /**
     * 设置 Java 值（表 4-1 推断 + autoFit；{@code value == null} 等价 {@link #delete}）。
     *
     * @return 实际根（便于链式）
     */
    public Tag set(Tag root, String path, Object value) {
        return writer.setObject(root, compile(path), value, NbtWriteOption.DEFAULT);
    }

    /** 设置 Java 值（选项控制 autoFit / createPath / typeCheck / listPolicy）。 */
    public Tag set(Tag root, String path, Object value, NbtWriteOption option) {
        return writer.setObject(root, compile(path), value, option);
    }

    /** 设置 Tag（严格类型校验版，默认选项）。 */
    public Tag set(Tag root, String path, Tag value) {
        return writer.set(root, compile(path), value, NbtWriteOption.DEFAULT);
    }

    /** 插入 Java 值到列表（尾段下标 = 定位插入；否则命中列表末尾追加）。 */
    public Tag insert(Tag root, String path, Object value) {
        return writer.insertObject(root, compile(path), value, NbtWriteOption.DEFAULT);
    }

    /** 深合并补丁到命中 Compound（0 命中默认抛，createPath 开启时自动建）。 */
    public Tag merge(Tag root, String path, CompoundTag patch) {
        return writer.merge(root, compile(path), patch, NbtWriteOption.DEFAULT);
    }

    /** 深合并 Map 补丁（递归推断为 Compound 后合并）。 */
    public Tag merge(Tag root, String path, Map<String, ?> patch) {
        return writer.mergeObject(root, compile(path), patch, NbtWriteOption.DEFAULT);
    }

    /** 删除全部命中（幂等：0 命中静默成功）。 */
    public Tag delete(Tag root, String path) {
        return writer.delete(root, compile(path));
    }

    // ==================== 全局默认 ====================

    /**
     * 设置门面级默认转换模式（影响便捷读取家族与 {@code get(root, path, type)} 的降级行为）。
     * <p>默认 STRICT（类型不符显式抛出）；LENIENT 下 0 命中/类型不符返回零值（开放问题 #8）。
     */
    public void setDefaultCoerceMode(CoerceMode mode) {
        this.defaultCoerceMode = Objects.requireNonNull(mode, "mode");
    }

    /** 当前默认转换模式。 */
    public CoerceMode getDefaultCoerceMode() {
        return defaultCoerceMode;
    }
}
