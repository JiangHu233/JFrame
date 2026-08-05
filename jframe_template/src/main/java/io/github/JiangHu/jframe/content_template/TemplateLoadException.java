package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.core.JFrameException;

/**
 * 模板加载异常——{@link io.github.JiangHu.jframe.content_template.loader.TemplateLoader}
 * 在加载模板源码时发生 IO 错误或其他异常时抛出。
 */
public class TemplateLoadException extends JFrameException {

    public TemplateLoadException(String message) {
        super(message);
    }

    public TemplateLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
