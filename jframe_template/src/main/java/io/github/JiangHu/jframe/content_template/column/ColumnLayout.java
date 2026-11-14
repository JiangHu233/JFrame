package io.github.JiangHu.jframe.content_template.column;

import java.util.List;

/**
 * 列布局配置——不可变，编译期由 {@code TemplateParser} 从根属性 {@code columns} +
 * {@code <columns>} 配置块构建，随 {@code Template} 不可变持有。
 *
 * <h3>启用语义</h3>
 * <ul>
 *   <li>{@link #DISABLED}：禁用（未启用列格式的模板共用此单例），渲染行为与引擎扩展前完全一致</li>
 *   <li>{@link #enabled(String, List)}：启用——separator 为行内分隔符，
 *       columns 为逐列策略列表（可为空列表 = 全列默认 LEFT + AUTO）</li>
 * </ul>
 *
 * <p>第 k 列使用 columns 第 k 个策略；列数超出声明数时回退最后一个策略（见 {@link ColumnAligner}）。
 */
public final class ColumnLayout {

    /** 禁用单例：未启用列格式的模板共用 */
    public static final ColumnLayout DISABLED = new ColumnLayout(false, null, List.of());

    private final boolean enabled;
    private final String separator;
    private final List<ColumnSpec> columns;

    private ColumnLayout(boolean enabled, String separator, List<ColumnSpec> columns) {
        this.enabled = enabled;
        this.separator = separator;
        this.columns = columns;
    }

    /**
     * 构建启用的列布局。
     *
     * @param separator 行内分隔符（非空校验）
     * @param columns   逐列策略列表（null 或空列表 = 全列默认 LEFT + AUTO）
     * @return 启用的 ColumnLayout
     * @throws IllegalArgumentException separator 为 null 或空串时抛出
     */
    public static ColumnLayout enabled(String separator, List<ColumnSpec> columns) {
        if (separator == null || separator.isEmpty()) {
            throw new IllegalArgumentException("列分隔符不能为空");
        }
        return new ColumnLayout(true, separator,
                columns != null ? List.copyOf(columns) : List.of());
    }

    /** 是否启用列格式 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 行内分隔符（启用时恒非空；禁用时返回 null） */
    public String getSeparator() {
        return separator;
    }

    /** 逐列策略列表（不可变；启用时可为空列表 = 全列默认策略；禁用时为空列表） */
    public List<ColumnSpec> getColumns() {
        return columns;
    }

    @Override
    public String toString() {
        return enabled
                ? "ColumnLayout{separator='" + separator + "', columns=" + columns.size() + "}"
                : "ColumnLayout{DISABLED}";
    }
}
