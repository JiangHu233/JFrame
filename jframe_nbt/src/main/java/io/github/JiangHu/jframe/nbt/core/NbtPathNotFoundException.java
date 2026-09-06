package io.github.JiangHu.jframe.nbt.core;

/**
 * NBT 路径<b>命中数不满足「恰好一个」</b> —— {@code getSingle}/{@code selectSingle} 读取时
 * 命中 0 个或多个（DESIGN.md 3.5）。
 *
 * <p>注意：普通 {@code get}（返回 {@code List<Tag>}）0 命中返回空列表、不抛异常（读宽容）；
 * 本异常只用于「要求恰一」的严格读取形态。
 */
public class NbtPathNotFoundException extends NbtPathException {

    public NbtPathNotFoundException(String message) {
        super(message);
    }
}
