package io.github.JiangHu.jframe.content_template.column;

/**
 * 列宽策略——不可变值对象。
 *
 * <ul>
 *   <li>{@link #auto()}：AUTO——取该列全部单元格的最大显示宽度（{@link TextWidth#displayWidth}），
 *       不足最大宽的单元格按对齐方向补空格</li>
 *   <li>{@link #fixed(int)}：FIXED(n)——固定宽度 n，超宽单元格截断加省略号
 *       {@code ...}（省略号宽度计入 n），不足按对齐方向补空格</li>
 * </ul>
 *
 * <p>工厂方法：{@link #auto()} / {@link #fixed(int)}（{@code width <= 0} 抛
 * {@link IllegalArgumentException}）。
 */
public final class ColumnWidth {

    /** AUTO 策略单例 */
    private static final ColumnWidth AUTO = new ColumnWidth(-1);

    /** 固定宽度值；-1 表示 AUTO */
    private final int fixedWidth;

    private ColumnWidth(int fixedWidth) {
        this.fixedWidth = fixedWidth;
    }

    /** AUTO 宽度策略：该列宽度 = 全部单元格最大显示宽度 */
    public static ColumnWidth auto() {
        return AUTO;
    }

    /**
     * FIXED 宽度策略：固定宽度 n。
     *
     * @param width 固定显示宽度
     * @throws IllegalArgumentException width <= 0 时抛出
     */
    public static ColumnWidth fixed(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("固定列宽必须为正整数，实际为: " + width);
        }
        return new ColumnWidth(width);
    }

    /** 是否为 AUTO 策略 */
    public boolean isAuto() {
        return fixedWidth < 0;
    }

    /**
     * 固定宽度值。
     *
     * @return FIXED 策略的宽度值
     * @throws IllegalStateException AUTO 策略下调用时抛出
     */
    public int getFixedWidth() {
        if (isAuto()) {
            throw new IllegalStateException("AUTO 宽度策略没有固定宽度值");
        }
        return fixedWidth;
    }

    @Override
    public String toString() {
        return isAuto() ? "ColumnWidth(auto)" : "ColumnWidth(fixed=" + fixedWidth + ")";
    }
}
