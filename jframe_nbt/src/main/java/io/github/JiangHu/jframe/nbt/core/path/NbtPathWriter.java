package io.github.JiangHu.jframe.nbt.core.path;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.NumberTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtModifyException;
import io.github.JiangHu.jframe.nbt.core.NbtPathTypeException;
import io.github.JiangHu.jframe.nbt.core.NbtTypeMismatchException;
import io.github.JiangHu.jframe.nbt.core.value.CoerceMode;
import io.github.JiangHu.jframe.nbt.core.value.NbtValues;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NBT 路径写入引擎 —— set / insert / merge / delete（DESIGN.md 2.5 写入语义细则）。
 *
 * <h3>读宽容、写严格</h3>
 * 读取侧类型冲突静默 0 命中（见 {@link NbtPathMatcher}）；写入侧默认严格：
 * <ul>
 *   <li>{@code set} 0 命中默认抛 {@link NbtModifyException}（fail-fast，开放问题 #4 建议默认值，
 *       可在 M2 复议）；{@code NbtWriteOption.createPath=true} 时对「全键段路径」自动创建中间
 *       Compound 后写入（吸收 JSON Patch {@code add} 语义）；</li>
 *   <li>{@code set} 替换默认类型校验（防 {@code 1b} 被写成 {@code 1}），{@code typeCheck=false} 关闭；</li>
 *   <li>{@code insert} 只作用于 ListTag：尾段为下标 = 该处插入（元素后移），否则末尾追加；
 *       下标越界默认抛，{@code append=true} 改为末尾追加；</li>
 *   <li>{@code merge} 仅 Compound：同名键双方皆 Compound 递归合并，否则覆盖；</li>
 *   <li>{@code delete} 幂等静默（0 命中不抛）；List 元素删除按同容器下标<b>降序</b>执行防错位。</li>
 * </ul>
 *
 * <h3>数组元素写入（DESIGN.md 2.2）</h3>
 * {@code ByteArrayTag}/{@code IntArrayTag} 的元素 set 按原数组元素类型<b>收窄</b>（byte/int）；
 * 数组元素不支持 delete（长度语义模糊，M1 限制，见 DEVELOPER.md 偏差登记）。
 *
 * <p>本类无状态（仅持只读依赖）、线程安全，可作 Spring 单例。
 */
public final class NbtPathWriter {

    private final NbtPathMatcher matcher;

    public NbtPathWriter(NbtPathMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher 不能为 null");
    }

    // ==================== set ====================

    /**
     * 替换全部命中（0 命中依 createPath；类型校验依 typeCheck）。
     *
     * @return 实际根（便于链式）
     */
    public Tag set(Tag root, NbtPath path, Tag value, NbtWriteOption option) {
        if (value == null) {
            throw new NullPointerException("set 的 value 不能为 null（删除请用 delete）");
        }
        List<NbtPathMatcher.Match> matches = matcher.match(root, path);
        if (matches.isEmpty()) {
            if (option.createPath()) {
                createAndSet(root, path, value);
                return root;
            }
            throw new NbtModifyException("set 0 命中且未开启 createPath（默认 fail-fast，"
                    + "开放问题 #4 建议默认值，可在 M2 复议）: " + path.expression());
        }
        for (NbtPathMatcher.Match m : matches) {
            if (m.parent() == null) {
                // 根命中的结构性拒绝先于类型校验（避免掩盖真实原因）
                throw new NbtModifyException("不能对根节点自身执行 set（无父容器）: " + path.expression());
            }
            if (option.typeCheck() && !isArrayElement(m) && m.tag().getId() != value.getId()) {
                throw new NbtPathTypeException("set 类型校验失败: 旧值 "
                        + m.tag().getClass().getSimpleName() + "，新值 " + value.getClass().getSimpleName()
                        + "（防 1b 被写成 1 破坏游戏行为；确认无误可用 NbtWriteOption.withTypeCheck(false) 关闭）");
            }
            replace(m, value);
        }
        return root;
    }

    /** 数组元素命中（父容器为 ByteArray/IntArray）：replace 内按容器元素类型收窄，Tag 级类型校验不适用。 */
    private static boolean isArrayElement(NbtPathMatcher.Match m) {
        return m.parent() instanceof ByteArrayTag || m.parent() instanceof IntArrayTag;
    }

    /**
     * set 的 Object 重载（表 4-1 写入推断 + autoFit 旧值无损适配，DESIGN.md 4.2）。
     * <p>{@code value == null} 等同 {@link #delete}。
     */
    public Tag setObject(Tag root, NbtPath path, Object value, NbtWriteOption option) {
        if (value == null) {
            return delete(root, path);
        }
        Tag inferred = NbtValues.of(value, CoerceMode.LENIENT, option.listPolicy());
        List<NbtPathMatcher.Match> matches = matcher.match(root, path);
        if (matches.isEmpty()) {
            if (option.createPath()) {
                createAndSet(root, path, inferred);
                return root;
            }
            throw new NbtModifyException("set 0 命中且未开启 createPath（默认 fail-fast，"
                    + "开放问题 #4 建议默认值，可在 M2 复议）: " + path.expression());
        }
        for (NbtPathMatcher.Match m : matches) {
            Tag effective = inferred;
            if (isArrayElement(m)) {
                // 数组元素：replace 内按容器元素类型收窄（STRICT 数值校验），跳过 Tag 级适配
            } else if (option.autoFit()) {
                Tag fitted = NbtValues.autoFit(inferred, m.tag());
                if (fitted == null) {
                    throw new NbtTypeMismatchException("autoFit 失败：旧值类型无法无损容纳新值（旧 "
                            + m.tag().getClass().getSimpleName() + "，新 " + inferred.getClass().getSimpleName() + "）",
                            "与旧值同类型", m.tag());
                }
                effective = fitted;
            } else if (option.typeCheck() && m.tag().getId() != inferred.getId()) {
                throw new NbtPathTypeException("set 类型校验失败: 旧值 "
                        + m.tag().getClass().getSimpleName() + "，推断值 " + inferred.getClass().getSimpleName()
                        + "（autoFit 已关闭；可开启 NbtWriteOption.withAutoFit(true) 按旧类型无损适配）");
            }
            replace(m, effective);
        }
        return root;
    }

    /** 就地替换单个命中（按父容器类型分派；ListTag 无 set(int,T)，用 remove+add 组合）。 */
    private void replace(NbtPathMatcher.Match m, Tag value) {
        if (m.parent() == null || m.slot() == null) {
            throw new NbtModifyException("不能对根节点自身执行 set（无父容器）");
        }
        if (m.slot() instanceof NbtPathMatcher.KeySlot(var name)) {
            ((CompoundTag) m.parent()).put(name, value);
        } else {
            int index = ((NbtPathMatcher.IndexSlot) m.slot()).index();
            if (m.parent() instanceof ListTag<?> l) {
                // Nukkit ListTag.add(int,T) 在 index<size 时是 set 语义（javap 验证），
                // remove+add 组合在中间索引会丢元素，故经 getAllUnsafe() 直接操作内部列表
                asTagList(l).getAllUnsafe().set(index, value);
            } else if (m.parent() instanceof ByteArrayTag array) {
                array.data[index] = (byte) NbtValues.asInt(value, CoerceMode.STRICT);
            } else if (m.parent() instanceof IntArrayTag array) {
                array.data[index] = NbtValues.asInt(value, CoerceMode.STRICT);
            } else {
                throw new NbtPathTypeException("不支持的替换容器: " + m.parent().getClass().getSimpleName());
            }
        }
    }

    /** createPath：仅「根为 Compound + 全键段路径」可逐层创建；根过滤不匹配时拒绝（防在错误的树上建）。 */
    private void createAndSet(Tag root, NbtPath path, Tag value) {
        if (!(root instanceof CompoundTag compoundRoot)) {
            throw new NbtModifyException("根节点不是 CompoundTag，无法自动创建路径: " + path.expression());
        }
        NbtPathSegment.CompoundFilter rootFilter = path.rootFilter();
        if (rootFilter != null && !NbtPathMatcher.matchesFilter(compoundRoot, rootFilter.entries())) {
            throw new NbtModifyException("根过滤不匹配，拒绝自动创建路径（根过滤是对目标树的断言）: "
                    + path.expression());
        }
        List<NbtPathSegment> segments = path.segments();
        if (!(segments.get(segments.size() - 1) instanceof NbtPathSegment.Key(var lastName))) {
            throw new NbtModifyException("路径 0 命中且尾段不是键段，无法自动创建: " + path.expression()
                    + "（仅键段路径支持 createPath）");
        }
        CompoundTag current = compoundRoot;
        for (int i = 0; i < segments.size() - 1; i++) {
            if (!(segments.get(i) instanceof NbtPathSegment.Key(var name))) {
                throw new NbtModifyException("路径 0 命中且中间段不是键段，无法自动创建: " + path.expression());
            }
            Tag child = current.get(name);
            if (child == null) {
                CompoundTag created = new CompoundTag("");
                current.put(name, created);
                current = created;
            } else if (child instanceof CompoundTag compound) {
                current = compound;
            } else {
                throw new NbtModifyException("中间节点已存在且不是 CompoundTag，无法继续创建: "
                        + path.expression());
            }
        }
        current.put(lastName, value);
    }

    // ==================== insert ====================

    /**
     * 插入元素（只作用于 ListTag，DESIGN.md 2.5）：
     * 尾段为下标 = 在该处插入（原元素后移，负索引归一，越界依 append）；
     * 其余路径 = 命中的每个 ListTag 末尾追加。
     */
    public Tag insert(Tag root, NbtPath path, Tag value, NbtWriteOption option) {
        if (value == null) {
            throw new NullPointerException("insert 的 value 不能为 null");
        }
        List<NbtPathSegment> segments = path.segments();
        if (!segments.isEmpty() && segments.get(segments.size() - 1) instanceof NbtPathSegment.Index(var raw)) {
            NbtPath parentPath = NbtPath.of(path.rootFilter(), segments.subList(0, segments.size() - 1));
            List<NbtPathMatcher.Match> parents = matcher.match(root, parentPath);
            if (parents.isEmpty()) {
                throw new NbtModifyException("insert 目标不存在（父路径 0 命中）: " + path.expression());
            }
            for (NbtPathMatcher.Match p : parents) {
                if (!(p.tag() instanceof ListTag<?> l)) {
                    throw new NbtPathTypeException("insert 目标不是 ListTag: "
                            + p.tag().getClass().getSimpleName() + "（路径 " + path.expression() + "）");
                }
                ListTag<Tag> list = asTagList(l);
                int index = raw < 0 ? raw + list.size() : raw;
                if (index < 0 || index > list.size()) {
                    if (option.append()) {
                        list.add(value);
                        continue;
                    }
                    throw new NbtModifyException("insert 下标越界: " + raw + "（列表长度 " + list.size()
                            + "；如需越界追加可用 NbtWriteOption.withAppend(true)）");
                }
                // Nukkit ListTag.add(int,T) 在 index<size 时是 set 语义（javap 验证），
                // 经 getAllUnsafe() 取内部列表执行标准 List.add(int,E) 真插入
                list.getAllUnsafe().add(index, value);
            }
            return root;
        }
        List<NbtPathMatcher.Match> matches = matcher.match(root, path);
        if (matches.isEmpty()) {
            throw new NbtModifyException("insert 目标不存在（路径 0 命中，insert 不支持 createPath）: "
                    + path.expression());
        }
        for (NbtPathMatcher.Match m : matches) {
            if (!(m.tag() instanceof ListTag<?> l)) {
                throw new NbtPathTypeException("insert 目标不是 ListTag: "
                        + m.tag().getClass().getSimpleName() + "（路径 " + path.expression() + "）");
            }
            asTagList(l).add(value);
        }
        return root;
    }

    /** insert 的 Object 重载：Java 值先按表 4-1 推断（Tag 直通），再走 {@link #insert}。 */
    public Tag insertObject(Tag root, NbtPath path, Object value, NbtWriteOption option) {
        if (value == null) {
            throw new NullPointerException("insert 的 value 不能为 null");
        }
        return insert(root, path, NbtValues.of(value, CoerceMode.LENIENT, option.listPolicy()), option);
    }

    // ==================== merge ====================

    /** 深合并到全部命中的 Compound（0 命中依 createPath；非 Compound 抛）。 */
    public Tag merge(Tag root, NbtPath path, CompoundTag patch, NbtWriteOption option) {
        List<NbtPathMatcher.Match> matches = matcher.match(root, path);
        if (matches.isEmpty()) {
            if (option.createPath()) {
                createAndSet(root, path, patch.copy());
                return root;
            }
            throw new NbtModifyException("merge 0 命中且未开启 createPath: " + path.expression());
        }
        for (NbtPathMatcher.Match m : matches) {
            if (!(m.tag() instanceof CompoundTag target)) {
                throw new NbtModifyException("merge 目标不是 CompoundTag: "
                        + m.tag().getClass().getSimpleName() + "（路径 " + path.expression() + "）");
            }
            deepMerge(target, patch);
        }
        return root;
    }

    /** merge 的 Map 重载：Map → CompoundTag 递归推断（表 4-1）后深合并。 */
    public Tag mergeObject(Tag root, NbtPath path, Map<String, ?> patch, NbtWriteOption option) {
        Tag inferred = NbtValues.of(patch, CoerceMode.LENIENT, option.listPolicy());
        if (!(inferred instanceof CompoundTag compound)) {
            throw new NbtTypeMismatchException("merge 的 Map 推断结果不是 CompoundTag（内部错误）");
        }
        return merge(root, path, compound, option);
    }

    /** 深合并：同名键双方皆 Compound → 递归合并；否则覆盖（值拷贝，防共享引用）。 */
    private void deepMerge(CompoundTag target, CompoundTag patch) {
        for (Map.Entry<String, Tag> e : patch.getTags().entrySet()) {
            Tag existing = target.get(e.getKey());
            if (existing instanceof CompoundTag ec && e.getValue() instanceof CompoundTag pc) {
                deepMerge(ec, pc);
            } else {
                target.put(e.getKey(), e.getValue().copy());
            }
        }
    }

    // ==================== delete ====================

    /**
     * 删除全部命中（幂等：0 命中静默成功）。
     * List 元素按同容器下标<b>降序</b>删除（防正序删除导致的下标错位）；
     * 数组元素不支持 delete（M1 限制）；根命中无父容器抛 {@link NbtModifyException}。
     */
    public Tag delete(Tag root, NbtPath path) {
        List<NbtPathMatcher.Match> matches = matcher.match(root, path);
        // 先执行 Compound 键删除（无顺序问题）
        for (NbtPathMatcher.Match m : matches) {
            if (m.slot() instanceof NbtPathMatcher.KeySlot(var name)) {
                ((CompoundTag) m.parent()).remove(name);
            }
        }
        // List 元素删除：按容器分组、组内下标降序
        Map<ListTag<?>, List<Integer>> byList = new IdentityHashMap<>();
        for (NbtPathMatcher.Match m : matches) {
            if (m.slot() instanceof NbtPathMatcher.IndexSlot(var index)) {
                if (m.parent() instanceof ListTag<?> list) {
                    byList.computeIfAbsent(list, k -> new ArrayList<>()).add(index);
                } else {
                    throw new NbtPathTypeException("数组元素不支持 delete（ByteArray/IntArray 长度语义模糊，"
                            + "M1 限制；如需清空请 set 整个数组）: " + path.expression());
                }
            }
        }
        for (Map.Entry<ListTag<?>, List<Integer>> e : byList.entrySet()) {
            List<Integer> indexes = e.getValue();
            indexes.sort((a, b) -> Integer.compare(b, a)); // 降序
            for (int i : indexes) {
                e.getKey().remove(i);
            }
        }
        // 根命中（空路径/根过滤单独成路径）：无父容器
        for (NbtPathMatcher.Match m : matches) {
            if (m.parent() == null && m.slot() == null) {
                throw new NbtModifyException("不能对根节点自身执行 delete（无父容器）");
            }
        }
        return root;
    }

    /**
     * ListTag 元素静态类型统一为 {@code Tag}：通配符 capture 下无法直接 {@code add(Tag)}，
     * 擦除后运行时同型（Nukkit ListTag 本就装 Tag），转换安全。
     */
    @SuppressWarnings("unchecked")
    private static ListTag<Tag> asTagList(ListTag<?> list) {
        return (ListTag<Tag>) list;
    }
}
