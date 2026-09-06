package io.github.JiangHu.jframe.nbt.core;

/**
 * NBT 路径<b>类型不匹配</b> —— 「路径段 × 节点类型」冲突，或 set 替换时的类型校验失败。
 *
 * <p>与 {@link NbtTypeMismatchException} 的分工（DESIGN.md 3.5）：
 * <ul>
 *   <li>本异常管<b>路径语义层</b>：段类型与节点类型冲突（如对 IntTag 用 {@code [0]} 寻址）、
 *       {@code set} 直传 Tag 时与旧值 NBT 类型不同（防 {@code 1b} 被写成 {@code 1} 破坏游戏行为，
 *       可用 {@code NbtWriteOption.typeCheck=false} 关闭）；</li>
 *   <li>{@link NbtTypeMismatchException} 管<b>值转换层</b>：Java 值 ↔ Tag 的自动识别/转换失败。</li>
 * </ul>
 */
public class NbtPathTypeException extends NbtPathException {

    public NbtPathTypeException(String message) {
        super(message);
    }
}
