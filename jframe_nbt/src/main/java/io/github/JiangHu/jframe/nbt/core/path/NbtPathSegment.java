package io.github.JiangHu.jframe.nbt.core.path;

import io.github.JiangHu.jframe.nbt.core.filter.SnbtLiteral;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NBT 路径段 —— 密封类型，一种语法构造对应一种段（DESIGN.md 3.3）。
 *
 * <p>与 jframe_command 的 {@code PathPattern.Segment}（Kind 枚举 + 扁平字段）不同：
 * NBT 路径段种类多且携带数据各异，改用 <b>sealed interface + record 子类</b>，
 * 求值引擎用 switch 模式匹配分派（Java 24）。
 *
 * <p>M1 语法子集产出 {@link Key}/{@link Index}/{@link AllElements}/{@link CompoundFilter}；
 * {@link Predicate} 为 M2 预留（段模型先行定义，M2 解析器/谓词编译器接入，
 * M1 引擎遇到即抛不支持异常）。
 */
public sealed interface NbtPathSegment {

    /**
     * 键段：{@code display} 或 {@code "weird.name"}（引号转义键名）。
     * <p>作用于 CompoundTag：取该键对应的值；键不存在 → 0 命中（读静默）。
     * name 为 {@code "*"} 时表示键通配（M2 语法，M1 解析器拒绝）。
     */
    record Key(String name) implements NbtPathSegment {
        public Key {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("键段 name 不可为空");
            }
        }
    }

    /**
     * 下标段：{@code [0]} / {@code [-1]}（负索引从尾部数，-1 = 最后一个）。
     * <p>作用于 ListTag / ByteArrayTag / IntArrayTag；越界 → 0 命中（读）/ 抛异常（写）。
     */
    record Index(int index) implements NbtPathSegment {
    }

    /**
     * 全体元素段：{@code []}。
     * <p>展开 ListTag / 数组类型的全部元素（等价于逐一下标的并集，按文档序）。
     */
    record AllElements() implements NbtPathSegment {
    }

    /**
     * 复合过滤段：{@code [{id:"x", Count:64b}]}。
     * <p>作用于 ListTag<CompoundTag>：选出<b>包含</b>全部给定键值（子集匹配、
     * <b>类型敏感</b>：{@code 64b} 不匹配 {@code IntTag(64)}）的 Compound 元素。
     * 亦用作根过滤（路径最前的独立 {@code {k:v}}，对整棵根做子集匹配）。
     *
     * @param entries 过滤条目（不可变、按声明序）
     */
    record CompoundFilter(Map<String, SnbtLiteral> entries) implements NbtPathSegment {
        public CompoundFilter {
            // 不可变但保序：Map.copyOf 不保迭代序（hash 桶序会打乱声明序，
            // 破坏 expression() 往返与"条目按声明序"契约），故用 LinkedHashMap + unmodifiableMap
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /**
     * 比较谓词段：{@code [?(@.Amount >= 0.1)]}（JSONPath 血统）。
     * <p><b>M2 预留</b>：段模型先行占位（保持 sealed 结构与 DESIGN.md 3.3 一致），
     * M1 解析器不产出本段、引擎遇到即抛 {@code NbtPathSyntaxException}；
     * M2 由 PredicateCompiler 接入（数值提升语义见 4.5）。
     */
    record Predicate(String expression) implements NbtPathSegment {
    }
}
