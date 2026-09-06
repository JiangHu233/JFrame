package io.github.JiangHu.jframe.nbt.core.value;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.NumberTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtTypeMismatchException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tag ↔ Java 值静态工具 —— 自动类型识别层的核心（DESIGN.md 第四章 4.2/4.3）。
 *
 * <h3>写入方向：{@link #of(Object)}（表 4-1 契约）</h3>
 * <table border="1">
 * <caption>Java 值 → NBT Tag 推断映射</caption>
 * <tr><th>Java 值</th><th>推断结果</th></tr>
 * <tr><td>{@link Tag}</td><td>原样直通（幂等）</td></tr>
 * <tr><td>{@link Boolean}</td><td>{@code ByteTag(1/0)}</td></tr>
 * <tr><td>Byte/Short/Integer/Long/Float/Double</td><td>对应宽度 Tag</td></tr>
 * <tr><td>{@link String}</td><td>{@code StringTag}（不做数字猜测）</td></tr>
 * <tr><td>byte[] / int[]</td><td>{@code ByteArrayTag} / {@code IntArrayTag}</td></tr>
 * <tr><td>long[]</td><td>平台无 LongArrayTag → 抛 {@link NbtTypeMismatchException}（DESIGN.md 9.4 降级）</td></tr>
 * <tr><td>short[]/float[]/double[]/boolean[]/Object[]</td><td>{@code ListTag}（元素递归推断 + 归一）</td></tr>
 * <tr><td>Map<String,?></td><td>{@code CompoundTag}（值递归；null 值跳过该键）</td></tr>
 * <tr><td>List<?></td><td>{@code ListTag}（元素递归 + 类型归一）</td></tr>
 * <tr><td>BigInteger</td><td>{@code LongTag}（超 long 值域抛异常）</td></tr>
 * <tr><td>BigDecimal</td><td>{@code DoubleTag}（STRICT 丢精度抛 / LENIENT 转 double）</td></tr>
 * <tr><td>其他类型</td><td>抛 {@link NbtTypeMismatchException}（不做反射/toString 魔法）</td></tr>
 * </table>
 *
 * <h3>List 元素类型归一（NBT 规范要求 ListTag 同质）</h3>
 * <ol>
 *   <li>全部元素推断为同一 Tag 类型 → 直接成表；</li>
 *   <li>全部为数值 Tag（Boolean 先按 ByteTag 计入）→ 提升到最宽公共类型：
 *       任一 Double → Double；否则任一 Float → Float；否则任一 Long → Long；
 *       否则（Byte/Short/Int 混合）→ Int；</li>
 *   <li>含非数值异构 → 默认抛 {@link NbtTypeMismatchException}；
 *       {@code NbtWriteOption.listPolicy = ACCEPT_HETEROGENEOUS} 显式放行；</li>
 *   <li>空集合 → 空 ListTag。</li>
 * </ol>
 *
 * <h3>读取方向：{@link #unwrap(Tag)} / {@code asXxx} / {@link #toJavaValue(Tag)}</h3>
 * <ul>
 *   <li>{@link #unwrap}：原生解包（按 Tag 自身宽度：ByteTag→Byte、IntTag→Integer…，
 *       Compound→浅 Map、List→浅 List）；</li>
 *   <li>{@code asXxx(tag[, mode])}：数值宽化无损自动（表 4-3），窄化/不可转换依
 *       {@link CoerceMode}（STRICT 抛 / LENIENT 截断或类型零值）；</li>
 *   <li>{@link #toJavaValue}：深解包为嵌套 Map/List/标量的纯 Java 结构（消费视图，
 *       丢失 NBT 类型信息；往返保真请用 jframe_inventory 的 NbtJsonConverter）。</li>
 * </ul>
 *
 * <h3>null 的三类边界（对齐 NbtJsonConverter 实践）</h3>
 * Map 值为 null → 跳过该键；List 元素为 null → 抛异常（位置有语义，静默跳过会错位）；
 * 顶层 set(path, null) → 由 API 层转为 delete（本类 {@link #of(Object)} 对 null 抛 NPE）。
 *
 * <p>本类全部方法线程安全（无状态静态工具）。
 */
public final class NbtValues {

    private NbtValues() {
    }

    // ==================== 写入方向：Java 值 → Tag ====================

    /**
     * 写入推断（表 4-1）：Java 值 → NBT Tag。
     *
     * @param value 原生 Java 值（不可为 null；顶层 null 请用 delete 语义）
     * @return 推断出的 Tag
     * @throws NbtTypeMismatchException 类型不受支持 / 值域越界 / 异构 List 拒绝
     */
    public static Tag of(Object value) {
        return of(value, CoerceMode.LENIENT, ListPolicy.REJECT);
    }

    /** 写入推断 + 转换模式（影响 BigDecimal 丢精度行为）。 */
    public static Tag of(Object value, CoerceMode mode) {
        return of(value, mode, ListPolicy.REJECT);
    }

    /**
     * 写入推断全参版（引擎内部使用）：List 归一策略由写入选项传入。
     *
     * @param listPolicy List 元素异构策略（REJECT / ACCEPT_HETEROGENEOUS）
     */
    public static Tag of(Object value, CoerceMode mode, ListPolicy listPolicy) {
        if (value == null) {
            throw new NullPointerException("写入值不能为 null（顶层 null 请使用 delete 语义）");
        }
        if (value instanceof Tag t) {
            return t; // 幂等直通
        }
        if (value instanceof Boolean b) {
            return new ByteTag("", b ? 1 : 0);
        }
        if (value instanceof Byte v) {
            return new ByteTag("", v);
        }
        if (value instanceof Short v) {
            return new ShortTag("", v);
        }
        if (value instanceof Integer v) {
            return new IntTag("", v);
        }
        if (value instanceof Long v) {
            return new LongTag("", v);
        }
        if (value instanceof Float v) {
            return new FloatTag("", v);
        }
        if (value instanceof Double v) {
            return new DoubleTag("", v);
        }
        if (value instanceof String s) {
            return new StringTag("", s);
        }
        if (value instanceof BigInteger v) {
            try {
                return new LongTag("", v.longValueExact());
            } catch (ArithmeticException e) {
                throw new NbtTypeMismatchException("BigInteger 超出 long 值域: " + v);
            }
        }
        if (value instanceof BigDecimal v) {
            double d = v.doubleValue();
            // 判据用 new BigDecimal(d)（double 的精确二进制展开），而非 Double.toString 的最短表示
            //（最短表示会让 0.1 这类不可精确表示的值"假通过"STRICT 检查）
            if (!Double.isFinite(d) || new BigDecimal(d).compareTo(v) != 0) {
                if (mode == CoerceMode.STRICT) {
                    throw new NbtTypeMismatchException("BigDecimal 转 double 丢失精度（STRICT 拒绝）: " + v);
                }
                // LENIENT：截断接受
            }
            return new DoubleTag("", d);
        }
        if (value instanceof byte[] a) {
            return new ByteArrayTag("", a);
        }
        if (value instanceof int[] a) {
            return new IntArrayTag("", a);
        }
        if (value instanceof long[]) {
            // Nukkit MOT 无 LongArrayTag（DESIGN.md 9.4 预判），按文档降级为抛异常
            throw new NbtTypeMismatchException("平台无 LongArrayTag（Nukkit MOT），long[] 不受支持");
        }
        // 其他数组 → ListTag（元素递归推断 + 归一）
        if (value instanceof boolean[] a) {
            List<Object> boxed = new ArrayList<>(a.length);
            for (boolean b : a) boxed.add(b);
            return ofList(boxed, listPolicy);
        }
        if (value instanceof short[] a) {
            List<Object> boxed = new ArrayList<>(a.length);
            for (short s : a) boxed.add(s);
            return ofList(boxed, listPolicy);
        }
        if (value instanceof float[] a) {
            List<Object> boxed = new ArrayList<>(a.length);
            for (float f : a) boxed.add(f);
            return ofList(boxed, listPolicy);
        }
        if (value instanceof double[] a) {
            List<Object> boxed = new ArrayList<>(a.length);
            for (double d : a) boxed.add(d);
            return ofList(boxed, listPolicy);
        }
        if (value instanceof Object[] a) {
            return ofList(List.of(a), listPolicy);
        }
        if (value instanceof Map<?, ?> m) {
            CompoundTag compound = new CompoundTag("");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new NbtTypeMismatchException("Map 键必须为 String，实际: "
                            + (e.getKey() == null ? "null" : e.getKey().getClass().getSimpleName()));
                }
                if (e.getValue() == null) {
                    continue; // null 值跳过该键（对齐 NbtJsonConverter 跳过 JSON null）
                }
                compound.put(key, of(e.getValue(), mode, listPolicy));
            }
            return compound;
        }
        if (value instanceof List<?> l) {
            return ofList(l, listPolicy);
        }
        throw new NbtTypeMismatchException("不支持的写入类型: " + value.getClass().getName()
                + "（请先显式转换为受支持类型，映射表见 NbtValues javadoc / README）");
    }

    /**
     * List → ListTag（元素递归推断 + 归一，规则见类 javadoc）。
     *
     * @param list       元素列表（元素不可为 null）
     * @param listPolicy 异构策略
     */
    public static ListTag<Tag> ofList(List<?> list, ListPolicy listPolicy) {
        ListTag<Tag> result = new ListTag<>("");
        if (list.isEmpty()) {
            return result; // 空集合 → 空 ListTag（元素类型由后续 insert 决定）
        }
        List<Tag> tags = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                throw new NbtTypeMismatchException("List 元素不能为 null（列表位置有语义，静默跳过会错位）");
            }
            tags.add(of(element, CoerceMode.LENIENT, listPolicy));
        }
        // 归一 1：全部同类型 → 直接成表
        Class<?> first = tags.get(0).getClass();
        boolean sameType = true;
        for (Tag t : tags) {
            if (t.getClass() != first) {
                sameType = false;
                break;
            }
        }
        if (sameType) {
            tags.forEach(result::add);
            return result;
        }
        // 归一 2：全部数值 → 提升最宽公共类型
        boolean allNumeric = true;
        for (Tag t : tags) {
            if (!(t instanceof NumberTag<?>)) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            boolean hasDouble = false, hasFloat = false, hasLong = false;
            for (Tag t : tags) {
                if (t instanceof DoubleTag) hasDouble = true;
                else if (t instanceof FloatTag) hasFloat = true;
                else if (t instanceof LongTag) hasLong = true;
            }
            for (Tag t : tags) {
                result.add(widenTo(t, hasDouble ? NbtValueType.DOUBLE
                        : hasFloat ? NbtValueType.FLOAT
                        : hasLong ? NbtValueType.LONG
                        : NbtValueType.INT));
            }
            return result;
        }
        // 归一 3：非数值异构 → 依策略
        if (listPolicy == ListPolicy.ACCEPT_HETEROGENEOUS) {
            tags.forEach(result::add);
            return result;
        }
        throw new NbtTypeMismatchException("List 元素类型异构（NBT ListTag 要求同质）: "
                + describeElementTypes(tags) + "；如确需混存可用 NbtWriteOption.listPolicy = ACCEPT_HETEROGENEOUS 放行");
    }

    /** 数值 Tag 提升到目标宽度（归一内部用，调用方保证数值）。 */
    private static Tag widenTo(Tag t, NbtValueType target) {
        Number n = ((NumberTag<?>) t).getData();
        return switch (target) {
            case DOUBLE -> new DoubleTag("", n.doubleValue());
            case FLOAT -> new FloatTag("", n.floatValue());
            case LONG -> new LongTag("", n.longValue());
            case INT -> new IntTag("", n.intValue());
            default -> throw new NbtTypeMismatchException("非法的数值提升目标: " + target);
        };
    }

    private static String describeElementTypes(List<Tag> tags) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(tags.get(i).getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }

    // ==================== autoFit：旧值类型无损适配（4.2） ====================

    /**
     * autoFit：将推断结果按<b>旧值类型</b>无损重建（「读出 → 修改 → 写回」往返不改变原树类型布局）。
     *
     * <p>例：旧 {@code ByteTag} + 推断 {@code IntTag(32)} → 返回 {@code ByteTag(32)}；
     * 旧 {@code FloatTag} + 推断 {@code DoubleTag(0.5)} → 返回 {@code FloatTag(0.5f)}。
     *
     * @param inferred 按表 4-1 推断出的新值 Tag
     * @param oldTag   命中位置的旧值 Tag
     * @return 按旧类型重建的 Tag；<b>不可无损容纳时返回 null</b>（调用方抛
     *         {@link NbtTypeMismatchException}）
     */
    public static Tag autoFit(Tag inferred, Tag oldTag) {
        byte from = inferred.getId();
        byte to = oldTag.getId();
        if (from == to) {
            return inferred;
        }
        if (!(inferred instanceof NumberTag<?> num)) {
            return null; // 非数值类型之间不做适配（String/Compound 等类型不同即失败）
        }
        return switch (to) {
            case Tag.TAG_Byte -> fitInt(num, Byte.MIN_VALUE, Byte.MAX_VALUE)
                    ? new ByteTag("", num.getData().intValue()) : null;
            case Tag.TAG_Short -> fitInt(num, Short.MIN_VALUE, Short.MAX_VALUE)
                    ? new ShortTag("", num.getData().intValue()) : null;
            case Tag.TAG_Int -> fitInt(num, Integer.MIN_VALUE, Integer.MAX_VALUE)
                    ? new IntTag("", num.getData().intValue()) : null;
            case Tag.TAG_Long -> fitLong(num) ? new LongTag("", num.getData().longValue()) : null;
            case Tag.TAG_Float -> fitFloat(num) ? new FloatTag("", num.getData().floatValue()) : null;
            case Tag.TAG_Double -> fitDouble(num) ? new DoubleTag("", num.getData().doubleValue()) : null;
            default -> null;
        };
    }

    /** 数值可无损装入 int 域（整值且不溢出）。 */
    private static boolean fitInt(NumberTag<?> num) {
        return fitInt(num, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static boolean fitInt(NumberTag<?> num, long min, long max) {
        Number n = num.getData();
        if (n instanceof Byte || n instanceof Short || n instanceof Integer) {
            return n.longValue() >= min && n.longValue() <= max;
        }
        if (n instanceof Long l) {
            return l >= min && l <= max;
        }
        // 浮点须为整值且在域内
        double d = n.doubleValue();
        return d == Math.rint(d) && d >= min && d < (double) max + 1;
    }

    /** 数值可无损装入 long 域（浮点须为整值）。 */
    private static boolean fitLong(NumberTag<?> num) {
        Number n = num.getData();
        if (n instanceof Long || n instanceof Byte || n instanceof Short || n instanceof Integer) {
            return true;
        }
        double d = n.doubleValue();
        return d == Math.rint(d) && d >= -9.223372036854776E18 && d < 9.223372036854776E18;
    }

    /** 数值可无损装入 float（整型须 float 往返无损，即 2^24 精度域内；浮点须 float 往返无损）。 */
    private static boolean fitFloat(NumberTag<?> num) {
        Number n = num.getData();
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            long lv = n.longValue();
            return (long) (float) lv == lv;
        }
        double d = n.doubleValue();
        return (double) (float) d == d;
    }

    /** 数值可无损装入 double（float 恒无损；long 须 double 往返无损）。 */
    private static boolean fitDouble(NumberTag<?> num) {
        Number n = num.getData();
        if (n instanceof Long l) {
            return (long) (double) l.longValue() == l.longValue();
        }
        return true; // float → double 恒无损
    }

    // ==================== 读取方向：Tag → Java 值 ====================

    /**
     * 原生解包（表 4-2「未指定目标」语义）：按 Tag 自身宽度返回 Java 值。
     * <ul>
     *   <li>StringTag → String；ByteTag → Byte；ShortTag → Short；IntTag → Integer；
     *       LongTag → Long；FloatTag → Float；DoubleTag → Double</li>
     *   <li>ByteArrayTag → byte[]（拷贝）；IntArrayTag → int[]（拷贝）</li>
     *   <li>CompoundTag → 浅 {@code Map<String,Tag>}；ListTag → 浅 {@code List<Tag>}</li>
     *   <li>EndTag → null</li>
     * </ul>
     */
    public static Object unwrap(Tag tag) {
        if (tag instanceof StringTag t) {
            return t.data;
        }
        if (tag instanceof ByteTag t) {
            return (byte) (int) t.getData();
        }
        if (tag instanceof ShortTag t) {
            return (short) (int) t.getData();
        }
        if (tag instanceof IntTag t) {
            return t.getData();
        }
        if (tag instanceof LongTag t) {
            return t.getData();
        }
        if (tag instanceof FloatTag t) {
            return t.getData();
        }
        if (tag instanceof DoubleTag t) {
            return t.getData();
        }
        if (tag instanceof ByteArrayTag t) {
            return t.data.clone();
        }
        if (tag instanceof IntArrayTag t) {
            return t.data.clone();
        }
        if (tag instanceof CompoundTag t) {
            return new LinkedHashMap<String, Tag>(t.getTags());
        }
        if (tag instanceof ListTag<?> t) {
            return new ArrayList<Tag>(t.getAll());
        }
        if (tag instanceof cn.nukkit.nbt.tag.EndTag) {
            return null;
        }
        throw new NbtTypeMismatchException("无法解包的 NBT 类型: " + tag.getClass().getName());
    }

    /**
     * 深解包：整树递归转为嵌套 Map/List/标量的纯 Java 结构（消费视图）。
     * <p>Byte/Short/Long/Float 解包后即丢失 NBT 类型信息；需要往返保真请用
     * jframe_inventory 的 {@code NbtJsonConverter}（{@code __nbt} 标记）或 SNBT。
     */
    public static Object toJavaValue(Tag tag) {
        if (tag instanceof CompoundTag t) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Tag> e : t.getTags().entrySet()) {
                map.put(e.getKey(), toJavaValue(e.getValue()));
            }
            return map;
        }
        if (tag instanceof ListTag<?> t) {
            List<Object> list = new ArrayList<>(t.size());
            for (Tag el : t.getAll()) {
                list.add(toJavaValue(el));
            }
            return list;
        }
        return unwrap(tag);
    }

    // ==================== asXxx：数值宽化/窄化解包（表 4-3） ====================

    /** 解包为 int（默认 LENIENT）。 */
    public static int asInt(Tag tag) {
        return asInt(tag, CoerceMode.LENIENT);
    }

    /**
     * 解包为 int：整型宽化无损自动；long/浮点窄化依模式（STRICT 溢出/丢精度抛，
     * LENIENT Java 原生截断）；非数值 STRICT 抛 / LENIENT 返回 0。
     */
    public static int asInt(Tag tag, CoerceMode mode) {
        if (tag instanceof NumberTag<?> n) {
            Number v = n.getData();
            if (v instanceof Byte || v instanceof Short || v instanceof Integer) {
                return v.intValue();
            }
            if (v instanceof Long l) {
                if (mode == CoerceMode.STRICT && (int) l.longValue() != l.longValue()) {
                    throw new NbtTypeMismatchException("读取窄化溢出", "int", tag);
                }
                return (int) l.longValue();
            }
            double d = v.doubleValue();
            if (mode == CoerceMode.STRICT && !(d == Math.rint(d) && d >= Integer.MIN_VALUE && d < (double) Integer.MAX_VALUE + 1)) {
                throw new NbtTypeMismatchException("读取窄化丢精度/溢出", "int", tag);
            }
            return (int) d;
        }
        return coerceFail(tag, "int", 0, mode);
    }

    /** 解包为 long（默认 LENIENT）。 */
    public static long asLong(Tag tag) {
        return asLong(tag, CoerceMode.LENIENT);
    }

    /** 解包为 long：整型无损；浮点窄化依模式；非数值依模式（LENIENT → 0）。 */
    public static long asLong(Tag tag, CoerceMode mode) {
        if (tag instanceof NumberTag<?> n) {
            Number v = n.getData();
            if (v instanceof Long || v instanceof Byte || v instanceof Short || v instanceof Integer) {
                return v.longValue();
            }
            double d = v.doubleValue();
            if (mode == CoerceMode.STRICT && !(d == Math.rint(d) && d >= -9.223372036854776E18 && d < 9.223372036854776E18)) {
                throw new NbtTypeMismatchException("读取窄化丢精度/溢出", "long", tag);
            }
            return (long) d;
        }
        return coerceFail(tag, "long", 0L, mode);
    }

    /** 解包为 float（默认 LENIENT）。 */
    public static float asFloat(Tag tag) {
        return asFloat(tag, CoerceMode.LENIENT);
    }

    /** 解包为 float：float 恒无损；double/整型窄化依模式（超 2^24 精度域按窄对待）。 */
    public static float asFloat(Tag tag, CoerceMode mode) {
        if (tag instanceof NumberTag<?> n) {
            Number v = n.getData();
            if (v instanceof Float) {
                return v.floatValue();
            }
            if (mode == CoerceMode.STRICT) {
                double d = v.doubleValue();
                if ((double) (float) d != d) {
                    throw new NbtTypeMismatchException("读取窄化丢精度", "float", tag);
                }
            }
            return v.floatValue();
        }
        return coerceFail(tag, "float", 0f, mode);
    }

    /** 解包为 double（默认 LENIENT）。 */
    public static double asDouble(Tag tag) {
        return asDouble(tag, CoerceMode.LENIENT);
    }

    /** 解包为 double：整型无损（long 超 2^53 精度域按窄对待）；非数值依模式（LENIENT → 0.0）。 */
    public static double asDouble(Tag tag, CoerceMode mode) {
        if (tag instanceof NumberTag<?> n) {
            Number v = n.getData();
            if (v instanceof Long l && mode == CoerceMode.STRICT && (long) (double) l.longValue() != l.longValue()) {
                throw new NbtTypeMismatchException("读取窄化丢精度（long 超 2^53 精度域）", "double", tag);
            }
            return v.doubleValue();
        }
        return coerceFail(tag, "double", 0.0, mode);
    }

    /**
     * 解包为布尔：<b>数值非零为 true</b>（开放问题 #7 建议默认值，宽松、与 C 系直觉一致，
     * 可在 M2 复议）；非数值 STRICT 抛 / LENIENT 返回 false。
     */
    public static boolean asBoolean(Tag tag) {
        return asBoolean(tag, CoerceMode.LENIENT);
    }

    public static boolean asBoolean(Tag tag, CoerceMode mode) {
        if (tag instanceof NumberTag<?> n) {
            return n.getData().doubleValue() != 0.0;
        }
        return coerceFail(tag, "boolean", false, mode);
    }

    /** 解包为 String：仅 StringTag；其他 STRICT 抛 / LENIENT 返回 null。 */
    public static String asString(Tag tag) {
        return asString(tag, CoerceMode.LENIENT);
    }

    public static String asString(Tag tag, CoerceMode mode) {
        if (tag instanceof StringTag t) {
            return t.data;
        }
        return coerceFail(tag, "String", null, mode);
    }

    /** 解包为 byte[]（拷贝）：仅 ByteArrayTag；其他 STRICT 抛 / LENIENT 返回 null。 */
    public static byte[] asByteArray(Tag tag) {
        return asByteArray(tag, CoerceMode.LENIENT);
    }

    public static byte[] asByteArray(Tag tag, CoerceMode mode) {
        if (tag instanceof ByteArrayTag t) {
            return t.data.clone();
        }
        return coerceFail(tag, "byte[]", null, mode);
    }

    /** 解包为 int[]（拷贝）：仅 IntArrayTag；其他 STRICT 抛 / LENIENT 返回 null。 */
    public static int[] asIntArray(Tag tag) {
        return asIntArray(tag, CoerceMode.LENIENT);
    }

    public static int[] asIntArray(Tag tag, CoerceMode mode) {
        if (tag instanceof IntArrayTag t) {
            return t.data.clone();
        }
        return coerceFail(tag, "int[]", null, mode);
    }

    /** LENIENT 失败降级：返回类型零值（开放问题 #8 建议 A，可在 M2 复议）。 */
    private static <T> T coerceFail(Tag tag, String expected, T zeroValue, CoerceMode mode) {
        if (mode == CoerceMode.STRICT) {
            throw new NbtTypeMismatchException("读取解包失败", expected, tag);
        }
        return zeroValue;
    }

    // ==================== convert：按目标 Class 解包 ====================

    /**
     * 按目标 Java 类型解包 Tag（{@code NbtAPI.get(root, path, Class)} 的底层）。
     * <p>M1 支持精确类型 + 无损宽化；窄化/不可转换依 {@link CoerceMode}。
     *
     * @param type 目标类型（Integer/Long/Double/Float/Byte/Short/String/Boolean/
     *             byte[]/int[]/Map/List/CompoundTag/Tag/Object 及对应原始类型）
     * @return 解包值；LENIENT 失败返回 null（由调用方降级为 Optional.empty / 默认值）
     * @throws NbtTypeMismatchException STRICT 下类型不符/窄化失败，或不支持的目标类型
     */
    public static <T> T convert(Tag tag, Class<T> type, CoerceMode mode) {
        Object result;
        if (type == Tag.class) {
            result = tag;
        } else if (type == CompoundTag.class) {
            result = tag instanceof CompoundTag c ? c : convertFail(tag, type, mode);
        } else if (type == String.class) {
            result = asString(tag, mode);
        } else if (type == Integer.class || type == int.class) {
            result = asInt(tag, mode);
        } else if (type == Long.class || type == long.class) {
            result = asLong(tag, mode);
        } else if (type == Double.class || type == double.class) {
            result = asDouble(tag, mode);
        } else if (type == Float.class || type == float.class) {
            result = asFloat(tag, mode);
        } else if (type == Byte.class || type == byte.class) {
            result = (byte) asInt(tag, mode);
        } else if (type == Short.class || type == short.class) {
            result = (short) asInt(tag, mode);
        } else if (type == Boolean.class || type == boolean.class) {
            result = asBoolean(tag, mode);
        } else if (type == byte[].class) {
            result = asByteArray(tag, mode);
        } else if (type == int[].class) {
            result = asIntArray(tag, mode);
        } else if (type == Map.class) {
            result = tag instanceof CompoundTag ? unwrap(tag) : convertFail(tag, type, mode);
        } else if (type == List.class) {
            result = tag instanceof ListTag ? unwrap(tag) : convertFail(tag, type, mode);
        } else if (type == Object.class) {
            result = unwrap(tag);
        } else {
            throw new NbtTypeMismatchException("不支持的目标读取类型: " + type.getName()
                    + "（受支持清单见 NbtValues.convert javadoc / README）");
        }
        if (type.isPrimitive()) {
            // Class.cast 不支持原始类型；asXxx 系列已保证返回对应包装类型，直接返回即可
            @SuppressWarnings("unchecked")
            T unchecked = (T) result;
            return unchecked;
        }
        return type.cast(result); // LENIENT 失败为 null 时 cast(null) 恒通过
    }

    private static <T> Object convertFail(Tag tag, Class<T> type, CoerceMode mode) {
        if (mode == CoerceMode.STRICT) {
            throw new NbtTypeMismatchException("读取解包失败", type.getSimpleName(), tag);
        }
        return null;
    }

    // List 归一策略枚举见同包独立文件 ListPolicy.java（供 NbtWriteOption 共用，维持 path → value 单向依赖）
}
