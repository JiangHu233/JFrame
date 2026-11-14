package io.github.JiangHu.jframe.nbt.core.path;

import io.github.JiangHu.jframe.nbt.core.NbtPathSyntaxException;
import io.github.JiangHu.jframe.nbt.core.value.NbtValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NbtPathParser} 纯逻辑测试 —— 合法/非法矩阵、转义、错误位置、规范化往返（DESIGN.md 2.1 EBNF M1 子集）。
 * <p>不依赖服务器启动，纯 JDK + JUnit5（对齐 jframe 家族 LogicTest 惯例）。
 */
@DisplayName("NbtPathParser 路径解析（M1 语法子集）")
class NbtPathParserLogicTest {

    private final NbtPathParser parser = new NbtPathParser();

    // ==================== 合法路径 ====================

    @Nested
    @DisplayName("合法路径解析")
    class Legal {

        @Test
        @DisplayName("空表达式 = 根自身（0 段、无根过滤）")
        void emptyExpressionIsRoot() {
            NbtPath path = parser.parse("");
            assertTrue(path.segments().isEmpty(), "空表达式应无段");
            assertNull(path.rootFilter(), "空表达式不应有根过滤");
            assertEquals("", path.expression());
        }

        @Test
        @DisplayName("首尾空白宽容忽略")
        void trimsWhitespace() {
            NbtPath path = parser.parse("  display.Name  ");
            assertEquals(2, path.segments().size());
            assertEquals("display.Name", path.expression());
        }

        @Test
        @DisplayName("单键段")
        void singleKey() {
            NbtPath path = parser.parse("display");
            assertEquals(1, path.segments().size());
            assertInstanceOf(NbtPathSegment.Key.class, path.segments().get(0));
            assertEquals("display", ((NbtPathSegment.Key) path.segments().get(0)).name());
        }

        @Test
        @DisplayName("点分链：display.Name → 两个键段")
        void dottedChain() {
            NbtPath path = parser.parse("display.Name");
            assertEquals(2, path.segments().size());
            assertEquals("display", ((NbtPathSegment.Key) path.segments().get(0)).name());
            assertEquals("Name", ((NbtPathSegment.Key) path.segments().get(1)).name());
        }

        @Test
        @DisplayName("首点可省略：.a.b 与 a.b 归一为同一路径")
        void leadingDotOptional() {
            NbtPath withDot = parser.parse(".a.b");
            NbtPath withoutDot = parser.parse("a.b");
            assertEquals(withoutDot, withDot, "首点显式与省略应 equals");
            assertEquals("a.b", withDot.expression(), "规范化表达式应去掉首点");
        }

        @Test
        @DisplayName("下标段：Items[0]")
        void indexSegment() {
            NbtPath path = parser.parse("Items[0]");
            assertInstanceOf(NbtPathSegment.Index.class, path.segments().get(1));
            assertEquals(0, ((NbtPathSegment.Index) path.segments().get(1)).index());
        }

        @Test
        @DisplayName("负索引：Items[-1] → Index(-1)")
        void negativeIndex() {
            NbtPath path = parser.parse("Items[-1]");
            assertEquals(-1, ((NbtPathSegment.Index) path.segments().get(1)).index());
        }

        @Test
        @DisplayName("全体元素段：Items[] → AllElements")
        void allElements() {
            NbtPath path = parser.parse("Items[]");
            assertInstanceOf(NbtPathSegment.AllElements.class, path.segments().get(1));
            assertEquals("Items[]", path.expression());
        }

        @Test
        @DisplayName("起始 '[' 的路径（超集）：[0] 直接以下标开头")
        void leadingBracket() {
            NbtPath path = parser.parse("[0]");
            assertEquals(1, path.segments().size());
            assertInstanceOf(NbtPathSegment.Index.class, path.segments().get(0));
        }

        @Test
        @DisplayName("复合过滤段：Items[{id:\"x\", Count:64b}] → CompoundFilter（类型敏感字面量）")
        void compoundFilter() {
            NbtPath path = parser.parse("Items[{id:\"x\", Count:64b}]");
            NbtPathSegment filter = path.segments().get(1);
            assertInstanceOf(NbtPathSegment.CompoundFilter.class, filter);
            var entries = ((NbtPathSegment.CompoundFilter) filter).entries();
            assertEquals(2, entries.size());
            assertEquals("x", entries.get("id").value());
            assertEquals(NbtValueType.STRING, entries.get("id").type());
            assertEquals(64, entries.get("Count").value());
            assertEquals(NbtValueType.BYTE, entries.get("Count").type(), "64b 应锚定为 BYTE（1b ≠ 1）");
        }

        @Test
        @DisplayName("复合过滤字面量矩阵：无后缀整数=INT、后缀 b/s/L/f/d、小数=DOUBLE、布尔=BYTE")
        void filterLiteralInference() {
            var entries = ((NbtPathSegment.CompoundFilter) parser.parse("[{a:1, b:2b, c:3s, d:4L, e:5f, g:6d, h:1.5, t:true}]")
                    .segments().get(0)).entries();
            assertEquals(NbtValueType.INT, entries.get("a").type());
            assertEquals(NbtValueType.BYTE, entries.get("b").type());
            assertEquals(NbtValueType.SHORT, entries.get("c").type());
            assertEquals(NbtValueType.LONG, entries.get("d").type());
            assertEquals(NbtValueType.FLOAT, entries.get("e").type());
            assertEquals(NbtValueType.DOUBLE, entries.get("g").type());
            assertEquals(NbtValueType.DOUBLE, entries.get("h").type(), "无后缀小数恒 DOUBLE");
            assertEquals(NbtValueType.BYTE, entries.get("t").type(), "true = 1b（MC 无布尔类型）");
            assertEquals(1, entries.get("t").value());
        }

        @Test
        @DisplayName("根过滤：{version:1} → rootFilter 非 null、0 段")
        void rootFilter() {
            NbtPath path = parser.parse("{version:1}");
            assertNotNull(path.rootFilter(), "根过滤应被解析");
            assertTrue(path.segments().isEmpty());
            assertEquals(1, path.rootFilter().entries().get("version").value());
            assertEquals(NbtValueType.INT, path.rootFilter().entries().get("version").type());
        }

        @Test
        @DisplayName("根过滤 + 后续段：{version:1}.Items[0]")
        void rootFilterWithSegments() {
            NbtPath path = parser.parse("{version:1}.Items[0]");
            assertNotNull(path.rootFilter());
            assertEquals(2, path.segments().size());
        }

        @Test
        @DisplayName("空过滤 {} 匹配任意 Compound")
        void emptyFilter() {
            NbtPath path = parser.parse("Items[{}]");
            assertTrue(((NbtPathSegment.CompoundFilter) path.segments().get(1)).entries().isEmpty());
        }

        @Test
        @DisplayName("引号键：custom.\"weird.key\" → Key(weird.key)")
        void quotedKey() {
            NbtPath path = parser.parse("custom.\"weird.key\"");
            assertEquals("weird.key", ((NbtPathSegment.Key) path.segments().get(1)).name());
        }

        @Test
        @DisplayName("引号转义：\\\" 与 \\\\")
        void quotedEscapes() {
            assertEquals("a\"b", ((NbtPathSegment.Key) parser.parse("\"a\\\"b\"").segments().get(0)).name());
            assertEquals("a\\b", ((NbtPathSegment.Key) parser.parse("\"a\\\\b\"").segments().get(0)).name());
        }

        @Test
        @DisplayName("未知转义保留字面反斜杠（对齐 MC）")
        void unknownEscapeKeptLiteral() {
            assertEquals("a\\nb", ((NbtPathSegment.Key) parser.parse("\"a\\nb\"").segments().get(0)).name(),
                    "\\n 不是合法转义，应保留反斜杠与 n");
        }

        @Test
        @DisplayName("过滤键也可用引号：[{\"weird.key\":1}]")
        void quotedFilterKey() {
            var entries = ((NbtPathSegment.CompoundFilter) parser.parse("[{\"weird.key\":1}]")
                    .segments().get(0)).entries();
            assertEquals(1, entries.get("weird.key").value());
        }

        @Test
        @DisplayName("重复过滤键后者覆盖（对齐 MC）")
        void duplicateFilterKeyLastWins() {
            var entries = ((NbtPathSegment.CompoundFilter) parser.parse("[{a:1, a:2b}]")
                    .segments().get(0)).entries();
            assertEquals(1, entries.size());
            assertEquals(NbtValueType.BYTE, entries.get("a").type());
        }

        @Test
        @DisplayName("混合深路径：Items[0].tag.display.Lore[]")
        void mixedDeepPath() {
            NbtPath path = parser.parse("Items[0].tag.display.Lore[]");
            assertEquals(6, path.segments().size());
            assertInstanceOf(NbtPathSegment.AllElements.class, path.segments().get(5));
        }
    }

    // ==================== 非法路径（M2 特性拒绝 + 语法错误） ====================

    @Nested
    @DisplayName("非法路径与错误位置")
    class Illegal {

        @Test
        @DisplayName("示例表 #24：Items[0 未闭合 '[' → 语法异常（含位置）")
        void unclosedBracket() {
            NbtPathSyntaxException e = assertThrows(NbtPathSyntaxException.class, () -> parser.parse("Items[0"));
            assertEquals("Items[0", e.getExpression());
            assertEquals(7, e.getPosition(), "出错位置应为表达式结尾（0 起偏移 7）");
        }

        @Test
        @DisplayName("未闭合字符串")
        void unclosedString() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("\"abc"));
        }

        @Test
        @DisplayName("空键名：a..b")
        void emptyKey() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("a..b"));
        }

        @Test
        @DisplayName("尾部悬空点：a.")
        void trailingDot() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("a."));
        }

        @Test
        @DisplayName("键通配 * 为 M2 特性（#10/#11）")
        void wildcardRejected() {
            NbtPathSyntaxException e = assertThrows(NbtPathSyntaxException.class, () -> parser.parse("custom.*"));
            assertEquals(7, e.getPosition()); // '*' 所在位置（0 基）
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("*"));
        }

        @Test
        @DisplayName("[*] 通配为 M2 特性，全体元素请用 []")
        void bracketWildcardRejected() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("Items[*]"));
        }

        @Test
        @DisplayName("比较谓词 [?(...)] 为 M2 特性（#12/#31）")
        void predicateRejected() {
            assertThrows(NbtPathSyntaxException.class,
                    () -> parser.parse("Attributes[?(@.Amount >= 20)].Name"));
        }

        @Test
        @DisplayName("非法选择器：[x]")
        void illegalSelector() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("Items[x]"));
        }

        @Test
        @DisplayName("嵌套数组字面量为 M2 特性：[B;1,2]")
        void nestedArrayLiteralRejected() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("custom.Payload[B;1,2]"));
        }

        @Test
        @DisplayName("嵌套复合字面量为 M2 特性：[{a:{b:1}}]")
        void nestedCompoundLiteralRejected() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("Items[{a:{b:1}}]"));
        }

        @Test
        @DisplayName("后缀值域越界：300b")
        void suffixOutOfRange() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a:300b}]"));
        }

        @Test
        @DisplayName("无后缀整数超 int 值域")
        void intLiteralOverflow() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a:99999999999}]"));
        }

        @Test
        @DisplayName("下标超 int 值域")
        void indexOverflow() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("Items[99999999999]"));
        }

        @Test
        @DisplayName("过滤体缺冒号：[{a 1}]")
        void filterMissingColon() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a 1}]"));
        }

        @Test
        @DisplayName("过滤体未闭合：[{a:1]")
        void filterUnclosed() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a:1]"));
        }

        @Test
        @DisplayName("小数带整数后缀被拒：[{a:1.5b}]")
        void fractionalWithIntSuffix() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a:1.5b}]"));
        }

        @Test
        @DisplayName("null 表达式 → NPE")
        void nullExpression() {
            assertThrows(NullPointerException.class, () -> parser.parse(null));
        }

        @Test
        @DisplayName("无法识别的字面量单词：[{a:maybe}]")
        void unknownLiteralWord() {
            assertThrows(NbtPathSyntaxException.class, () -> parser.parse("[{a:maybe}]"));
        }
    }

    // ==================== 规范化往返与相等 ====================

    @Nested
    @DisplayName("规范化往返与 equals/hashCode")
    class Canonical {

        @Test
        @DisplayName("parse(expression()).expression() 恒等往返")
        void roundTrip() {
            String[] expressions = {
                    "Items[0].tag.display.Name", "Items[-1].id", "Items[].Count",
                    "Items[{id:\"minecraft:diamond\"}].Count", "{version:1}", "custom.\"weird.key\"",
                    "custom.Payload[1]", "Items[{id:\"x\", Count:64b}].tag.display.Lore[]"
            };
            for (String expr : expressions) {
                NbtPath first = parser.parse(expr);
                NbtPath second = parser.parse(first.expression());
                assertEquals(first.expression(), second.expression(), "往返失真: " + expr);
                assertEquals(first, second, "往返后应 equals: " + expr);
            }
        }

        @Test
        @DisplayName("含保留字符的键名规范化时保留引号")
        void quotedKeyStaysQuoted() {
            NbtPath path = parser.parse("custom.\"weird.key\"");
            assertEquals("custom.\"weird.key\"", path.expression());
        }

        @Test
        @DisplayName("合法裸键规范化时不加引号")
        void bareKeyStaysBare() {
            assertEquals("display.Name", parser.parse("\"display\".\"Name\"").expression(),
                    "引号包裹的合法键名规范化后可裸写");
        }

        @Test
        @DisplayName("equals 与 hashCode：等价表达式解析后相等")
        void equalsAndHashCode() {
            NbtPath a = parser.parse("Items[0].id");
            NbtPath b = parser.parse(".Items[0].id");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, parser.parse("Items[1].id"));
            assertNotEquals(a, parser.parse("Items[0]"));
        }

        @Test
        @DisplayName("toString 返回规范化表达式")
        void toStringIsExpression() {
            assertEquals("Items[0].id", parser.parse("Items[0].id").toString());
        }

        @Test
        @DisplayName("segments() 不可变（防外部篡改）")
        void segmentsImmutable() {
            NbtPath path = parser.parse("Items[0]");
            assertThrows(UnsupportedOperationException.class, () -> path.segments().clear());
        }
    }

    // ==================== 组合 API ====================

    @Nested
    @DisplayName("resolve / parent 组合")
    class Compose {

        @Test
        @DisplayName("resolve 拼接子路径")
        void resolveSub() {
            NbtPath base = parser.parse("Items[0]");
            NbtPath merged = base.resolve("tag.display.Name");
            assertEquals("Items[0].tag.display.Name", merged.expression());
        }

        @Test
        @DisplayName("resolve 拒绝携带根过滤的子路径")
        void resolveRejectsRootFilter() {
            NbtPath base = parser.parse("Items");
            assertThrows(IllegalArgumentException.class, () -> base.resolve("{v:1}"));
        }

        @Test
        @DisplayName("parent 去掉最后一段")
        void parentDropsLast() {
            NbtPath parent = parser.parse("Items[0].id").parent();
            assertEquals("Items[0]", parent.expression());
        }

        @Test
        @DisplayName("空路径 parent 抛 IllegalStateException")
        void parentOfEmptyThrows() {
            assertThrows(IllegalStateException.class, () -> parser.parse("").parent());
        }

        @Test
        @DisplayName("NbtPath.compile 静态入口与 Parser 行为一致")
        void staticCompile() {
            assertEquals(parser.parse("a.b[0]"), NbtPath.compile("a.b[0]"));
        }
    }

    /** JUnit5 断言 instanceof（5.x 无原生，简单包装）。 */
    private static void assertInstanceOf(Class<?> type, Object actual) {
        assertTrue(type.isInstance(actual), "期待 " + type.getSimpleName() + "，实际 " + actual.getClass().getSimpleName());
    }

    /** 消除「未使用」告警的占位引用（保持 parser 字段被测试类持有）。 */
    @SuppressWarnings("unused")
    private void touchParser() {
        assertSame(parser, parser);
    }
}
