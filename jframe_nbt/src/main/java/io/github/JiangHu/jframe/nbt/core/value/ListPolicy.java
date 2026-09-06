package io.github.JiangHu.jframe.nbt.core.value;

/**
 * List 元素异构策略（DESIGN.md 4.2 / 4.6）——写入 List 归一的可配置开关。
 *
 * <p>NBT 规范要求 ListTag 同质；Nukkit 容器虽宽容，但异构列表跨工具/跨平台序列化会失真，
 * 故默认拒绝（写严格）。确需混存（如临时加工中间态）可显式放行，兼容性自负。
 *
 * <p>供 {@link NbtValues#ofList} 与 {@code NbtWriteOption.listPolicy} 共用；
 * 放在 value 包（而非 path 包）以维持 path → value 的单向依赖。
 */
public enum ListPolicy {
    /** 拒绝异构（默认，写严格）：非数值异构 List 抛 {@code NbtTypeMismatchException}。 */
    REJECT,
    /** 放行异构：按元素各自推断类型直接成表（Nukkit 允许，兼容性自负）。 */
    ACCEPT_HETEROGENEOUS
}
