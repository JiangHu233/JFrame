package io.github.JiangHu.jframe.content_template.ast;

/**
 * 纯文本节点——原样输出，不做任何求值。
 *
 * @param text 纯文本内容
 */
public record TextNode(String text) implements TemplateNode {
}
