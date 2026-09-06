package io.github.JiangHu.jframe.nbt.core.path;

import io.github.JiangHu.jframe.nbt.core.NbtPathSyntaxException;
import io.github.JiangHu.jframe.nbt.core.filter.SnbtLiteral;
import io.github.JiangHu.jframe.nbt.core.value.NbtValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBT 路径解析器 —— 手写递归下降，DESIGN.md 2.1 EBNF 的直接映射（M1 语法子集）。
 *
 * <h3>M1 支持的语法（MC 原生兼容子集 + 起始 {@code [} 超集）</h3>
 * <ul>
 *   <li>键段 {@code display} / 引号键 {@code "weird.name"}（{@code \"} {@code \\} 转义）；</li>
 *   <li>下标 {@code [0]} / 负索引 {@code [-1]}；全体元素 {@code []}；</li>
 *   <li>复合过滤 {@code [{id:"x", Count:64b}]}（类型敏感字面量，见 4.4）；</li>
 *   <li>根部复合过滤 {@code {version:1}}（对整棵根做子集匹配）；</li>
 *   <li>首段点可省略（{@code display.Name} 等价 {@code .display.Name}，解析层归一化）；</li>
 *   <li>空表达式 = 根自身（命中整棵树）。</li>
 * </ul>
 *
 * <h3>M1 明确拒绝（M2 特性，报语法错误并提示）</h3>
 * <ul>
 *   <li>{@code *} 键通配 / {@code [*]}；</li>
 *   <li>{@code [?(...)]} 比较谓词；</li>
 *   <li>过滤字面量中的嵌套构造（{@code [B;1,2]}、{@code {a:1b}}）。</li>
 * </ul>
 *
 * <p>错误消息中文、携带 0 起字符偏移。本类无状态（每次 {@link #parse} 创建独立游标），
 * 线程安全，可作 Spring 单例。
 */
public final class NbtPathParser {

    /**
     * 解析路径表达式为不可变 {@link NbtPath}。
     *
     * @param expression 路径表达式（首尾空白宽容忽略），如 {@code Items[0].tag.display.Name}
     * @throws NbtPathSyntaxException 表达式非法（消息含出错位置）
     */
    public NbtPath parse(String expression) {
        if (expression == null) {
            throw new NullPointerException("路径表达式不能为 null");
        }
        Cursor cur = new Cursor(expression.trim());
        NbtPathSegment.CompoundFilter rootFilter = null;
        if (!cur.eof() && cur.peek() == '{') {
            rootFilter = parseCompoundFilter(cur);
        }
        List<NbtPathSegment> segments = new ArrayList<>();
        while (!cur.eof()) {
            char c = cur.peek();
            if (c == '.') {
                cur.advance();
                segments.add(parseKey(cur));
            } else if (c == '"') {
                segments.add(parseKey(cur));
            } else if (c == '[') {
                segments.add(parseIndexSel(cur));
            } else if (isIdentStart(c)) {
                segments.add(parseKey(cur));
            } else {
                throw err(cur, "非法字符 '" + c + "'（期待键名、引号键、'[' 或 '.'）");
            }
        }
        return NbtPath.of(rootFilter, segments);
    }

    // ==================== 键段 ====================

    /** 键段：引号键或裸标识符；{@code *} 为 M2 键通配，M1 拒绝。 */
    private NbtPathSegment.Key parseKey(Cursor cur) {
        if (cur.eof()) {
            throw err(cur, "键名不能为空");
        }
        if (cur.peek() == '"') {
            return new NbtPathSegment.Key(parseQuoted(cur));
        }
        if (cur.peek() == '*') {
            throw err(cur, "键通配符 * 为 M2 特性，当前版本不支持");
        }
        String name = parseIdentifier(cur);
        if (name.isEmpty()) {
            throw err(cur, "键名不能为空");
        }
        return new NbtPathSegment.Key(name);
    }

    /** 裸标识符：排除路径/过滤保留字符与空白（含特殊字符的键名请用双引号包裹）。 */
    private String parseIdentifier(Cursor cur) {
        StringBuilder sb = new StringBuilder();
        while (!cur.eof() && isIdentPart(cur.peek())) {
            sb.append(cur.advance());
        }
        return sb.toString();
    }

    private static boolean isIdentStart(char c) {
        return isIdentPart(c);
    }

    private static boolean isIdentPart(char c) {
        if (Character.isWhitespace(c)) {
            return false;
        }
        // 路径结构字符、过滤分隔符、M2 谓词保留字符均不可裸用
        return ".[]{}\"*,:;?()@=!<>|&".indexOf(c) < 0;
    }

    // ==================== 下标选择器 ====================

    /** {@code [选择器]}：空 = 全体元素；整数 = 下标；{...} = 复合过滤；?/* = M2 拒绝。 */
    private NbtPathSegment parseIndexSel(Cursor cur) {
        cur.advance(); // 消费 '['
        if (cur.eof()) {
            throw err(cur, "未闭合的 '['");
        }
        char c = cur.peek();
        if (c == ']') {
            cur.advance();
            return new NbtPathSegment.AllElements();
        }
        if (c == '?') {
            throw err(cur, "比较谓词 [?(...)] 为 M2 特性，当前版本不支持");
        }
        if (c == '*') {
            throw err(cur, "通配符 [*] 为 M2 特性；全体元素请使用 []");
        }
        if (c == '{') {
            NbtPathSegment.CompoundFilter filter = parseCompoundFilter(cur);
            expect(cur, ']');
            return filter;
        }
        if (c == '-' || isDigit(c)) {
            int index = parseInteger(cur);
            expect(cur, ']');
            return new NbtPathSegment.Index(index);
        }
        throw err(cur, "非法的选择器 '" + c + "'（期待整数下标、{ 过滤或 ] 全体元素）");
    }

    /** 整数下标（支持负索引；超 int 值域报错）。 */
    private int parseInteger(Cursor cur) {
        boolean negative = false;
        if (cur.peek() == '-') {
            negative = true;
            cur.advance();
        }
        if (cur.eof() || !isDigit(cur.peek())) {
            throw err(cur, "期待整数下标");
        }
        long value = 0;
        while (!cur.eof() && isDigit(cur.peek())) {
            value = value * 10 + (cur.advance() - '0');
            if (value > Integer.MAX_VALUE + 1L) {
                throw err(cur, "下标超出 int 值域: " + (negative ? "-" : "") + value);
            }
        }
        if (negative) {
            value = -value;
        }
        if (value < Integer.MIN_VALUE) {
            throw err(cur, "下标超出 int 值域: " + value);
        }
        return (int) value;
    }

    // ==================== 复合过滤 ====================

    /** {@code {k:v,...}}（当前字符为 '{'）：空过滤 = 匹配任意 Compound；重复键后者覆盖（对齐 MC）。 */
    private NbtPathSegment.CompoundFilter parseCompoundFilter(Cursor cur) {
        cur.advance(); // 消费 '{'
        Map<String, SnbtLiteral> entries = new LinkedHashMap<>();
        skipWs(cur);
        if (!cur.eof() && cur.peek() == '}') {
            cur.advance();
            return new NbtPathSegment.CompoundFilter(entries);
        }
        while (true) {
            skipWs(cur);
            String key = parseFilterKey(cur);
            skipWs(cur);
            expect(cur, ':');
            skipWs(cur);
            SnbtLiteral literal = parseLiteral(cur);
            entries.put(key, literal);
            skipWs(cur);
            if (cur.eof()) {
                throw err(cur, "未闭合的 '{'");
            }
            char c = cur.peek();
            if (c == ',') {
                cur.advance();
                continue;
            }
            if (c == '}') {
                cur.advance();
                return new NbtPathSegment.CompoundFilter(entries);
            }
            throw err(cur, "期待 ',' 或 '}'，实际 '" + c + "'");
        }
    }

    /** 过滤键：裸标识符或引号键（不支持通配）。 */
    private String parseFilterKey(Cursor cur) {
        if (cur.eof()) {
            throw err(cur, "过滤键不能为空");
        }
        if (cur.peek() == '"') {
            return parseQuoted(cur);
        }
        if (cur.peek() == '*') {
            throw err(cur, "过滤键不支持通配符 *");
        }
        String key = parseIdentifier(cur);
        if (key.isEmpty()) {
            throw err(cur, "过滤键不能为空");
        }
        return key;
    }

    /** 跳过空白（过滤条目间宽容空白，如 {@code {id:"x", Count:64b}} 的 ", " 分隔）。 */
    private static void skipWs(Cursor cur) {
        while (!cur.eof() && Character.isWhitespace(cur.peek())) {
            cur.advance();
        }
    }

    // ==================== SNBT 标量字面量（表 4-4 M1 子集） ====================

    /** 字面量：字符串 / true/false / 数值（可带后缀）；嵌套构造为 M2 拒绝。 */
    private SnbtLiteral parseLiteral(Cursor cur) {
        if (cur.eof()) {
            throw err(cur, "期待字面量（字符串/数值/布尔）");
        }
        char c = cur.peek();
        if (c == '"') {
            return SnbtLiteral.ofString(parseQuoted(cur));
        }
        if (c == '-' || isDigit(c)) {
            return parseNumberLiteral(cur);
        }
        if (c == '[' || c == '{') {
            throw err(cur, "嵌套字面量（数组/复合）为 M2 特性，当前版本仅支持标量");
        }
        if (isIdentStart(c)) {
            String word = parseIdentifier(cur);
            if (word.equals("true")) {
                return SnbtLiteral.ofBoolean(true);
            }
            if (word.equals("false")) {
                return SnbtLiteral.ofBoolean(false);
            }
            throw err(cur, "无法识别的字面量: " + word);
        }
        throw err(cur, "非法的字面量起始字符 '" + c + "'");
    }

    /**
     * 数值字面量：无后缀整数恒 {@code IntTag}（VANILLA，过滤场景不可配置，见 4.4 场景绑定）；
     * 后缀 b/s/L/f/d 强制对应类型；无后缀小数恒 {@code DoubleTag}；值域越界报语法错误。
     */
    private SnbtLiteral parseNumberLiteral(Cursor cur) {
        boolean negative = false;
        if (cur.peek() == '-') {
            negative = true;
            cur.advance();
        }
        if (cur.eof() || !isDigit(cur.peek())) {
            throw err(cur, "期待数字");
        }
        StringBuilder digits = new StringBuilder();
        while (!cur.eof() && isDigit(cur.peek())) {
            digits.append(cur.advance());
        }
        String fracDigits = null;
        if (!cur.eof() && cur.peek() == '.' && isDigit(cur.peekAt(1))) {
            cur.advance(); // 消费 '.'
            StringBuilder frac = new StringBuilder();
            while (!cur.eof() && isDigit(cur.peek())) {
                frac.append(cur.advance());
            }
            fracDigits = frac.toString();
        }
        Character suffix = null;
        if (!cur.eof() && "bBsSlLfFdD".indexOf(cur.peek()) >= 0) {
            suffix = cur.advance();
        }
        String signed = (negative ? "-" : "") + digits;
        if (fracDigits != null) {
            double d = Double.parseDouble(signed + "." + fracDigits);
            if (suffix == null || suffix == 'd' || suffix == 'D') {
                return new SnbtLiteral(d, NbtValueType.DOUBLE);
            }
            if (suffix == 'f' || suffix == 'F') {
                return new SnbtLiteral((float) d, NbtValueType.FLOAT);
            }
            throw err(cur, "小数字面量不能使用整数后缀 '" + suffix + "'");
        }
        if (suffix == null) {
            try {
                return new SnbtLiteral(Integer.parseInt(signed), NbtValueType.INT);
            } catch (NumberFormatException e) {
                throw err(cur, "无后缀整数超出 int 值域（如需 long 请加 L 后缀）");
            }
        }
        return switch (suffix) {
            case 'b', 'B' -> intLiteral(cur, signed, NbtValueType.BYTE, "byte（-128~127）");
            case 's', 'S' -> intLiteral(cur, signed, NbtValueType.SHORT, "short（-32768~32767）");
            case 'l', 'L' -> {
                try {
                    yield new SnbtLiteral(Long.parseLong(signed), NbtValueType.LONG);
                } catch (NumberFormatException e) {
                    throw err(cur, "long 字面量超出值域");
                }
            }
            case 'f', 'F' -> new SnbtLiteral(Float.parseFloat(signed), NbtValueType.FLOAT);
            case 'd', 'D' -> new SnbtLiteral(Double.parseDouble(signed), NbtValueType.DOUBLE);
            default -> throw err(cur, "非法的数值后缀 '" + suffix + "'");
        };
    }

    /** 整数字面量 + 宽度值域检查（b/s 后缀须落在对应宽度域内）。 */
    private SnbtLiteral intLiteral(Cursor cur, String signed, NbtValueType type, String rangeDesc) {
        int value;
        try {
            value = Integer.parseInt(signed);
        } catch (NumberFormatException e) {
            throw err(cur, "字面量超出 " + rangeDesc + " 值域");
        }
        long min = type == NbtValueType.BYTE ? Byte.MIN_VALUE : Short.MIN_VALUE;
        long max = type == NbtValueType.BYTE ? Byte.MAX_VALUE : Short.MAX_VALUE;
        if (value < min || value > max) {
            throw err(cur, "字面量超出 " + rangeDesc + " 值域");
        }
        return SnbtLiteral.ofInt(value, type);
    }

    // ==================== 引号字符串 ====================

    /** 双引号字符串：{@code \"} 与 {@code \\} 转义；未知转义保留字面反斜杠（对齐 MC）。 */
    private String parseQuoted(Cursor cur) {
        cur.advance(); // 消费 '"'
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (cur.eof()) {
                throw err(cur, "未闭合的字符串（缺少结束引号）");
            }
            char c = cur.advance();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (cur.eof()) {
                    throw err(cur, "未闭合的转义序列");
                }
                char e = cur.advance();
                if (e == '"' || e == '\\') {
                    sb.append(e);
                } else {
                    sb.append('\\').append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    // ==================== 基础设施 ====================

    private void expect(Cursor cur, char expected) {
        if (cur.eof() || cur.peek() != expected) {
            throw err(cur, "期待 '" + expected + "'，实际 "
                    + (cur.eof() ? "表达式结尾" : "'" + cur.peek() + "'"));
        }
        cur.advance();
    }

    private NbtPathSyntaxException err(Cursor cur, String reason) {
        return new NbtPathSyntaxException(reason, cur.source(), cur.index());
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** 解析游标（每次 parse 独立创建，Parser 本身无状态、线程安全）。 */
    private static final class Cursor {
        private final String source;
        private int index;

        Cursor(String source) {
            this.source = source;
        }

        String source() {
            return source;
        }

        int index() {
            return index;
        }

        boolean eof() {
            return index >= source.length();
        }

        char peek() {
            return source.charAt(index);
        }

        char peekAt(int offset) {
            int i = index + offset;
            return i < source.length() ? source.charAt(i) : '\0';
        }

        char advance() {
            return source.charAt(index++);
        }
    }
}
