package io.github.JiangHu.jframe.content_template.column;

/**
 * 列对齐方向。
 *
 * <p>由 {@code <column align="...">} 属性声明，{@link ColumnAligner} 按此方向补空格：
 * <ul>
 *   <li>{@link #LEFT}：左对齐，右侧补空格（默认）</li>
 *   <li>{@link #CENTER}：居中，左侧补 {@code ⌊pad/2⌋}、右侧补 {@code ⌈pad/2⌉}</li>
 *   <li>{@link #RIGHT}：右对齐，左侧补空格</li>
 * </ul>
 */
public enum ColumnAlign {

    /** 左对齐（默认）：右侧补空格 */
    LEFT,
    /** 居中：左侧补 ⌊pad/2⌋、右侧补 ⌈pad/2⌉ */
    CENTER,
    /** 右对齐：左侧补空格 */
    RIGHT
}
