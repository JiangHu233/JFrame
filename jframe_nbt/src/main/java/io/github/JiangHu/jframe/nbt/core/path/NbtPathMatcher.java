package io.github.JiangHu.jframe.nbt.core.path;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtPathSyntaxException;
import io.github.JiangHu.jframe.nbt.core.filter.SnbtLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NBT 路径读取引擎 —— 段序列 × Tag → <b>命中集合</b>（DESIGN.md 2.2 多匹配语义）。
 *
 * <h3>求值模型</h3>
 * 从根出发（根过滤不匹配则 0 命中），逐段展开：每段作用于当前全部命中节点，
 * 产出下一层命中；结果按<b>文档序</b>（容器内自然顺序）排列。
 *
 * <h3>读宽容（DESIGN.md 2.5）</h3>
 * 段类型与节点类型冲突（对 IntTag 用键导航、对 Compound 用下标）、键不存在、
 * 下标越界 → 一律 <b>0 命中</b>，不抛异常（探测式编程友好）；写入侧的严格语义见
 * {@link NbtPathWriter}。
 *
 * <h3>数组类型（DESIGN.md 2.2）</h3>
 * {@code ByteArrayTag}/{@code IntArrayTag} 视作数值列表：{@code [i]}/{@code []} 寻址，
 * 元素以 {@code ByteTag}/{@code IntTag} 包装命中（只读视图；写入按原数组元素类型收窄）。
 *
 * <p>本类无状态、线程安全，可作 Spring 单例。
 */
public final class NbtPathMatcher {

    /** 求值：返回全部命中的 Tag（按文档序；0 命中返回空列表，不抛异常）。 */
    public List<Tag> select(Tag root, NbtPath path) {
        List<Match> matches = match(root, path);
        List<Tag> result = new ArrayList<>(matches.size());
        for (Match m : matches) {
            result.add(m.tag());
        }
        return result;
    }

    /**
     * 求值并携带父容器定位（供 {@link NbtPathWriter} 复用同一套展开逻辑定位写回位置）。
     *
     * @return 命中列表；根命中（根过滤/空路径）的 parent 与 slot 为 null
     */
    List<Match> match(Tag root, NbtPath path) {
        List<Match> matches = new ArrayList<>();
        NbtPathSegment.CompoundFilter rootFilter = path.rootFilter();
        if (rootFilter == null) {
            matches.add(new Match(null, root, null));
        } else if (root instanceof CompoundTag compound && matchesFilter(compound, rootFilter.entries())) {
            matches.add(new Match(null, root, null));
        }
        for (NbtPathSegment segment : path.segments()) {
            if (matches.isEmpty()) {
                break; // 短路：上一层已 0 命中
            }
            List<Match> next = new ArrayList<>();
            for (Match m : matches) {
                expand(m, segment, next);
            }
            matches = next;
        }
        return matches;
    }

    /** 单段展开：把段应用到命中节点，产出下一层命中（读宽容：类型冲突静默 0 命中）。 */
    private void expand(Match m, NbtPathSegment segment, List<Match> out) {
        switch (segment) {
            case NbtPathSegment.Key(var name) -> {
                if (m.tag() instanceof CompoundTag compound) {
                    Tag child = compound.get(name);
                    if (child != null) {
                        out.add(new Match(m.tag(), child, new KeySlot(name)));
                    }
                }
            }
            case NbtPathSegment.Index(var raw) -> {
                if (m.tag() instanceof ListTag<?> list) {
                    int i = normalizeIndex(raw, list.size());
                    if (i >= 0) {
                        out.add(new Match(m.tag(), list.get(i), new IndexSlot(i)));
                    }
                } else if (m.tag() instanceof ByteArrayTag array) {
                    int i = normalizeIndex(raw, array.data.length);
                    if (i >= 0) {
                        out.add(new Match(m.tag(), new ByteTag("", array.data[i]), new IndexSlot(i)));
                    }
                } else if (m.tag() instanceof IntArrayTag array) {
                    int i = normalizeIndex(raw, array.data.length);
                    if (i >= 0) {
                        out.add(new Match(m.tag(), new IntTag("", array.data[i]), new IndexSlot(i)));
                    }
                }
            }
            case NbtPathSegment.AllElements() -> {
                if (m.tag() instanceof ListTag<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        out.add(new Match(m.tag(), list.get(i), new IndexSlot(i)));
                    }
                } else if (m.tag() instanceof ByteArrayTag array) {
                    for (int i = 0; i < array.data.length; i++) {
                        out.add(new Match(m.tag(), new ByteTag("", array.data[i]), new IndexSlot(i)));
                    }
                } else if (m.tag() instanceof IntArrayTag array) {
                    for (int i = 0; i < array.data.length; i++) {
                        out.add(new Match(m.tag(), new IntTag("", array.data[i]), new IndexSlot(i)));
                    }
                }
            }
            case NbtPathSegment.CompoundFilter(var entries) -> {
                if (m.tag() instanceof ListTag<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        Tag element = list.get(i);
                        if (element instanceof CompoundTag compound && matchesFilter(compound, entries)) {
                            out.add(new Match(m.tag(), element, new IndexSlot(i)));
                        }
                    }
                }
            }
            case NbtPathSegment.Predicate(var expr) ->
                    throw new NbtPathSyntaxException("比较谓词 [?()] 为 M2 特性，当前版本不支持", expr, -1);
        }
    }

    /** 负索引归一：返回合法下标；越界返回 -1（读静默 0 命中）。 */
    private static int normalizeIndex(int raw, int size) {
        int i = raw < 0 ? raw + size : raw;
        return (i >= 0 && i < size) ? i : -1;
    }

    /**
     * 复合过滤的<b>子集匹配</b>（类型敏感，DESIGN.md 2.2）：Compound 须包含全部给定键，
     * 且各键值与字面量「NBT 类型一致 + 值相等」（{@code 1b} 不匹配 {@code IntTag(1)}）。
     * 空条目集匹配任意 Compound。
     */
    static boolean matchesFilter(CompoundTag compound, Map<String, SnbtLiteral> entries) {
        for (Map.Entry<String, SnbtLiteral> e : entries.entrySet()) {
            Tag value = compound.get(e.getKey());
            if (value == null || !e.getValue().matches(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 命中项：命中 Tag + 其在父容器中的定位（写回/删除用）。
     * 根命中（根过滤/空路径）parent 与 slot 为 null。
     */
    record Match(Tag parent, Tag tag, Slot slot) {
    }

    /** 命中位置：Compound 键或 List/数组下标。 */
    sealed interface Slot permits KeySlot, IndexSlot {
    }

    record KeySlot(String name) implements Slot {
    }

    record IndexSlot(int index) implements Slot {
    }
}
