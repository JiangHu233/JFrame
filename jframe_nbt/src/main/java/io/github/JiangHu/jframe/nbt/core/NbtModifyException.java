package io.github.JiangHu.jframe.nbt.core;

/**
 * NBT <b>写入失败</b> —— 修改操作无法按语义完成（DESIGN.md 2.5 / 3.5）。
 *
 * <p>触发场景：
 * <ul>
 *   <li>{@code set}/{@code merge} 0 命中且未开启 {@code NbtWriteOption.createPath}
 *       （默认 fail-fast，开放问题 #4 建议默认值，防止手滑路径写错时静默新建一棵歪树）；</li>
 *   <li>{@code set} 0 命中且中间节点非 Compound（无法继续导航，写时严格）；</li>
 *   <li>{@code insert} 下标越界、目标不存在或非 List/数组；</li>
 *   <li>{@code merge} 目标非 Compound；</li>
 *   <li>对根节点自身的 set/delete 等无父容器操作。</li>
 * </ul>
 *
 * <p>注意：{@code delete} 0 命中<b>静默成功</b>（幂等），不抛本异常。
 */
public class NbtModifyException extends NbtPathException {

    public NbtModifyException(String message) {
        super(message);
    }
}
