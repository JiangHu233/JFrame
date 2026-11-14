package io.github.JiangHu.jframe.content_template.column;

/**
 * 显示宽度计算工具——基于 Minecraft 等宽字体假设的字符串显示宽度与截断。
 *
 * <h3>宽度规则</h3>
 * <ul>
 *   <li>颜色/格式代码 {@code §x}（§ 后跟一个代码字符）→ <b>0 宽</b></li>
 *   <li>颜色代码转义形式 {@code &x}（& 后跟一个代码字符）→ <b>0 宽</b>
 *       （两种转义都按 0 宽处理；是否把 {@code &x} 翻译为 {@code §x} 由业务层决定，
 *       宽度计算不依赖翻译）</li>
 *   <li>CJK 字符（中文、日文假名、韩文、中文标点、全角形式）→ <b>2 宽</b></li>
 *   <li>其他（ASCII / 半角）→ <b>1 宽</b></li>
 * </ul>
 *
 * <p>纯静态工具，线程安全。
 */
public final class TextWidth {

    private TextWidth() {}

    /** 颜色/格式代码前缀：Minecraft 分节符 */
    private static final char COLOR_PREFIX_SECTION = '\u00A7';

    /** 颜色/格式代码前缀：& 转义形式 */
    private static final char COLOR_PREFIX_AMP = '&';

    /**
     * 计算字符串的显示宽度。
     *
     * <p>颜色/格式代码（{@code §x} / {@code &x}，x 为合法代码字符）计 0 宽；
     * CJK 字符（含中文标点、全角形式）计 2 宽；其他计 1 宽。
     *
     * @param text 输入文本（null 视为空串）
     * @return 显示宽度
     */
    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (isColorPrefix(c) && i + 1 < len && isColorCodeChar(text.charAt(i + 1))) {
                i += 2; // 颜色/格式代码：0 宽
            } else if (isWideChar(c)) {
                width += 2;
                i++;
            } else {
                width += 1;
                i++;
            }
        }
        return width;
    }

    /**
     * 按显示宽度截断——保留前 {@code keepWidth} 宽度的内容，追加省略号。
     *
     * <p>规则：
     * <ul>
     *   <li>文本显示宽度不超过 {@code keepWidth} 时<b>原样返回</b>（不追加省略号）</li>
     *   <li>颜色代码不计宽，保留内容中的颜色代码原样保留</li>
     *   <li>发生截断时，若整个文本（含被截掉部分）中存在颜色/格式代码，
     *       在省略号前保留<b>最后一个</b>代码以延续样式（避免省略号继承错误颜色）</li>
     *   <li>结果总显示宽度 = {@code keepWidth + displayWidth(ellipsis)}</li>
     * </ul>
     *
     * @param text      输入文本
     * @param keepWidth 保留的内容显示宽度（< 0 视为 0）
     * @param ellipsis  省略号（null 或空串 = 硬截断不加省略号）
     * @return 截断后的文本
     */
    public static String truncateToWidth(String text, int keepWidth, String ellipsis) {
        if (text == null || text.isEmpty()) {
            return text != null ? text : "";
        }
        int limit = Math.max(0, keepWidth);
        if (displayWidth(text) <= limit) {
            return text;
        }

        // 扫描：截断点 + 整个文本中最后一个颜色代码
        String lastColor = null;
        StringBuilder kept = new StringBuilder();
        int keptWidth = 0;
        int i = 0;
        int len = text.length();
        boolean truncated = false;
        while (i < len) {
            char c = text.charAt(i);
            if (isColorPrefix(c) && i + 1 < len && isColorCodeChar(text.charAt(i + 1))) {
                lastColor = text.substring(i, i + 2);
                if (!truncated) {
                    kept.append(lastColor); // 保留内容中的颜色代码（0 宽）
                }
                i += 2;
            } else {
                int charWidth = isWideChar(c) ? 2 : 1;
                if (truncated || keptWidth + charWidth > limit) {
                    truncated = true;
                    // 被截掉部分仍继续扫描，只为捕获最后的颜色码
                    i++;
                } else {
                    kept.append(c);
                    keptWidth += charWidth;
                    i++;
                }
            }
        }

        // 未发生实际截断（宽度恰好）——防御性返回原文
        if (!truncated) {
            return text;
        }

        // 尾部延续样式：最后一个颜色码若不在保留内容末尾，追加一份
        if (lastColor != null && !endsWithCode(kept, lastColor)) {
            kept.append(lastColor);
        }
        if (ellipsis != null && !ellipsis.isEmpty()) {
            kept.append(ellipsis);
        }
        return kept.toString();
    }

    /** kept 是否已以指定颜色码结尾（避免延续码重复追加） */
    private static boolean endsWithCode(StringBuilder kept, String code) {
        int keptLen = kept.length();
        int codeLen = code.length();
        if (keptLen < codeLen) {
            return false;
        }
        return kept.substring(keptLen - codeLen).equals(code);
    }

    /** 是否为颜色/格式代码前缀字符 */
    private static boolean isColorPrefix(char c) {
        return c == COLOR_PREFIX_SECTION || c == COLOR_PREFIX_AMP;
    }

    /** 是否为合法的 Minecraft 颜色/格式代码字符（0-9 a-f k-o r，大小写等价） */
    private static boolean isColorCodeChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o')
                || (c >= 'K' && c <= 'O')
                || c == 'r' || c == 'R';
    }

    /**
     * 是否为宽字符（显示宽度 2）。
     * <p>覆盖：CJK 统一表意文字（含扩展/兼容区）、日文假名、韩文、
     * CJK 标点符号（U+3000–U+303F）、全角形式（U+FF01–U+FF60、U+FFE0–U+FFE6）。
     */
    private static boolean isWideChar(char c) {
        // 半角片假名 U+FF61–U+FF9F：计 1 宽
        // （先于 UnicodeScript 判定——该区段脚本归属 KATAKANA，会被下方 script 分支误判为宽）
        if (c >= 0xFF61 && c <= 0xFF9F) {
            return false;
        }
        // CJK 统一表意文字（含扩展 A/B、兼容表意）与假名、韩文：按 UnicodeScript 判定
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return true;
        }
        // CJK 标点符号：U+3000–U+303F（。「」【】等中文标点）
        if (c >= 0x3000 && c <= 0x303F) {
            return true;
        }
        // 全角形式：U+FF01–U+FF60（全角 ASCII/标点）；U+FFE0–U+FFE6（全角货币符号）
        // 注：U+FF61–U+FF9F 为半角片假名，计 1 宽
        if ((c >= 0xFF01 && c <= 0xFF60) || (c >= 0xFFE0 && c <= 0xFFE6)) {
            return true;
        }
        return false;
    }
}
