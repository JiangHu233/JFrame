package io.github.JiangHu.jframe.serialize.exception;

import io.github.JiangHu.jframe.core.JFrameException;

/**
 * 序列化内核异常。
 * <p>
 * jframe_serialize 内核及其上层存储设施（jframe_file 文件持久化 / jframe_db 数据库存取）
 * 操作中发生的错误都会包装为本异常抛出（unchecked），包括：
 * <ul>
 *   <li>{@code @SaveField(required = true)} 的字段在 JSON 中缺失或为 null</li>
 *   <li>存储时对象缺少合法主键（如未实现 {@link SaveIdentifiable} 或 saveKey 为空）</li>
 *   <li>文件读写 IO 错误（目录创建失败、文件写入失败等）</li>
 *   <li>JSON / YAML 解析与序列化错误</li>
 *   <li>反射访问字段失败（无参构造器缺失、字段不可访问等）</li>
 * </ul>
 * <p>
 * 继承 {@link JFrameException}，调用方无需强制 try-catch；
 * 如需精细处理，可 catch 本异常并检查 {@link #getCause()} 获取根因。
 */
public class DataException extends JFrameException {

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
