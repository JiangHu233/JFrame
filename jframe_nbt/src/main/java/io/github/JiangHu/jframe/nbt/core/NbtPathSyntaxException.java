package io.github.JiangHu.jframe.nbt.core;

/**
 * NBT 路径<b>语法错误</b> —— 表达式不符合 NbtPath DSL 语法（DESIGN.md 第二章 EBNF）。
 *
 * <p>消息携带：出错原因、原始表达式、出错位置（0 起字符偏移），便于定位，
 * 例如：{@code NBT 路径语法错误：未闭合的 '['（表达式 "Items[0"，位置 7）}。
 *
 * <p>触发场景举例：未闭合的 {@code [}、空键名、非法转义、数值后缀值域越界（{@code 300b}）、
 * 使用 M2 期语法（{@code [?(...)]} 谓词、{@code *} 键通配）等。
 */
public class NbtPathSyntaxException extends NbtPathException {

    /** 原始表达式（便于日志与上层反馈）。 */
    private final String expression;

    /** 出错位置（0 起字符偏移；-1 表示无精确定位）。 */
    private final int position;

    public NbtPathSyntaxException(String message, String expression, int position) {
        super(message + "（表达式 \"" + expression + "\"，位置 " + position + "）");
        this.expression = expression;
        this.position = position;
    }

    public String getExpression() {
        return expression;
    }

    public int getPosition() {
        return position;
    }
}
