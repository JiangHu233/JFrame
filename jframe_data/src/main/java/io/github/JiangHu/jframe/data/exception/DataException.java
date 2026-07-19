package io.github.JiangHu.jframe.data.exception;

/**
 * 数据保存/加载异常。
 * <p>
 * 所有 {@link io.github.JiangHu.jframe.data.DataSaver DataSaver} 操作中发生的错误
 * 都会包装为本异常抛出（ unchecked），包括：
 * <ul>
 *   <li>{@code @SaveField(required = true)} 的字段在 JSON 中缺失或为 null</li>
 *   <li>调用 {@code save(obj)} 时对象未实现 {@link io.github.JiangHu.jframe.data.SaveIdentifiable}</li>
 *   <li>文件读写 IO 错误（目录创建失败、文件写入失败等）</li>
 *   <li>JSON 解析 / 序列化错误</li>
 *   <li>反射访问字段失败（无参构造器缺失、字段不可访问等）</li>
 * </ul>
 * <p>
 * 继承 {@link RuntimeException}，调用方无需强制 try-catch；
 * 如需精细处理，可 catch 本异常并检查 {@link #getCause()} 获取根因。
 *
 * @see io.github.JiangHu.jframe.data.DataSaver
 */
public class DataException extends RuntimeException {

    /**
     * 构造数据异常。
     *
     * @param message 错误描述
     */
    public DataException(String message) {
        super(message);
    }

    /**
     * 构造数据异常（带原因）。
     *
     * @param message 错误描述
     * @param cause   根因异常
     */
    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
