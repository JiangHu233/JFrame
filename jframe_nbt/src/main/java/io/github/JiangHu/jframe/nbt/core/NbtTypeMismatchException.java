package io.github.JiangHu.jframe.nbt.core;

import cn.nukkit.nbt.tag.Tag;

/**
 * NBT <b>自动类型识别/转换失败</b>（DESIGN.md 第四章）。
 *
 * <p>触发场景：
 * <ul>
 *   <li>写入方向（恒抛，写严格）：值类型不受支持（如 POJO）、异构 List 且未放行、
 *       BigInteger 超 long 值域、BigDecimal 丢精度（STRICT）、autoFit 不可无损容纳
 *       （旧 {@code ByteTag} + 传 {@code 300}）；</li>
 *   <li>读取方向（依 {@code CoerceMode}）：STRICT 下类型不符或窄化溢出/丢精度；
 *       LENIENT 下<b>不抛</b>本异常，而是降级为默认值 / {@code Optional.empty()}
 *       （开放问题 #8 建议默认值，可在 M2 复议）。</li>
 * </ul>
 *
 * <p>与 {@link NbtPathTypeException} 的分工：后者管「路径段 × 节点」冲突，
 * 本异常管「Java 值 ↔ Tag」转换失败。消息携带期望类型、实际类型与值上下文。
 */
public class NbtTypeMismatchException extends NbtPathException {

    public NbtTypeMismatchException(String message) {
        super(message);
    }

    /**
     * 携带期望/实际类型上下文的构造。
     *
     * @param message 场景描述（如「读取解包失败」）
     * @param expected 期望类型描述（如 {@code int}）
     * @param actual   实际类型描述（如 {@code StringTag}）
     */
    public NbtTypeMismatchException(String message, String expected, String actual) {
        super(message + "：期望 " + expected + "，实际 " + actual);
    }

    /** 便捷构造：以 Tag 的实际类型描述失败。 */
    public NbtTypeMismatchException(String message, String expected, Tag actualTag) {
        this(message, expected, actualTag == null ? "null" : actualTag.getClass().getSimpleName());
    }
}
