package io.github.JiangHu.jframe.content_template.parser;

import io.github.JiangHu.jframe.core.JFrameException;

/**
 * 模板解析异常——XML 语法错误、标签不匹配等问题时抛出。
 */
public class TemplateParseException extends JFrameException {

    public TemplateParseException(String message) {
        super(message);
    }

    public TemplateParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
