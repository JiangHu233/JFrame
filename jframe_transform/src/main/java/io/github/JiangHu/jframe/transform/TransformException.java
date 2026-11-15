package io.github.JiangHu.jframe.transform;

/**
 * 动态类增强模块异常 — 自附加失败 / 目标非法 / 注册期校验失败等场景。
 * <p>
 * 自附加失败的异常消息包含诊断建议（精简 JRE 缺 {@code jdk.attach} /
 * {@code -Djdk.attach.allowAttachSelf=true} / {@code -XX:+EnableDynamicAgentLoading} /
 * {@code -javaagent} 预注入 / 容器禁 attach），业务插件捕获后可按需降级。
 */
public class TransformException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 消息
     */
    public TransformException(String message) {
        super(message);
    }

    /**
     * 构造异常（带原因）。
     *
     * @param message 消息
     * @param cause   原因
     */
    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
