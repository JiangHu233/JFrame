package io.github.JiangHu.jframe.nbt.core.path;

import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtPathNotFoundException;
import io.github.JiangHu.jframe.nbt.core.NbtPathSyntaxException;
import io.github.JiangHu.jframe.nbt.core.filter.SnbtLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * NBT 路径对象 —— 解析、选择、写入的核心（DESIGN.md 3.2，对标 jframe_command 的
 * {@code PathPattern} 地位）。
 *
 * <p>将形如 {@code Items[0].tag.display.Name} 的路径字符串解析为有序的
 * {@link NbtPathSegment 段序列}，对 NBT 树求值得到命中集合。
 *
 * <h3>不可变与线程安全</h3>
 * 解析后字段不再变化，线程安全；同一实例可被多线程并发求值。
 * 建议对高频路径用 {@code NbtAPI.compile} 走 LRU 缓存复用实例。
 *
 * <h3>规范化字符串</h3>
 * {@link #expression()} 重建唯一规范形式（吸收 JSON Pointer「规范化形式唯一、无歧义」思想），
 * 用于日志、去重与相等比较；{@code parse(expression()).expression()} 恒等往返。
 */
public final class NbtPath {

    /** 引擎共享实例（无状态、线程安全；Spring 容器内另有等价单例，行为一致）。 */
    private static final NbtPathMatcher MATCHER = new NbtPathMatcher();
    private static final NbtPathWriter WRITER = new NbtPathWriter(MATCHER);

    private final NbtPathSegment.CompoundFilter rootFilter;
    private final List<NbtPathSegment> segments;
    private final String expression;

    private NbtPath(NbtPathSegment.CompoundFilter rootFilter, List<NbtPathSegment> segments) {
        this.rootFilter = rootFilter;
        this.segments = List.copyOf(segments);
        this.expression = buildExpression(rootFilter, this.segments);
    }

    /**
     * 由段序列构造（包内工厂：解析器与写入引擎的 insert 父路径拆分使用）。
     *
     * @param rootFilter 根部复合过滤（可为 null）
     * @param segments   有序段列表（拷贝为不可变）
     */
    static NbtPath of(NbtPathSegment.CompoundFilter rootFilter, List<NbtPathSegment> segments) {
        return new NbtPath(rootFilter, segments);
    }

    /**
     * 解析路径表达式（每次解析产生新实例；高频路径请用 {@code NbtAPI.compile} 的缓存入口）。
     *
     * @param expression 路径表达式，如 {@code "Items[0].tag.display.Name"}
     * @throws NbtPathSyntaxException 表达式非法（消息含出错位置）
     */
    public static NbtPath compile(String expression) {
        return new NbtPathParser().parse(expression);
    }

    /** 根部复合过滤（无可为 null）。 */
    public NbtPathSegment.CompoundFilter rootFilter() {
        return rootFilter;
    }

    /** 有序段列表（不可变）。 */
    public List<NbtPathSegment> segments() {
        return segments;
    }

    /** 规范化字符串（用于日志/去重/相等比较）。 */
    public String expression() {
        return expression;
    }

    // ==================== 读取 ====================

    /** 求值：返回全部命中（按文档序；0 命中返回空列表，不抛异常——读宽容）。 */
    public List<Tag> select(Tag root) {
        return MATCHER.select(root, this);
    }

    /**
     * 恰好一个命中 → {@code Optional} 有值；0 个 → {@code empty}；
     * 多个 → 抛 {@link NbtPathNotFoundException}（要求恰一的严格读取形态）。
     */
    public Optional<Tag> selectSingle(Tag root) {
        List<Tag> hits = select(root);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() > 1) {
            throw new NbtPathNotFoundException("路径命中 " + hits.size() + " 个节点，要求恰好一个: "
                    + expression);
        }
        return Optional.of(hits.get(0));
    }

    /** 是否至少命中一个。 */
    public boolean matches(Tag root) {
        return !select(root).isEmpty();
    }

    // ==================== 写入（便捷实例方法，委托 NbtPathWriter；返回实际根，便于链式） ====================

    /** 替换全部命中（默认选项；类型校验与 createPath 见 {@link NbtWriteOption}）。 */
    public Tag set(Tag root, Tag value) {
        return set(root, value, NbtWriteOption.DEFAULT);
    }

    /** 替换全部命中（选项控制类型校验与 createPath）。 */
    public Tag set(Tag root, Tag value, NbtWriteOption option) {
        return WRITER.set(root, this, value, option);
    }

    /**
     * set 的 Java 值版本（表 4-1 推断 + autoFit；{@code value == null} 等价 {@link #delete}）。
     * <p>与 {@link #set(Tag, Tag)} 分开命名以避免 null 重载歧义。
     */
    public Tag setObject(Tag root, Object value) {
        return WRITER.setObject(root, this, value, NbtWriteOption.DEFAULT);
    }

    /** set 的 Java 值版本（选项控制 autoFit / createPath / listPolicy）。 */
    public Tag setObject(Tag root, Object value, NbtWriteOption option) {
        return WRITER.setObject(root, this, value, option);
    }

    /** List 末尾追加 / 指定下标插入（尾段为下标时在该处插入，原元素后移）。 */
    public Tag insert(Tag root, Tag value) {
        return WRITER.insert(root, this, value, NbtWriteOption.DEFAULT);
    }

    /** insert 的 Java 值版本（先按表 4-1 推断，Tag 直通）。 */
    public Tag insertObject(Tag root, Object value) {
        return WRITER.insertObject(root, this, value, NbtWriteOption.DEFAULT);
    }

    /** 深合并到全部命中的 Compound。 */
    public Tag merge(Tag root, CompoundTag patch) {
        return WRITER.merge(root, this, patch, NbtWriteOption.DEFAULT);
    }

    /** merge 的 Map 版本（Map → Compound 递归推断后深合并）。 */
    public Tag mergeObject(Tag root, Map<String, ?> patch) {
        return WRITER.mergeObject(root, this, patch, NbtWriteOption.DEFAULT);
    }

    /** 删除全部命中（幂等：0 命中静默成功）。 */
    public Tag delete(Tag root) {
        return WRITER.delete(root, this);
    }

    // ==================== 组合 ====================

    /**
     * 拼接子路径，返回新 {@code NbtPath}（Builder 风格组合）。
     *
     * @param subExpression 子路径表达式（不允许再带根部过滤）
     */
    public NbtPath resolve(String subExpression) {
        NbtPath sub = new NbtPathParser().parse(subExpression);
        if (sub.rootFilter() != null) {
            throw new IllegalArgumentException("子路径不允许携带根部过滤: " + subExpression);
        }
        List<NbtPathSegment> merged = new ArrayList<>(segments.size() + sub.segments.size());
        merged.addAll(segments);
        merged.addAll(sub.segments);
        return new NbtPath(rootFilter, merged);
    }

    /** 去掉最后一段，返回新 {@code NbtPath}（无段时抛 {@link IllegalStateException}）。 */
    public NbtPath parent() {
        if (segments.isEmpty()) {
            throw new IllegalStateException("路径已无段，不能再取 parent: " + expression);
        }
        return new NbtPath(rootFilter, segments.subList(0, segments.size() - 1));
    }

    // ==================== Object 协议 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NbtPath other)) {
            return false;
        }
        return Objects.equals(rootFilter, other.rootFilter) && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootFilter, segments);
    }

    @Override
    public String toString() {
        return expression;
    }

    // ==================== 规范化重建 ====================

    /** 规范化表达式重建：键段合法裸写否则加引号；过滤条目按声明序。 */
    private static String buildExpression(NbtPathSegment.CompoundFilter rootFilter,
                                          List<NbtPathSegment> segments) {
        StringBuilder sb = new StringBuilder();
        if (rootFilter != null) {
            appendFilter(sb, rootFilter.entries());
        }
        for (NbtPathSegment segment : segments) {
            switch (segment) {
                case NbtPathSegment.Key(var name) -> appendSegment(sb, name);
                case NbtPathSegment.Index(var index) -> sb.append('[').append(index).append(']');
                case NbtPathSegment.AllElements() -> sb.append("[]");
                case NbtPathSegment.CompoundFilter(var entries) -> {
                    sb.append('[');
                    appendFilter(sb, entries);
                    sb.append(']');
                }
                case NbtPathSegment.Predicate(var expr) -> sb.append("[?(").append(expr).append(")]");
            }
        }
        return sb.toString();
    }

    /** 追加键段：前文非空时补点分隔（前段结尾必为键名或 ']' 或 '}'，均需点）；键名合法裸写，否则引号包裹。 */
    private static void appendSegment(StringBuilder sb, String name) {
        if (sb.length() > 0) {
            sb.append('.');
        }
        sb.append(isBareKey(name) ? name : SnbtLiteral.quote(name));
    }

    /** 键名可裸写：非空且全部字符在裸标识符字符集内（含 {@code *} 等保留字符则须引号）。 */
    private static boolean isBareKey(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || ".[]{}\"*,:;?()@=!<>|&".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 追加复合过滤体 {@code {k:v,...}}（条目按声明序）。 */
    private static StringBuilder appendFilter(StringBuilder sb, Map<String, SnbtLiteral> entries) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, SnbtLiteral> e : entries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(isBareKey(e.getKey()) ? e.getKey() : SnbtLiteral.quote(e.getKey()))
                    .append(':')
                    .append(e.getValue().toSnbtString());
        }
        return sb.append('}');
    }
}
