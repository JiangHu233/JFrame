package io.github.JiangHu.jframe.content_template;

/**
 * 模板保留字常量类。
 *
 * <p>统一定义所有模板保留字（XML 标签名、XML 属性名、插值定界符、循环上下文变量），
 * DOM 解析器、AST 构建器、渲染器中所有标签/属性判断均引用此类常量，
 * 避免硬编码字符串，便于统一维护和扩展。
 */
public final class TemplateConstants {

    private TemplateConstants() {}

    // ===== XML 标签名 =====

    /** 根标签 {@code <template>} */
    public static final String TAG_TEMPLATE  = "template";
    /** 标题标签 {@code <title>} */
    public static final String TAG_TITLE     = "title";
    /** 静态行标签 {@code <line>} */
    public static final String TAG_LINE      = "line";
    /** 条件标签 {@code <if>}（行区域=块级条件，line/title 内=行内条件） */
    public static final String TAG_IF        = "if";
    /** 否则如果标签 {@code <elif>} */
    public static final String TAG_ELIF      = "elif";
    /** 否则标签 {@code <else>} */
    public static final String TAG_ELSE      = "else";
    /** 循环标签 {@code <for>}（行区域=块级循环，line/title 内=行内循环） */
    public static final String TAG_FOR       = "for";
    /** 元数据标签 {@code <meta key="..." value="..."/>}（{@code <template>} 直接子级，可多个） */
    public static final String TAG_META      = "meta";
    /** 列配置块标签 {@code <columns>}（{@code <template>} 直接子级，最多一个） */
    public static final String TAG_COLUMNS   = "columns";
    /** 单列策略标签 {@code <column align="..." width="..."/>}（{@code <columns>} 子级） */
    public static final String TAG_COLUMN    = "column";

    // ===== XML 属性名 =====

    /** 条件表达式属性 {@code <if cond="">} / {@code <elif cond="">} */
    public static final String ATTR_COND  = "cond";
    /** 循环数据源属性 {@code <for items="">} */
    public static final String ATTR_ITEMS = "items";
    /** 循环元素变量名属性 {@code <for var="item">}（缺省 {@code this}） */
    public static final String ATTR_VAR   = "var";
    /** 循环序号变量名属性 {@code <for index="i">}（缺省 {@code index}） */
    public static final String ATTR_INDEX = "index";
    /** 最大迭代数属性 {@code <for max="n">}（限制遍历元素数，超出截断） */
    public static final String ATTR_MAX   = "max";
    /** 列格式启用属性 {@code <template columns="|">}（根属性，值为分隔符） */
    public static final String ATTR_COLUMNS   = "columns";
    /** 分隔符属性（预留：{@code <columns>} 块级分隔符覆盖入口） */
    public static final String ATTR_SEPARATOR = "separator";
    /** 对齐方向属性 {@code <column align="left|center|right">} */
    public static final String ATTR_ALIGN     = "align";
    /** 列宽属性 {@code <column width="auto|n">} */
    public static final String ATTR_WIDTH     = "width";
    /** 元数据键属性 {@code <meta key="...">} */
    public static final String ATTR_KEY       = "key";
    /** 元数据值属性 {@code <meta value="...">} */
    public static final String ATTR_VALUE     = "value";

    // ===== 插值定界符 =====

    /** 插值开始标记 {@code {{} */
    public static final String INTERP_OPEN  = "{{";
    /** 插值结束标记 {@code }}} */
    public static final String INTERP_CLOSE = "}}";

    // ===== 循环上下文变量 =====

    /** 循环内当前元素 {@code {{this}}} / {@code {{this.name}}} */
    public static final String CTX_THIS  = "this";
    /** 循环内当前序号 {@code {{index}}}（从 0 开始） */
    public static final String CTX_INDEX = "index";

    // ===== 列格式默认值 =====

    /** 默认列分隔符（根属性 {@code columns} 缺省、仅 {@code <columns>} 块启用时使用） */
    public static final String DEFAULT_COLUMN_SEPARATOR = "|";
    /** FIXED 列超宽截断的省略号（宽度计入固定列宽） */
    public static final String TRUNCATE_ELLIPSIS = "...";
}
