package io.github.JiangHu.jframe.content_template.column;

/**
 * 单列策略——对齐方向 + 宽度策略，不可变值对象。
 *
 * <p>由 {@code <column align="..." width="..."/>} 声明，缺省为 {@link ColumnAlign#LEFT} +
 * {@link ColumnWidth#auto()}。
 *
 * <p>工厂方法 + 链式派生：
 * <pre>{@code
 * ColumnSpec spec = ColumnSpec.right().width(ColumnWidth.fixed(10));
 * }</pre>
 */
public final class ColumnSpec {

    private final ColumnAlign align;
    private final ColumnWidth width;

    private ColumnSpec(ColumnAlign align, ColumnWidth width) {
        this.align = align;
        this.width = width;
    }

    /** 左对齐 + AUTO 宽（全默认策略） */
    public static ColumnSpec left() {
        return new ColumnSpec(ColumnAlign.LEFT, ColumnWidth.auto());
    }

    /** 右对齐 + AUTO 宽 */
    public static ColumnSpec right() {
        return new ColumnSpec(ColumnAlign.RIGHT, ColumnWidth.auto());
    }

    /** 居中 + AUTO 宽 */
    public static ColumnSpec center() {
        return new ColumnSpec(ColumnAlign.CENTER, ColumnWidth.auto());
    }

    /** 按指定对齐方向 + 宽度策略构建 */
    public static ColumnSpec of(ColumnAlign align, ColumnWidth width) {
        return new ColumnSpec(align != null ? align : ColumnAlign.LEFT,
                width != null ? width : ColumnWidth.auto());
    }

    /**
     * 派生新策略：保持对齐方向，替换宽度策略（本对象不可变，返回新实例）。
     *
     * @param width 宽度策略（null 视为 AUTO）
     * @return 新的 ColumnSpec 实例
     */
    public ColumnSpec width(ColumnWidth width) {
        return new ColumnSpec(this.align, width != null ? width : ColumnWidth.auto());
    }

    /** 对齐方向（恒非 null） */
    public ColumnAlign getAlign() {
        return align;
    }

    /** 宽度策略（恒非 null） */
    public ColumnWidth getWidth() {
        return width;
    }

    @Override
    public String toString() {
        return "ColumnSpec{align=" + align + ", width=" + width + "}";
    }
}
