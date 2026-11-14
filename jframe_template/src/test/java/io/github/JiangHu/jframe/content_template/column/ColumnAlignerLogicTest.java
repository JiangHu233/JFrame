package io.github.JiangHu.jframe.content_template.column;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ColumnAligner} 列化算法的纯逻辑单元测试（无 Nukkit 依赖）。
 *
 * <h3>测试矩阵（设计文档 10.1 引擎侧）</h3>
 * <ul>
 *   <li>AUTO 宽（各列最大显示宽）/ 固定宽截断（省略号计入固定宽）</li>
 *   <li>左 / 右 / 居中对齐（含奇数 pad 的居中拆分）</li>
 *   <li>行内列数不一致（缺失补空、多余以分隔符并入末列）</li>
 *   <li>分隔符转义（{@code \|}）、空行直通、颜色码 0 宽不影响列宽</li>
 *   <li>computeWidths 与 align 一致性、禁用（DISABLED）原样返回</li>
 * </ul>
 */
@DisplayName("ColumnAligner 列化算法测试")
class ColumnAlignerLogicTest {

    /** 默认分隔符布局（无列策略声明 → 全列 LEFT + AUTO） */
    private static ColumnLayout pipe() {
        return ColumnLayout.enabled("|", List.of());
    }

    @Nested
    @DisplayName("AUTO 宽与左对齐（默认）")
    class AutoLeftTests {

        @Test
        @DisplayName("AUTO 宽 = 各列最大显示宽，LEFT 右补空格")
        void testAutoWidthLeftAlign() {
            List<String> result = ColumnAligner.align(List.of("a|1", "bbb|22"), pipe());
            // 列宽 [3,2]；joiner " | "（两侧各一空格）：a+2空格pad、1+1空格pad
            assertEquals(List.of("a   | 1 ", "bbb | 22"), result);
        }

        @Test
        @DisplayName("CJK 单元格按 2 宽参与列宽计算")
        void testCjkWidth() {
            List<String> result = ColumnAligner.align(List.of("名字|数量", "Steve|1000"), pipe());
            // 列宽 [5,4]（"名字"宽4、"Steve"宽5；"数量"宽4、"1000"宽4）
            assertEquals("名字  | 数量", result.get(0));   // 名字(4)+1空格 pad 到 5，再接 joiner
            assertEquals("Steve | 1000", result.get(1));  // "Steve" 恰好 5 宽不 pad，joiner 两侧空格
        }

        @Test
        @DisplayName("颜色码 0 宽：含码单元格与纯文本同宽对齐")
        void testColorCodeZeroWidth() {
            List<String> result = ColumnAligner.align(List.of("§aA|B", "AA|BB"), pipe());
            // 列宽 [2,2]（"§aA" 颜色码 0 宽、内容宽 1；"AA" 宽 2）
            assertEquals("§aA  | B ", result.get(0));  // "§aA"(宽1) 右补 1 空格 + joiner
            assertEquals("AA | BB", result.get(1));    // "AA" 恰好 2 宽不 pad
        }
    }

    @Nested
    @DisplayName("对齐方向")
    class AlignDirectionTests {

        @Test
        @DisplayName("RIGHT：左补空格")
        void testRightAlign() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.left(), ColumnSpec.right()));
            List<String> result = ColumnAligner.align(List.of("a|1", "aa|22"), layout);
            // 列宽 [2,2]；第 2 列 RIGHT："1" 左补 1 空格
            assertEquals("a  |  1", result.get(0));
            assertEquals("aa | 22", result.get(1));    // "aa" 恰好列宽不 pad
        }

        @Test
        @DisplayName("CENTER：偶数 pad 左右均分")
        void testCenterEvenPad() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(ColumnSpec.center()));
            // 单列布局：拆列后每行只有 1 格（无分隔符出现）
            List<String> result = ColumnAligner.align(List.of("ab", "abcd"), layout);
            // 列宽 4；"ab" pad 2 → 左1右1
            assertEquals(List.of(" ab ", "abcd"), result);
        }

        @Test
        @DisplayName("CENTER：奇数 pad 左 ⌊pad/2⌋、右 ⌈pad/2⌉")
        void testCenterOddPad() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(ColumnSpec.center()));
            List<String> result = ColumnAligner.align(List.of("abc", "abcd"), layout);
            // 列宽 4；"abc" pad 1 → 左0右1
            assertEquals(List.of("abc ", "abcd"), result);
        }
    }

    @Nested
    @DisplayName("固定宽与截断")
    class FixedWidthTests {

        @Test
        @DisplayName("FIXED 超宽：截断加省略号（省略号计入固定宽）")
        void testFixedTruncate() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.of(ColumnAlign.LEFT, ColumnWidth.fixed(5))));
            List<String> result = ColumnAligner.align(List.of("abcdef", "ab"), layout);
            assertEquals("ab...", result.get(0));  // 保留 2 宽 + "..."（总宽 5）
            assertEquals("ab   ", result.get(1));  // 2 宽 pad 到 5
        }

        @Test
        @DisplayName("FIXED 不超宽：补空格到固定宽")
        void testFixedPad() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.of(ColumnAlign.RIGHT, ColumnWidth.fixed(5))));
            List<String> result = ColumnAligner.align(List.of("ab", "abcdefg"), layout);
            assertEquals("   ab", result.get(0));
            // 宽 7 > 5：保留 2 宽 + "..." → "ab..."，RIGHT 无 pad
            assertEquals("ab...", result.get(1));
        }

        @Test
        @DisplayName("FIXED 宽 ≤ 省略号宽：硬截断")
        void testFixedHardTruncate() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.of(ColumnAlign.LEFT, ColumnWidth.fixed(2))));
            List<String> result = ColumnAligner.align(List.of("abcdef"), layout);
            assertEquals("ab", result.get(0));  // 固定宽 2 容不下 "..."（宽3）→ 硬截断
        }

        @Test
        @DisplayName("AUTO 与 FIXED 混合：各列独立策略")
        void testMixedWidths() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.left(),                                  // 第1列 AUTO LEFT
                    ColumnSpec.of(ColumnAlign.RIGHT, ColumnWidth.fixed(4)))); // 第2列 FIXED RIGHT
            List<String> result = ColumnAligner.align(List.of("a|1", "bbb|22"), layout);
            // 第1列 AUTO 宽 3；第2列固定 4（RIGHT 左补）
            assertEquals("a   |    1", result.get(0));
            assertEquals("bbb |   22", result.get(1)); // "22" 左补 2 + joiner 1 空格
        }
    }

    @Nested
    @DisplayName("列数不齐与转义")
    class RaggedAndEscapeTests {

        @Test
        @DisplayName("行内列数不一致：缺失补空串、多余以分隔符并入末列")
        void testRaggedRows() {
            List<String> result = ColumnAligner.align(List.of("a|b|c|d", "x|y"), pipe());
            // 列数 4（取最大）；第2行补 ["x","y","",""]，列宽全 1
            assertEquals("a | b | c | d", result.get(0));
            assertEquals("x | y |   |  ", result.get(1));
        }

        @Test
        @DisplayName("声明列数少于单元格数：多余单元格原样并入末列")
        void testDeclaredFewerColumns() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.left(), ColumnSpec.right()));
            List<String> result = ColumnAligner.align(List.of("a|b|c"), layout);
            // 规整为 2 列：["a", "b|c"]；列宽 [1,3]
            assertEquals("a | b|c", result.get(0));
        }

        @Test
        @DisplayName("分隔符转义：\\| 不拆列，原样保留")
        void testEscapedSeparator() {
            List<String> result = ColumnAligner.align(List.of("a\\|b|c", "x|y"), pipe());
            // 第1行拆为 ["a\\|b", "c"]（"a\|b" 转义序列原样保留、显示宽 4）；列宽 [4,1]
            assertEquals("a\\|b | c", result.get(0));
            assertEquals("x    | y", result.get(1));
        }

        @Test
        @DisplayName("空串行直通：不参与列宽统计")
        void testEmptyLinePassthrough() {
            List<String> result = ColumnAligner.align(List.of("a|bb", "", "cc|d"), pipe());
            // 列宽 [2,2]
            assertEquals("", result.get(1));
            assertEquals("a  | bb", result.get(0));
            assertEquals("cc | d ", result.get(2));
        }

        @Test
        @DisplayName("多字符分隔符：整体匹配拆列")
        void testMultiCharSeparator() {
            ColumnLayout layout = ColumnLayout.enabled(";;", List.of());
            List<String> result = ColumnAligner.align(List.of("a;;1", "bb;;22"), layout);
            // 列宽 [2,2]；joiner " ;; "（两侧各一空格）
            assertEquals("a  ;; 1 ", result.get(0));   // "a" 右补 1 空格 + joiner
            assertEquals("bb ;; 22", result.get(1));   // "bb" 恰好列宽不 pad
        }
    }

    @Nested
    @DisplayName("computeWidths 与禁用行为")
    class WidthsAndDisabledTests {

        @Test
        @DisplayName("computeWidths：AUTO 列返回各列最大显示宽")
        void testComputeWidths() {
            int[] widths = ColumnAligner.computeWidths(List.of("a|1", "bbb|22"), pipe());
            assertArrayEquals(new int[]{3, 2}, widths);
        }

        @Test
        @DisplayName("computeWidths：FIXED 列返回固定值")
        void testComputeWidthsFixed() {
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.left(),
                    ColumnSpec.of(ColumnAlign.RIGHT, ColumnWidth.fixed(7))));
            int[] widths = ColumnAligner.computeWidths(List.of("a|1", "bbb|22"), layout);
            assertArrayEquals(new int[]{3, 7}, widths);
        }

        @Test
        @DisplayName("computeWidths：空串行不参与统计")
        void testComputeWidthsIgnoresEmpty() {
            int[] widths = ColumnAligner.computeWidths(List.of("a|1", ""), pipe());
            assertArrayEquals(new int[]{1, 1}, widths);
        }

        @Test
        @DisplayName("computeWidths 与 align 内部算法一致")
        void testWidthsAlignConsistency() {
            List<String> lines = List.of("名字|100", "Steve|9", "§aAB|12345");
            ColumnLayout layout = ColumnLayout.enabled("|", List.of(
                    ColumnSpec.left(), ColumnSpec.right()));
            int[] widths = ColumnAligner.computeWidths(lines, layout);
            assertArrayEquals(new int[]{5, 5}, widths);
            // 对齐后每行总显示宽 = Σ列宽 + joiner(" | ")宽 —— 证明每列恰好 pad 到 widths
            int expectedTotal = widths[0] + widths[1] + 3;
            List<String> aligned = ColumnAligner.align(lines, layout);
            for (String line : aligned) {
                assertEquals(expectedTotal, TextWidth.displayWidth(line),
                        "对齐行总宽应为 Σ列宽 + joiner 宽: " + line);
            }
        }

        @Test
        @DisplayName("DISABLED 布局：原样返回入参")
        void testDisabledReturnsInput() {
            List<String> lines = List.of("a|b", "cc|dd");
            List<String> result = ColumnAligner.align(lines, ColumnLayout.DISABLED);
            assertSame(lines, result, "禁用时应返回原引用");
        }

        @Test
        @DisplayName("DISABLED 布局：computeWidths 返回空数组")
        void testDisabledWidthsEmpty() {
            assertArrayEquals(new int[0],
                    ColumnAligner.computeWidths(List.of("a|b"), ColumnLayout.DISABLED));
        }

        @Test
        @DisplayName("null 布局：与禁用同行为")
        void testNullLayout() {
            List<String> lines = List.of("a|b");
            assertSame(lines, ColumnAligner.align(lines, null));
        }
    }
}
