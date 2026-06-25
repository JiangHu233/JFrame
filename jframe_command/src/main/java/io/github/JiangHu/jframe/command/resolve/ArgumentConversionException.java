package io.github.JiangHu.jframe.command.resolve;

/**
 * 参数类型转换异常。
 * <p>
 * 当 {@link ArgumentResolver#convert} 无法将字符串值转换为目标类型时抛出
 * （如 {@code "abc"} 转 {@code int}、玩家名查无此人）。
 * <p>
 * 该异常是<b>可恢复的</b>：上层（{@link io.github.JiangHu.jframe.command.routing.CommandRegistry}）
 * 会捕获它并向命令发送者返回友好的错误提示，而非中断整个分发流程。
 *
 * @see ArgumentResolver
 */
public class ArgumentConversionException extends RuntimeException {

    /** 无法转换的原始值 */
    private final String rawValue;

    /** 目标类型 */
    private final Class<?> targetType;

    /**
     * 构造转换异常。
     *
     * @param rawValue  原始字符串值
     * @param targetType 目标类型
     */
    public ArgumentConversionException(String rawValue, Class<?> targetType) {
        super("无法将 \"" + rawValue + "\" 转换为 " + targetType.getSimpleName());
        this.rawValue = rawValue;
        this.targetType = targetType;
    }

    /** 原始字符串值 */
    public String getRawValue() {
        return rawValue;
    }

    /** 目标类型 */
    public Class<?> getTargetType() {
        return targetType;
    }
}
