package io.github.JiangHu.jframe.nbt.core.path;

import io.github.JiangHu.jframe.nbt.core.value.ListPolicy;

/**
 * 写入选项（DESIGN.md 2.5 / 4.6）——set/insert/merge 的行为开关集合。
 *
 * <p>不可变 record；全部字段有默认值（{@link #DEFAULT}），按需覆盖：
 * <pre>{@code
 * NbtWriteOption opt = NbtWriteOption.DEFAULT.withCreatePath(true);
 * }</pre>
 *
 * @param typeCheck  set 替换时与旧值做 NBT 类型校验（默认 true，防 {@code 1b} 被写成 {@code 1}
 *                  破坏游戏行为；关闭即宽松模式）
 * @param createPath 0 命中时自动创建中间 Compound 并写入（默认 false = fail-fast 抛
 *                  {@code NbtModifyException}，开放问题 #4 建议默认值，可在 M2 复议；
 *                  仅对「尾段为键」的路径生效，中间段含下标/过滤仍抛）
 * @param append     insert 下标越界时改为末尾追加（默认 false = 越界抛异常）
 * @param listPolicy List 元素异构策略（默认 {@link ListPolicy#REJECT}，见 4.2）
 * @param autoFit    set 传 Object 值时的旧值类型无损适配（默认 true，开放问题 #10 建议默认值，
 *                  可在 M2 复议；「读出 → 修改 → 写回」往返不改变原树类型布局）
 */
public record NbtWriteOption(
        boolean typeCheck,
        boolean createPath,
        boolean append,
        ListPolicy listPolicy,
        boolean autoFit) {

    /** 全默认选项。 */
    public static final NbtWriteOption DEFAULT = new NbtWriteOption(
            true, false, false, ListPolicy.REJECT, true);

    public NbtWriteOption {
        if (listPolicy == null) {
            throw new NullPointerException("listPolicy 不可为 null");
        }
    }

    public NbtWriteOption withTypeCheck(boolean typeCheck) {
        return new NbtWriteOption(typeCheck, createPath, append, listPolicy, autoFit);
    }

    public NbtWriteOption withCreatePath(boolean createPath) {
        return new NbtWriteOption(typeCheck, createPath, append, listPolicy, autoFit);
    }

    public NbtWriteOption withAppend(boolean append) {
        return new NbtWriteOption(typeCheck, createPath, append, listPolicy, autoFit);
    }

    public NbtWriteOption withListPolicy(ListPolicy listPolicy) {
        return new NbtWriteOption(typeCheck, createPath, append, listPolicy, autoFit);
    }

    public NbtWriteOption withAutoFit(boolean autoFit) {
        return new NbtWriteOption(typeCheck, createPath, append, listPolicy, autoFit);
    }
}
