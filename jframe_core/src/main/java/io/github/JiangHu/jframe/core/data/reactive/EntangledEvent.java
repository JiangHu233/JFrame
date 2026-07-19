package io.github.JiangHu.jframe.core.data.reactive;

/**
 * 纠缠值变化事件。
 *
 * <p>当 {@link EntangledValue} 的值通过 {@link EntangledValue#set(Object)} 更新时产生，
 * 并由所属 {@link EntangledChannel}（通道）广播给通道内所有成员（含触发者自身）的监听器，
 * 以及通道级监听器。
 *
 * <p><b>事件语义</b>：本事件始终描述「<b>触发者</b> {@link #source()} 的值变化」，
 * 即 {@code source} 把值从 {@link #oldValue()} 改成了 {@link #newValue()}。
 * 无论是否开启镜像模式，事件内容都相同——镜像模式只是额外保证通道内其他成员的值
 * 也被同步为 {@code newValue}。监听器可通过 {@code event.source() == myValue}
 * 判断本次变化是否由自身触发。
 *
 * <p><b>异构支持</b>：{@link #source()} 的类型为无类型根 {@link Entangled}，因此同一通道内
 * 可容纳不同值类型的成员（异构纠缠）。{@link #oldValue()} / {@link #newValue()} 的泛型 {@code T}
 * 取自触发者；在同构通道中类型安全，在异构通道中监听器需自行判断 / 转换值类型。
 *
 * <p>本类为不可变 {@code record}，线程安全。
 *
 * @param source   触发本次更新的纠缠成员（调用 {@code set} 的成员，类型为 {@link Entangled}），不会为 {@code null}
 * @param oldValue 触发者更新前的值（可能为 {@code null}）
 * @param newValue 触发者更新后的值（可能为 {@code null}）
 * @param <T>      触发者的值类型
 *
 * @see EntangledValue
 * @see EntangledChannel
 * @see Entangled
 */
public record EntangledEvent<T>(Entangled source, T oldValue, T newValue) {
}
