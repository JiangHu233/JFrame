package io.github.JiangHu.jframe.content_template.column;

import io.github.JiangHu.jframe.content_template.TemplateConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * 列化算法——纯函数、无状态、线程安全（渲染后独立阶段）。
 *
 * <p>对行集合执行 <b>拆列 → 全量列宽重算 → 逐列对齐/截断 → 重组</b>：
 * <ol>
 *   <li><b>拆列</b>：每行按 {@link ColumnLayout#getSeparator()} 拆分为单元格；
 *       {@code \ + 分隔符} 转义序列表示字面分隔符（原样保留，不拆列）</li>
 *   <li><b>列数</b>：声明了 {@code <column>} 策略时取声明数，否则取各行最大单元格数；
 *       某行单元格少于列数 → 缺失视为空串；多于 → 多余单元格原样拼接进最后一列</li>
 *   <li><b>列宽</b>：AUTO 列 = 该列全部单元格最大显示宽度（空列宽 0）；
 *       FIXED(n) 列 = n（超宽截断加 {@code ...}，省略号宽度计入 n）</li>
 *   <li><b>对齐</b>：LEFT 右补空格 / RIGHT 左补空格 / CENTER 居中（左 ⌊pad/2⌋、右 ⌈pad/2⌉）</li>
 *   <li><b>重组</b>：对齐后的单元格以 {@code " 分隔符 "}（两侧各一空格）重新拼接</li>
 * </ol>
 *
 * <p>空串行不参与列宽统计，原样输出（面板分隔行）。颜色代码 0 宽
 * （见 {@link TextWidth}），含码文本与纯文本同宽对齐。
 *
 * <p>策略覆盖：第 k 列使用 {@code <columns>} 第 k 个 {@code <column>} 声明，
 * 列数超出声明数时回退最后一个声明；未声明时全列默认 LEFT + AUTO。
 */
public final class ColumnAligner {

    private ColumnAligner() {}

    /**
     * 对行集合执行列化（拆列 → 算宽 → 对齐/截断 → 重组）。
     *
     * @param lines  渲染后的原始行列表
     * @param layout 列布局（禁用或 null 时原样返回入参）
     * @return 列化后的行列表（与入参等长、同序；空行原样直通）
     */
    public static List<String> align(List<String> lines, ColumnLayout layout) {
        if (layout == null || !layout.isEnabled() || lines == null || lines.isEmpty()) {
            return lines;
        }

        String separator = layout.getSeparator();
        List<ColumnSpec> specs = layout.getColumns();

        // 拆列 + 规整（补齐/多余并入末列）；null 元素表示空行直通
        List<List<String>> rows = new ArrayList<>(lines.size());
        int colCount = normalizeRows(lines, separator, specs, rows);
        if (colCount <= 0) {
            return lines;
        }

        // 列宽（与 computeWidths 同一算法）
        int[] widths = computeColumnWidths(rows, colCount, specs);

        // 逐行对齐 + 重组
        String joiner = " " + separator + " ";
        List<String> result = new ArrayList<>(lines.size());
        for (List<String> cells : rows) {
            if (cells == null) {
                result.add("");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < colCount; c++) {
                if (c > 0) {
                    sb.append(joiner);
                }
                sb.append(alignCell(cells.get(c), widths[c], specAt(specs, c)));
            }
            result.add(sb.toString());
        }
        return result;
    }

    /**
     * 只计算各列显示宽度（增量协作用：与上次宽度比较判断是否扩大变更集）。
     *
     * <p>AUTO 列 = 该列全部单元格最大内容宽；FIXED 列 = 固定宽值。
     * 空串行不参与统计。
     *
     * @param lines  渲染后的原始行列表
     * @param layout 列布局（禁用或 null 时返回空数组）
     * @return 各列宽度数组（无有效行时为空数组）
     */
    public static int[] computeWidths(List<String> lines, ColumnLayout layout) {
        if (layout == null || !layout.isEnabled() || lines == null || lines.isEmpty()) {
            return new int[0];
        }

        List<ColumnSpec> specs = layout.getColumns();
        List<List<String>> rows = new ArrayList<>(lines.size());
        int colCount = normalizeRows(lines, layout.getSeparator(), specs, rows);
        if (colCount <= 0) {
            return new int[0];
        }
        return computeColumnWidths(rows, colCount, specs);
    }

    // ==================== 内部算法 ====================

    /**
     * 拆列并规整所有行。
     *
     * @param lines     原始行
     * @param separator 分隔符
     * @param specs     列策略声明（决定列数契约）
     * @param rows      出参：规整后的单元格列表（空串行以 null 占位）
     * @return 列数（声明数 > 0 取声明数，否则取各行最大单元格数）
     */
    private static int normalizeRows(List<String> lines, String separator,
                                     List<ColumnSpec> specs, List<List<String>> rows) {
        int declaredCols = specs.size();
        int maxCells = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                rows.add(null); // 空行直通，不参与列宽统计
                continue;
            }
            List<String> cells = splitLine(line, separator);
            maxCells = Math.max(maxCells, cells.size());
            rows.add(cells);
        }
        int colCount = declaredCols > 0 ? declaredCols : maxCells;
        if (colCount <= 0) {
            return 0;
        }

        // 规整：多余单元格原样拼接进最后一列；缺失补空串
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (cells == null) {
                continue;
            }
            rows.set(r, normalizeCells(cells, colCount, separator));
        }
        return colCount;
    }

    /** 单行单元格规整到固定列数 */
    private static List<String> normalizeCells(List<String> cells, int colCount, String separator) {
        if (cells.size() == colCount) {
            return cells;
        }
        List<String> normalized = new ArrayList<>(colCount);
        if (cells.size() > colCount) {
            // 多余单元格原样（以分隔符）拼接进最后一列
            normalized.addAll(cells.subList(0, colCount - 1));
            normalized.add(String.join(separator, cells.subList(colCount - 1, cells.size())));
        } else {
            normalized.addAll(cells);
            for (int i = cells.size(); i < colCount; i++) {
                normalized.add("");
            }
        }
        return normalized;
    }

    /** 按分隔符拆分单行；{@code \ + 分隔符} 转义序列原样保留 */
    private static List<String> splitLine(String line, String separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int sepLen = separator.length();
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            if (c == '\\' && line.startsWith(separator, i + 1)) {
                // 转义：保留字面 "\ + 分隔符"
                current.append(c).append(separator);
                i += 1 + sepLen;
            } else if (line.startsWith(separator, i)) {
                cells.add(current.toString());
                current.setLength(0);
                i += sepLen;
            } else {
                current.append(c);
                i++;
            }
        }
        cells.add(current.toString());
        return cells;
    }

    /** 各列宽度：AUTO = 最大内容宽，FIXED = 固定值 */
    private static int[] computeColumnWidths(List<List<String>> rows, int colCount, List<ColumnSpec> specs) {
        int[] widths = new int[colCount];
        for (int c = 0; c < colCount; c++) {
            ColumnSpec spec = specAt(specs, c);
            if (!spec.getWidth().isAuto()) {
                widths[c] = spec.getWidth().getFixedWidth();
            } else {
                int max = 0;
                for (List<String> cells : rows) {
                    if (cells != null) {
                        max = Math.max(max, TextWidth.displayWidth(cells.get(c)));
                    }
                }
                widths[c] = max;
            }
        }
        return widths;
    }

    /** 单个单元格按列宽与策略对齐（FIXED 超宽先截断加省略号） */
    private static String alignCell(String cell, int targetWidth, ColumnSpec spec) {
        if (!spec.getWidth().isAuto()) {
            cell = fitFixedWidth(cell, spec.getWidth().getFixedWidth());
        }
        return padCell(cell, targetWidth, spec.getAlign());
    }

    /** FIXED 列超宽截断：加省略号（省略号宽度计入固定宽）；固定宽容不下省略号时硬截断 */
    private static String fitFixedWidth(String cell, int fixedWidth) {
        int cellWidth = TextWidth.displayWidth(cell);
        if (cellWidth <= fixedWidth) {
            return cell;
        }
        String ellipsis = TemplateConstants.TRUNCATE_ELLIPSIS;
        int ellipsisWidth = TextWidth.displayWidth(ellipsis);
        if (fixedWidth <= ellipsisWidth) {
            return TextWidth.truncateToWidth(cell, fixedWidth, "");
        }
        return TextWidth.truncateToWidth(cell, fixedWidth - ellipsisWidth, ellipsis);
    }

    /** 按对齐方向补空格到目标宽度 */
    private static String padCell(String cell, int targetWidth, ColumnAlign align) {
        int pad = targetWidth - TextWidth.displayWidth(cell);
        if (pad <= 0) {
            return cell;
        }
        return switch (align) {
            case LEFT -> cell + " ".repeat(pad);
            case RIGHT -> " ".repeat(pad) + cell;
            case CENTER -> {
                int left = pad / 2;
                yield " ".repeat(left) + cell + " ".repeat(pad - left);
            }
        };
    }

    /** 第 colIndex 列的策略：越界回退最后一个声明；无声明全默认 LEFT + AUTO */
    private static ColumnSpec specAt(List<ColumnSpec> specs, int colIndex) {
        if (specs.isEmpty()) {
            return ColumnSpec.left();
        }
        if (colIndex < specs.size()) {
            return specs.get(colIndex);
        }
        return specs.get(specs.size() - 1);
    }
}
