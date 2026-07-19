package io.github.JiangHu.jframe.inventory.codec;

/**
 * 物品 / 物品栏序列化异常。
 * <p>
 * {@link ItemCodec} 与 {@link InventoryCodec} 在编解码过程中遇到的所有错误
 * （格式非法、版本不支持、Base64 解码失败、NBT 解析失败、容量不匹配等）
 * 统一包装为本异常抛出，属于非受检异常，调用方可按需捕获。
 *
 * @see ItemCodec
 * @see InventoryCodec
 */
public class InventoryCodecException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 描述信息
     */
    public InventoryCodecException(String message) {
        super(message);
    }

    /**
     * 构造异常并携带原始原因。
     *
     * @param message 描述信息
     * @param cause   原始异常
     */
    public InventoryCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
