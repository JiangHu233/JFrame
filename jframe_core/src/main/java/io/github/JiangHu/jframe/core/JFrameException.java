package io.github.JiangHu.jframe.core;

/**
 * JFrame 框架异常基类 — 所有模块的业务异常统一继承本类。
 *
 * <h3>设计动机</h3>
 * <p>
 * 各模块（data / command / template / inventory 等）原本各自定义独立的
 * {@code RuntimeException} 子类，没有统一基类。调用方无法通过一次
 * {@code catch (JFrameException e)} 统一捕获框架异常。
 * <p>
 * 本类提供统一的异常层次根节点：
 * <ul>
 *   <li>调用方可 {@code catch (JFrameException)} 统一处理框架异常</li>
 *   <li>也可 {@code catch (DataException)} 等具体子类精确处理</li>
 * </ul>
 *
 * <h3>异常层次</h3>
 * <pre>
 * RuntimeException
 *   └── JFrameException          ← 本类（框架统一基类）
 *         ├── DataException
 *         ├── ArgumentConversionException
 *         ├── TemplateLoadException
 *         ├── TemplateNotFoundException
 *         ├── TemplateParseException
 *         └── InventoryCodecException
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 模块定义异常
 * public class DataException extends JFrameException {
 *     public DataException(String message) { super(message); }
 *     public DataException(String message, Throwable cause) { super(message, cause); }
 * }
 *
 * // 调用方统一捕获
 * try {
 *     dataSaver.save("key", value);
 *     engine.getTemplate("main");
 * } catch (JFrameException e) {
 *     // 统一处理框架异常
 *     logger.error("框架异常", e);
 * }
 * }</pre>
 *
 * @see RuntimeException
 */
public class JFrameException extends RuntimeException {

    /**
     * 构造带详细消息的异常。
     *
     * @param message 详细消息
     */
    public JFrameException(String message) {
        super(message);
    }

    /**
     * 构造带详细消息和原因的异常。
     *
     * @param message 详细消息
     * @param cause   根本原因
     */
    public JFrameException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造带原因的异常。
     *
     * @param cause 根本原因
     */
    public JFrameException(Throwable cause) {
        super(cause);
    }
}
