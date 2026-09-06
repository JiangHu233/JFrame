package io.github.JiangHu.jframe.nbt.core.filter;

import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.NumberTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.value.NbtValueType;

/**
 * 过滤条件中的<b>类型敏感 SNBT 标量字面量</b>（DESIGN.md 2.1 / 4.4 表 4-4 的 M1 标量子集）。
 *
 * <p>record 持有「解析值 + NBT 类型锚」，供复合过滤 {@code [{k:v}]} 与根过滤 {@code {k:v}}
 * 做<b>类型敏感匹配</b>：{@code 1b} 只匹配 {@code ByteTag(1)}，不匹配 {@code IntTag(1)}
 * （对齐 MC 原生语义，M1 兼容承诺的锚点）。
 *
 * <h3>字面量推断规则（M1 标量子集，表 4-4）</h3>
 * <table border="1">
 * <tr><th>字面量</th><th>推断结果</th></tr>
 * <tr><td>{@code "..."}</td><td>StringTag（支持 {@code \"} {@code \\} 转义）</td></tr>
 * <tr><td>{@code true} / {@code false}</td><td>ByteTag(1/0)（MC 语义：SNBT 无布尔类型）</td></tr>
 * <tr><td>{@code 3}（无后缀整数）</td><td>IntTag（<b>恒 VANILLA</b>：过滤场景不可配置，
 *     见 4.4 场景绑定；值域越界抛语法异常）</td></tr>
 * <tr><td>{@code 1.5}（无后缀小数）</td><td>DoubleTag（浮点默认 double）</td></tr>
 * <tr><td>{@code 3b}/{@code 3s}/{@code 3L}/{@code 1.5f}/{@code 3d}</td><td>后缀强制对应类型
 *     （b/s 值域越界抛语法异常）</td></tr>
 * </table>
 *
 * <p>嵌套字面量（{@code [B;1,2]}、{@code {a:1b}}）与 {@code SnbtNumberMode} 为 M2/M3 期内容，
 * M1 解析器遇到即抛语法异常。
 *
 * @param value 解析值：STRING→String；BYTE/SHORT/INT→Integer；LONG→Long；FLOAT→Float；DOUBLE→Double
 * @param type  NBT 类型锚
 */
public record SnbtLiteral(Object value, NbtValueType type) {

    public SnbtLiteral {
        if (value == null || type == null) {
            throw new NullPointerException("SnbtLiteral 的 value 与 type 不可为 null");
        }
    }

    /** 构造字符串字面量。 */
    public static SnbtLiteral ofString(String value) {
        return new SnbtLiteral(value, NbtValueType.STRING);
    }

    /** 构造整型字面量（按给定宽度锚定）。 */
    public static SnbtLiteral ofInt(int value, NbtValueType type) {
        return new SnbtLiteral(value, type);
    }

    /** 构造布尔字面量（语义等同 1b/0b）。 */
    public static SnbtLiteral ofBoolean(boolean value) {
        return new SnbtLiteral(value ? 1 : 0, NbtValueType.BYTE);
    }

    /**
     * 转为对应的 NBT Tag（值构造场景；无后缀整数已按 VANILLA 锚定为 INT，
     * 与过滤场景共用同一推断，两处语义不漂移）。
     */
    public Tag toTag() {
        return switch (type) {
            case STRING -> new StringTag("", (String) value);
            case BYTE -> new ByteTag("", (Integer) value);
            case SHORT -> new ShortTag("", (Integer) value);
            case INT -> new IntTag("", (Integer) value);
            case LONG -> new LongTag("", (Long) value);
            case FLOAT -> new FloatTag("", (Float) value);
            case DOUBLE -> new DoubleTag("", (Double) value);
            default -> throw new IllegalStateException("标量字面量不支持的类型: " + type);
        };
    }

    /**
     * 类型敏感匹配：Tag 的 NBT 类型与本字面量类型<b>完全一致</b>且值相等才命中。
     * <p>{@code 1b} ≠ {@code 1} ≠ {@code 1.0d}；数值不跨类型比较
     * （跨类型提升比较是 M2 谓词 {@code [?(...)]} 的语义，见 4.5 分工表）。
     */
    public boolean matches(Tag tag) {
        if (NbtValueType.of(tag) != type) {
            return false;
        }
        return switch (type) {
            case STRING -> ((StringTag) tag).data.equals(value);
            case BYTE, SHORT, INT -> ((NumberTag<?>) tag).getData().intValue() == (Integer) value;
            case LONG -> ((NumberTag<?>) tag).getData().longValue() == (Long) value;
            case FLOAT -> ((NumberTag<?>) tag).getData().floatValue() == (Float) value;
            case DOUBLE -> ((NumberTag<?>) tag).getData().doubleValue() == (Double) value;
            default -> false;
        };
    }

    /** 规范化 SNBT 文本（用于 {@code NbtPath.expression()} 重建）。 */
    public String toSnbtString() {
        return switch (type) {
            case STRING -> quote((String) value);
            case BYTE -> value + "b";
            case SHORT -> value + "s";
            case INT -> String.valueOf(value);
            case LONG -> value + "L";
            case FLOAT -> Float.toString((Float) value) + "f";
            case DOUBLE -> Double.toString((Double) value) + "d";
            default -> String.valueOf(value);
        };
    }

    /** 字符串加引号 + 转义（{@code \} 与 {@code "}）；供路径规范化重建复用。 */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    @Override
    public String toString() {
        return toSnbtString();
    }
}
