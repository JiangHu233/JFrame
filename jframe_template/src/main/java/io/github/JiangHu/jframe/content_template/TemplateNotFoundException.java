package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.core.JFrameException;

/**
 * 模板未找到异常——所有已注册的 {@link io.github.JiangHu.jframe.content_template.loader.TemplateLoader}
 * 都无法找到指定名称的模板时抛出。
 */
public class TemplateNotFoundException extends JFrameException {

    public TemplateNotFoundException(String message) {
        super(message);
    }

    public TemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
