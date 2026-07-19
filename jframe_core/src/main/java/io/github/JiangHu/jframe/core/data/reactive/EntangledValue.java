package io.github.JiangHu.jframe.core.data.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 纠缠值——一个可观察、可「纠缠」、类型化的值容器，实现无类型根 {@link Entangled}。
 *
 * <p>灵感取自量子纠缠：多个 {@code EntangledValue} 对象加入同一 {@link EntangledChannel 通道} 后互相<b>纠缠</b>，
 * 此后通道内<b>任意一个</b>对象的值发生更新，都会<b>通知</b>通道内其他对象。这是一种轻量、双向、可传递的
 * 响应式关联，非常适合「一处变化、多处感知」的场景：同一份配置 / 状态被多个界面（GUI、计分板、聊天提示）引用，
 * 任一界面修改后其余界面自动刷新。
 *
 * <h3>核心概念</h3>
 * <ul>
 *   <li><b>通道（{@link EntangledChannel}）</b>：纠缠的协调中枢，独立一等公民。成员加入同一通道即互相纠缠。
 *       纠缠是等价关系——{@code a} 与 {@code b} 同通道、{@code b} 与 {@code c} 同通道，则三者互相纠缠。</li>
 *   <li><b>通知模式</b>：某成员 {@link #set} 新值后，<b>仅通知</b>其他成员（触发其监听器），<b>不改变</b>其值。</li>
 *   <li><b>角色（{@link Role}）</b>：每个成员在通道内有收发方向——{@link Role#BOTH 听发皆可}（默认）、
 *       {@link Role#SOURCE 只发}、{@link Role#SINK 只听}、{@link Role#MUTE 静默}。</li>
 * </ul>
 *
 * <h3>异构纠缠</h3>
 * <p>由于实现了无类型根 {@link Entangled}，<b>不同值类型</b>的 {@code EntangledValue} 可加入同一通道
 * （如 {@code EntangledValue<Integer>} 与 {@code EntangledValue<String>} 互相纠缠）。事件 {@link EntangledEvent}
 * 的 {@code source} 为 {@link Entangled}，值类型取自触发者；同构通道下类型安全，异构场景下监听器需自行转换。
 *
 * <h3>容器类型支持</h3>
 * <p>对于 {@link List} / {@link java.util.Map} / {@link java.util.Set} 等可变容器，直接 {@link #set} 替换整个容器
 * 往往不符合预期（引用未变时 {@link #distinct} 会跳过）。改用 {@link #mutate(Consumer)} 对容器<b>原地修改</b>，
 * 它会记录修改前的引用并在修改后广播「内容变化」事件。
 *
 * <h3>事件语义</h3>
 * <p>监听器收到的 {@link EntangledEvent} 始终描述「<b>触发者</b>的值变化」：
 * {@code (source=触发者, oldValue=触发者旧值, newValue=触发者新值)}。
 * 用 {@code event.source() == myValue} 判断是否由自身触发；读取自己当前值调用 {@link #get()}。
 *
 * <h3>两阶段广播</h3>
 * <p>某成员 {@link #set} 后，由通道按以下顺序广播（详见 {@link EntangledChannel}）：
 * <ol>
 *   <li><b>阶段①</b>：触发通道级监听器（{@link EntangledChannel#subscribe}，以通道身份执行）。</li>
 *   <li><b>阶段②</b>：统一遍历所有成员（含触发者），按各自「接收」开关派发成员级监听器（{@link #addListener}）。</li>
 * </ol>
 *
 * <h3>快速上手</h3>
 * <pre>{@code
 * // 方式一：便捷纠缠（自动创建隐式通道，支持异构）
 * var hp = EntangledValue.of(100);
 * var hud = EntangledValue.of(100);
 * hp.entangle(hud);
 * hud.addListener(e -> refreshHud(e.newValue()));
 * hp.set(80);   // hud 收到通知
 *
 * // 方式二：显式通道 + 角色（传感器→显示器单向流）
 * var ch = new EntangledChannel("combat");
 * var sensor = EntangledValue.of(0).joinAsSource(ch);  // 只发
 * var display = EntangledValue.of(0).joinAsSink(ch);   // 只听
 * sensor.set(50);   // display 收到；display.set 不会回流给 sensor
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>值字段为 {@code volatile}；监听器列表为 {@link CopyOnWriteArrayList}。</li>
 *   <li>所有结构变更（纠缠 / 加入 / 离开 / 角色调整）与 {@link #set} 的状态更新段，
 *       通过整个纠缠体系共用的静态锁 {@link EntangledChannel#LOCK} 串行化。</li>
 *   <li>监听器回调在<b>锁外</b>执行；单个监听器抛出的 {@link RuntimeException} 由 {@link #errorHandler} 捕获，
 *       不会中断其他监听器。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li><b>避免回调递归</b>：在监听器中对同通道的值再次 {@link #set} 会引发连锁广播，可能造成 {@link StackOverflowError}。</li>
 *   <li>纠缠关系基于对象身份（{@code ==}），而非值相等。</li>
 *   <li>{@link #leave()}（或 {@link #unentangle()}）后，本对象不再收发原通道的通知。</li>
 * </ul>
 *
 * @param <T> 值类型
 * @see EntangledChannel
 * @see Entangled
 * @see Role
 * @see EntangledEvent
 */
@Accessors(chain = true)
public class EntangledValue<T> implements Entangled {

    /** 当前值（volatile 保证可见性）。 */
    private volatile T value;

    /** 所属通道；{@code null} 表示尚未加入任何通道。结构受 {@link EntangledChannel#LOCK} 保护。 */
    EntangledChannel channel;

    /** 本地（成员级）监听器列表（并发安全，遍历为快照）。 */
    private final List<Consumer<EntangledEvent<T>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 去重模式：开启后，{@link #set} 的新旧值经 {@link Objects#equals} 判定相等时不触发任何通知。
     * 默认 {@code false}。注意：对可变容器的<b>原地修改</b>请用 {@link #mutate(Consumer)}，不受本开关影响。
     */
    @Getter
    @Setter
    private boolean distinct = false;

    /**
     * 监听器异常处理器。某个监听器抛出 {@link RuntimeException} 时调用，
     * 默认实现将异常打印到 {@code System.err}。可替换为自定义日志 / 上报逻辑。
     */
    @Setter
    private Consumer<RuntimeException> errorHandler = EntangledValue::defaultErrorHandler;

    private EntangledValue(T value) {
        this.value = value;
    }

    // ==================== 工厂 ====================

    /**
     * 创建一个带初始值的纠缠值。
     *
     * @param value 初始值（可为 {@code null}）
     * @param <T>   值类型
     * @return 新建的纠缠值
     */
    public static <T> EntangledValue<T> of(T value) {
        return new EntangledValue<>(value);
    }

    /**
     * 创建一个值为 {@code null} 的纠缠值。
     *
     * @param <T> 值类型
     * @return 新建的纠缠值（值为 {@code null}）
     */
    public static <T> EntangledValue<T> empty() {
        return new EntangledValue<>(null);
    }

    // ==================== 读写 ====================

    /**
     * 获取当前值。
     *
     * @return 当前值（可能为 {@code null}）
     */
    public T get() {
        return value;
    }

    /**
     * 获取当前值，为 {@code null} 时返回默认值。
     *
     * @param defaultValue 默认值
     * @return 当前值，或 {@code defaultValue}
     */
    public T getOrDefault(T defaultValue) {
        T v = value;
        return v != null ? v : defaultValue;
    }

    /**
     * 当前值是否非 {@code null}。
     *
     * @return {@code true} 表示值非 {@code null}
     */
    public boolean isPresent() {
        return value != null;
    }

    /**
     * 设置新值并广播通知。
     *
     * <p>执行流程：
     * <ol>
     *   <li>若 {@link #distinct} 且新旧值 {@link Objects#equals} 相等，直接返回（不通知）。</li>
     *   <li>更新自身值。</li>
     *   <li>由通道执行两阶段广播（通道级 → 成员级）；无通道时仅触发自身监听器。</li>
     * </ol>
     *
     * <p>若当前角色无发送权（{@code send=false}，如 {@link Role#SINK}），本次 set 不产生任何广播通知；
     * 无发送权或无接收权的成员不会收到自身 set 的事件（详见 {@link EntangledChannel} 广播门控）。
     *
     * @param newValue 新值（可为 {@code null}）
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> set(T newValue) {
        Object oldVal;
        EntangledChannel ch;
        synchronized (EntangledChannel.LOCK) {
            oldVal = this.value;
            if (distinct && Objects.equals(oldVal, newValue)) {
                return this;
            }
            this.value = newValue;
            ch = this.channel;
        }
        if (ch != null) {
            ch.broadcast(this, oldVal, newValue);
        } else {
            this.dispatch(new EntangledEvent<>(this, oldVal, newValue));
        }
        return this;
    }

    /**
     * 对当前值执行<b>原地修改</b>并广播「内容变化」事件。
     *
     * <p>专为 {@link List} / {@link java.util.Map} / {@link java.util.Set} 等可变容器设计：
     * 先记录修改前的引用，应用 {@code mutator} 修改容器内容，再以「旧引用 → 同一引用（内容已变）」
     * 构造事件并广播。
     *
     * <p>与 {@link #set} 不同，{@code mutate} <b>不</b>受 {@link #distinct} 影响（内容变化总是广播）。
     *
     * @param mutator 修改操作，接收当前值（可为同一引用做原地修改），非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> mutate(Consumer<T> mutator) {
        Objects.requireNonNull(mutator, "mutator");
        Object oldVal;
        EntangledChannel ch;
        synchronized (EntangledChannel.LOCK) {
            oldVal = this.value;
            mutator.accept(this.value);
            ch = this.channel;
        }
        if (ch != null) {
            ch.broadcast(this, oldVal, this.value);
        } else {
            this.dispatch(new EntangledEvent<>(this, oldVal, this.value));
        }
        return this;
    }

    /**
     * 静默设置新值：更新值但<b>不触发</b>任何监听器、不广播。适用于初始化、批量同步等场景。
     *
     * @param newValue 新值（可为 {@code null}）
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> setSilently(T newValue) {
        synchronized (EntangledChannel.LOCK) {
            this.value = newValue;
        }
        return this;
    }

    // ==================== 纠缠管理（便捷，自动创建隐式通道，支持异构） ====================

    /**
     * 将当前对象与若干其他纠缠值纠缠到同一通道（支持<b>异构</b>类型）。
     *
     * <p>语义为「合并到同一通道」：若双方各自已有通道，则两个通道会被合并；若某方尚无通道，
     * 则加入当前对象所在（或新建）的通道。重复纠缠同一对象是安全的（幂等）。新加入的成员默认
     * {@link Role#BOTH}。
     *
     * @param others 其他纠缠值（可与本对象类型不同）
     * @return 当前对象（便于链式调用）
     */
    @SafeVarargs
    public final EntangledValue<T> entangle(EntangledValue<?>... others) {
        synchronized (EntangledChannel.LOCK) {
            EntangledChannel ch = ensureChannel();
            for (EntangledValue<?> other : others) {
                if (other == null || other == this) {
                    continue;
                }
                if (other.channel == null) {
                    other.channel = ch;
                    ch.membersPut(other, true, true);
                } else if (other.channel != ch) {
                    ch.mergeFrom(other.channel);
                }
            }
        }
        return this;
    }

    /**
     * 解除自身的纠缠关系（离开当前通道）。{@link #leave()} 的别名，保留以向后兼容。
     */
    public void unentangle() {
        leave();
    }

    // ==================== 通道加入 / 离开（对象侧） ====================

    /**
     * 加入指定通道（默认 {@link Role#BOTH}）。若已在别的通道，会先自动离开旧通道。
     *
     * @param ch 通道，非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> join(EntangledChannel ch) {
        return join(ch, Role.BOTH);
    }

    /**
     * 加入通道为{@link Role#SOURCE 只发}。
     *
     * @param ch 通道，非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> joinAsSource(EntangledChannel ch) {
        return join(ch, Role.SOURCE);
    }

    /**
     * 加入通道为{@link Role#SINK 只听}。
     *
     * @param ch 通道，非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> joinAsSink(EntangledChannel ch) {
        return join(ch, Role.SINK);
    }

    /**
     * 加入通道并指定角色。
     *
     * @param ch   通道，非 null
     * @param role 角色，非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> join(EntangledChannel ch, Role role) {
        return join(ch, role.receive, role.send);
    }

    /**
     * 加入通道并指定收发开关（最细粒度）。
     *
     * @param ch      通道，非 null
     * @param receive 是否接收他人通知
     * @param send    自身 set 是否广播
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> join(EntangledChannel ch, boolean receive, boolean send) {
        ch.add(this, receive, send);
        return this;
    }

    /**
     * 离开当前通道。此后本对象不再收发原通道的通知。未加入通道时为空操作。
     *
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> leave() {
        EntangledChannel ch = channel;
        if (ch != null) {
            ch.remove(this);
        }
        return this;
    }

    // ==================== 角色 / 收发开关（对象侧，作用于当前通道） ====================

    /**
     * 设置本对象在当前通道内的角色。未加入通道时为空操作。
     *
     * @param role 新角色
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> setRole(Role role) {
        EntangledChannel ch = channel;
        if (ch != null) {
            ch.setRole(this, role);
        }
        return this;
    }

    /**
     * 单独设置「接收」开关。未加入通道时为空操作。
     *
     * @param receive 是否接收
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> setReceiving(boolean receive) {
        EntangledChannel ch = channel;
        if (ch != null) {
            ch.setReceiving(this, receive);
        }
        return this;
    }

    /**
     * 单独设置「发送」开关。未加入通道时为空操作。
     *
     * @param send 是否发送
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> setSending(boolean send) {
        EntangledChannel ch = channel;
        if (ch != null) {
            ch.setSending(this, send);
        }
        return this;
    }

    /**
     * 本对象在当前通道内的角色。
     *
     * @return 角色；未加入通道时返回 {@code null}
     */
    public Role getRole() {
        EntangledChannel ch = channel;
        return ch == null ? null : ch.roleOf(this);
    }

    /** 是否处于「接收」状态（会收他人通知）。 */
    public boolean isReceiving() {
        EntangledChannel ch = channel;
        return ch != null && ch.isReceiving(this);
    }

    /** 是否处于「发送」状态（其 set 会广播）。 */
    public boolean isSending() {
        EntangledChannel ch = channel;
        return ch != null && ch.isSending(this);
    }

    // ==================== 状态查询 ====================

    /** 是否处于一个含 2 个及以上成员的通道中。 */
    public boolean isEntangled() {
        EntangledChannel ch = channel;
        return ch != null && ch.size() > 1;
    }

    /**
     * 是否与指定成员处于同一通道。
     *
     * @param other 另一个纠缠成员
     * @return {@code true} 表示同通道
     */
    public boolean isEntangledWith(Entangled other) {
        EntangledChannel ch = this.channel;
        return ch != null && other != null && ch.contains(other);
    }

    /**
     * 返回同通道其他成员的快照（不含自身）。未加入通道时返回空列表。
     *
     * @return 同通道其他成员列表
     */
    public List<Entangled> partners() {
        EntangledChannel ch = channel;
        if (ch == null) {
            return List.of();
        }
        List<Entangled> result = new ArrayList<>();
        for (Entangled m : ch.memberSnapshot()) {
            if (m != this) {
                result.add(m);
            }
        }
        return result;
    }

    /** 当前所属通道（可能为 {@code null}）。 */
    public EntangledChannel getChannel() {
        return channel;
    }

    // ==================== 监听器（成员级） ====================

    /**
     * 添加值变化监听器（成员级，广播阶段②触发）。
     *
     * <p>监听器在<b>自身或同通道任意成员</b> {@link #set} 时被触发（受角色门控）。
     * 同一监听器可被多次添加（会收到多次回调）。
     *
     * @param listener 监听器，非 null
     * @return 当前对象（便于链式调用）
     */
    public EntangledValue<T> addListener(Consumer<EntangledEvent<T>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * 移除一个监听器。
     *
     * @param listener 要移除的监听器
     * @return {@code true} 表示移除成功
     */
    public boolean removeListener(Consumer<EntangledEvent<T>> listener) {
        return listeners.remove(listener);
    }

    /** 移除所有监听器。 */
    public void clearListeners() {
        listeners.clear();
    }

    // ==================== Entangled 接口实现 ====================

    @Override
    public Object rawValue() {
        return value;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void dispatch(EntangledEvent<?> event) {
        for (Consumer<EntangledEvent<T>> l : listeners) {
            try {
                ((Consumer) l).accept(event);
            } catch (RuntimeException e) {
                if (errorHandler != null) {
                    errorHandler.accept(e);
                }
            }
        }
    }

    @Override
    public Consumer<RuntimeException> errorHandler() {
        return errorHandler;
    }

    // ==================== 内部实现 ====================

    /** 确保当前对象属于一个通道（无则新建仅含自身的），调用方须持有 {@link EntangledChannel#LOCK}。 */
    private EntangledChannel ensureChannel() {
        if (channel == null) {
            channel = new EntangledChannel();
            channel.membersPut(this, true, true);
        }
        return channel;
    }

    /** 由通道调用：绑定到指定通道（若已在别的通道，先从旧通道移除）。调用方同步由自身保证。 */
    void bindChannel(EntangledChannel ch) {
        synchronized (EntangledChannel.LOCK) {
            if (this.channel != null && this.channel != ch) {
                this.channel.removeInternal(this);
            }
            this.channel = ch;
        }
    }

    /** 由通道调用：解除与指定通道的绑定（仅当当前通道正是它时）。调用方同步由自身保证。 */
    void unbindChannel(EntangledChannel ch) {
        synchronized (EntangledChannel.LOCK) {
            if (this.channel == ch) {
                this.channel = null;
            }
        }
    }

    /** 默认异常处理器：打印到标准错误流。 */
    private static void defaultErrorHandler(RuntimeException e) {
        System.err.println("[EntangledValue] 监听器抛出异常: " + e);
        e.printStackTrace(System.err);
    }

    @Override
    public String toString() {
        EntangledChannel ch = channel;
        return "EntangledValue{" + value + "}"
                + (ch != null && ch.size() > 1
                ? "(channel:" + (ch.size() - 1) + " partners)"
                : "");
    }
}
