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
    /** 动态行展开标签 {@code <line-each>} */
    public static final String TAG_LINE_EACH = "line-each";
    /** 行内条件标签 {@code <if>} */
    public static final String TAG_IF        = "if";
    /** 行内否则如果标签 {@code <elif>} */
    public static final String TAG_ELIF      = "elif";
    /** 行内否则标签 {@code <else>} */
    public static final String TAG_ELSE      = "else";
    /** 行内循环标签 {@code <each>} */
    public static final String TAG_EACH      = "each";

    // ===== XML 属性名 =====

    /** 条件属性 {@code <line if="cond">} */
    public static final String ATTR_IF    = "if";
    /** 否则文本属性 {@code <line if="" else="text">} */
    public static final String ATTR_ELSE  = "else";
    /** 条件表达式属性 {@code <if cond="">} / {@code <elif cond="">} */
    public static final String ATTR_COND  = "cond";
    /** 循环数据源属性 {@code <each items="">} / {@code <line-each items="">} */
    public static final String ATTR_ITEMS = "items";
    /** 最大行数属性 {@code <line-each max="n">} */
    public static final String ATTR_MAX   = "max";

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
}
