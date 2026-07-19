package io.github.JiangHu.jframe.core.data.reactive;

import java.util.function.Consumer;

/**
 * 纠缠成员的<b>无类型根接口</b>，是异构纠缠的基础。
 *
 * <p>{@link EntangledChannel}（通道）以 {@code Entangled} 为统一视角管理其成员，
 * 因此同一通道内可以容纳<b>不同值类型</b>的 {@link EntangledValue}（如一个 {@code Integer}
 * 与一个 {@code String} 互相纠缠）。通道进行事件广播时，只依赖本接口暴露的无类型方法，
 * 无需关心成员的具体泛型参数。
 *
 * <p>本接口仅声明通道协作所需的最小契约：
 * <ul>
 *   <li>{@link #rawValue()} —— 读取无类型当前值（供事件构造与调试读取）。</li>
 *   <li>{@link #dispatch(EntangledEvent)} —— 由通道在广播时调用，向本成员的本地监听器派发事件。</li>
 *   <li>{@link #errorHandler()} —— 本地监听器抛出 {@link RuntimeException} 时使用的处理器。</li>
 * </ul>
 *
 * <p><b>类型安全说明</b>：异构通道内，事件携带的值类型可能与某成员声明的泛型不一致。
 * 同构通道（所有成员类型相同）下完全类型安全；异构场景下，监听器需自行判断 / 转换值类型。
 *
 * @see EntangledValue
 * @see EntangledChannel
 * @see EntangledEvent
 */
public interface Entangled {

    /**
     * 读取当前值（无类型视图）。
     *
     * @return 当前值，可能为 {@code null}
     */
    Object rawValue();

    /**
     * 由通道在<b>广播</b>时调用：向本成员的本地监听器派发指定事件。
     *
     * <p>单个监听器抛出的 {@link RuntimeException} 由 {@link #errorHandler()} 捕获，不影响其余监听器。
     *
     * @param event 要派发的事件
     */
    void dispatch(EntangledEvent<?> event);

    /**
     * 本地监听器异常处理器。
     *
     * @return 处理器，不应为 {@code null}
     */
    Consumer<RuntimeException> errorHandler();
}
