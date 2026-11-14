package io.github.JiangHu.jframe.content_template.column;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TextWidth} 显示宽度计算与截断的纯逻辑单元测试（无 Nukkit 依赖）。
 *
 * <h3>测试矩阵（设计文档 10.1 引擎侧）</h3>
 * <ul>
 *   <li>宽度：ASCII 1 宽 / CJK（含全角标点、假名）2 宽 / 颜色码（§x、&x）0 宽 / 混合</li>
 *   <li>截断：不超宽原样返回、超宽截断加省略号、保留尾部最后颜色码延续样式</li>
 * </ul>
 */
@DisplayName("TextWidth 显示宽度与截断测试")
class TextWidthLogicTest {

    @Nested
    @DisplayName("displayWidth：显示宽度计算")
    class DisplayWidthTests {

        @Test
        @DisplayName("空串与 null 宽度为 0")
        void testEmptyAndNull() {
            assertEquals(0, TextWidth.displayWidth(""));
            assertEquals(0, TextWidth.displayWidth(null));
        }

        @Test
        @DisplayName("纯 ASCII：每字符 1 宽")
        void testAscii() {
            assertEquals(0, TextWidth.displayWidth(""));
            assertEquals(1, TextWidth.displayWidth("a"));
            assertEquals(5, TextWidth.displayWidth("hello"));
            assertEquals(4, TextWidth.displayWidth("1234"));
        }

        @Test
        @DisplayName("CJK 汉字：每字符 2 宽")
        void testChinese() {
            assertEquals(2, TextWidth.displayWidth("中"));
            assertEquals(6, TextWidth.displayWidth("你好吗"));
        }

        @Test
        @DisplayName("中文标点（U+3000-303F）：2 宽")
        void testChinesePunctuation() {
            assertEquals(2, TextWidth.displayWidth("。"));
            assertEquals(2, TextWidth.displayWidth("【"));
            assertEquals(4, TextWidth.displayWidth("「」"));
        }

        @Test
        @DisplayName("全角形式（U+FF01-FF60）：2 宽")
        void testFullWidthForms() {
            assertEquals(2, TextWidth.displayWidth("Ａ")); // 全角字母 A
            assertEquals(2, TextWidth.displayWidth("！")); // 全角感叹号
            assertEquals(4, TextWidth.displayWidth("１２")); // 全角数字
        }

        @Test
        @DisplayName("日文假名：2 宽")
        void testKana() {
            assertEquals(2, TextWidth.displayWidth("あ")); // 平假名
            assertEquals(2, TextWidth.displayWidth("ア")); // 片假名
        }

        @Test
        @DisplayName("半角片假名（U+FF61-FF9F）：1 宽")
        void testHalfWidthKana() {
            assertEquals(1, TextWidth.displayWidth("ｱ")); // 半角片假名
        }

        @Test
        @DisplayName("§ 颜色码：0 宽")
        void testSectionColorCodes() {
            assertEquals(0, TextWidth.displayWidth("§a"));
            assertEquals(0, TextWidth.displayWidth("§f"));
            assertEquals(0, TextWidth.displayWidth("§l"));
            assertEquals(0, TextWidth.displayWidth("§r"));
            assertEquals(0, TextWidth.displayWidth("§A")); // 大写等价
        }

        @Test
        @DisplayName("& 转义颜色码：0 宽")
        void testAmpColorCodes() {
            assertEquals(0, TextWidth.displayWidth("&a"));
            assertEquals(0, TextWidth.displayWidth("&o"));
            assertEquals(0, TextWidth.displayWidth("&R"));
        }

        @Test
        @DisplayName("连续颜色码：全部 0 宽")
        void testConsecutiveColorCodes() {
            assertEquals(0, TextWidth.displayWidth("§a§l§r"));
            assertEquals(0, TextWidth.displayWidth("&a&b&c"));
            assertEquals(0, TextWidth.displayWidth("§a&b§c"));
        }

        @Test
        @DisplayName("非法颜色码后缀：前缀按普通字符 1 宽计")
        void testInvalidColorCode() {
            // § 后跟非法代码字符（如 z）：§ 计 1 宽（§ 不属于 CJK），z 计 1 宽
            assertEquals(2, TextWidth.displayWidth("§z"));
            // & 后跟非法代码字符：同理
            assertEquals(2, TextWidth.displayWidth("&z"));
        }

        @Test
        @DisplayName("前缀位于串尾（无后缀）：按普通字符计宽")
        void testTrailingPrefix() {
            assertEquals(1, TextWidth.displayWidth("§"));
            assertEquals(1, TextWidth.displayWidth("&"));
        }

        @Test
        @DisplayName("混合：颜色码 + ASCII + CJK")
        void testMixed() {
            // §a(0) + "AB"(2) + "中"(2) = 4
            assertEquals(4, TextWidth.displayWidth("§aAB中"));
            // "&r"(0) + "你好"(4) + "!"(1) = 5
            assertEquals(5, TextWidth.displayWidth("&r你好!"));
            // "§6⭐"(0+1) —— ⭐ 不属于 CJK 区，计 1 宽
            assertEquals(1, TextWidth.displayWidth("§6⭐"));
        }
    }

    @Nested
    @DisplayName("truncateToWidth：按显示宽度截断")
    class TruncateTests {

        @Test
        @DisplayName("不超宽：原样返回（不追加省略号）")
        void testNoTruncation() {
            assertEquals("abc", TextWidth.truncateToWidth("abc", 5, "..."));
            assertEquals("abc", TextWidth.truncateToWidth("abc", 3, "..."));
            assertEquals("中文", TextWidth.truncateToWidth("中文", 4, "..."));
        }

        @Test
        @DisplayName("纯 ASCII 超宽：截断加省略号")
        void testAsciiTruncation() {
            // "abcdef" 保留 3 宽 + "..." → "abc..."
            assertEquals("abc...", TextWidth.truncateToWidth("abcdef", 3, "..."));
        }

        @Test
        @DisplayName("CJK 超宽：按显示宽度截断（不切半字符）")
        void testCjkTruncation() {
            // "中文字符" 宽 8，保留 5 宽 → "中文"（4宽）+ "..."（"字"宽2会超5）
            assertEquals("中文...", TextWidth.truncateToWidth("中文字符", 5, "..."));
        }

        @Test
        @DisplayName("颜色码不计宽：保留内容中的颜色码原样保留")
        void testColorCodeZeroWidth() {
            // "§aabcdef" 显示宽 6，保留 3 宽 → "§aabc"；全文最后颜色码 §a 不在保留段末尾 → 追加延续码
            assertEquals("§aabc§a...", TextWidth.truncateToWidth("§aabcdef", 3, "..."));
        }

        @Test
        @DisplayName("截断时保留全文最后一个颜色码延续样式")
        void testStyleContinuation() {
            // "§aAB§cCDEF"：保留 2 宽 → "§aAB"，被截部分最后颜色码 §c → 追加后加省略号
            assertEquals("§aAB§c...", TextWidth.truncateToWidth("§aAB§cCDEF", 2, "..."));
        }

        @Test
        @DisplayName("最后颜色码已在保留段末尾：不重复追加")
        void testNoDuplicateCode() {
            // "AB§cCD"：保留 2 宽 → "AB"，全文最后码 §c 追加 → "AB§c..."
            assertEquals("AB§c...", TextWidth.truncateToWidth("AB§cCD", 2, "..."));
            // "§cCD"：保留 2 宽 → "§cCD" 保留段末尾即 §c 后无内容？——§c 在前，保留 "CD"，末码 §c 追加
            assertEquals("§cCD§c...", TextWidth.truncateToWidth("§cCDEF", 2, "..."));
        }

        @Test
        @DisplayName("无颜色码文本截断：不追加任何码")
        void testPlainTruncation() {
            assertEquals("ab...", TextWidth.truncateToWidth("abcdef", 2, "..."));
        }

        @Test
        @DisplayName("硬截断（空省略号）")
        void testHardTruncation() {
            assertEquals("abc", TextWidth.truncateToWidth("abcdef", 3, ""));
            assertEquals("abc", TextWidth.truncateToWidth("abcdef", 3, null));
        }

        @Test
        @DisplayName("keepWidth 为 0：只输出省略号（或延续码+省略号）")
        void testZeroKeepWidth() {
            assertEquals("...", TextWidth.truncateToWidth("abcdef", 0, "..."));
            // "§aabc"：保留 0 宽 + 延续码 §a + "..."
            assertEquals("§a...", TextWidth.truncateToWidth("§aabc", 0, "..."));
        }

        @Test
        @DisplayName("结果总宽 = 实际保留宽（CJK 不切半字符）+ 省略号宽")
        void testResultWidth() {
            // 保留 5 宽：CJK 每字 2 宽，"中文"(4宽)后 "字"(2宽) 会超 5 → 实际保留 4 宽
            String result = TextWidth.truncateToWidth("§a中文字符内容", 5, "...");
            assertEquals("§a中文§a...", result);
            assertEquals(4 + 3, TextWidth.displayWidth(result));
        }

        @Test
        @DisplayName("null / 空串输入：安全返回")
        void testNullAndEmpty() {
            assertEquals("", TextWidth.truncateToWidth(null, 3, "..."));
            assertEquals("", TextWidth.truncateToWidth("", 3, "..."));
        }
    }
}
