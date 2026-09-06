package io.github.JiangHu.jframe.nbt.core;

/**
 * NBT 路径工具异常基类 —— 本模块全部异常的公共父类。
 *
 * <p>对齐 jframe 家族惯例：非受检（继承 {@link RuntimeException}）、消息中文、场景化
 * （参考 {@code InventoryCodecException} 与 {@code ArgumentConversionException}）。
 *
 * <p>子类分工（DESIGN.md 3.5）：
 * <ul>
 *   <li>{@link NbtPathSyntaxException} —— 路径表达式语法错误（含出错位置）</li>
 *   <li>{@link NbtPathTypeException} —— 路径段 × 节点类型冲突、set 的类型校验失败</li>
 *   <li>{@link NbtTypeMismatchException} —— Java 值 ↔ Tag 自动转换失败（第四章类型识别层）</li>
 *   <li>{@link NbtPathNotFoundException} —— 「要求恰好一个命中」的读取遇到 0 命中或多命中</li>
 *   <li>{@link NbtModifyException} —— 写入失败（0 命中且不允许 createPath、insert 越界等）</li>
 * </ul>
 */
public class NbtPathException extends RuntimeException {

    public NbtPathException(String message) {
        super(message);
    }

    public NbtPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
