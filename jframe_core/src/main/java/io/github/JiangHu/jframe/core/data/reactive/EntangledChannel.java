package io.github.JiangHu.jframe.core.data.reactive;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 纠缠通道——纠缠体系的<b>协调中枢</b>，独立的一等公民。
 *
 * <p>通道是多个 {@link EntangledValue}（成员）之间的「广播域」：加入同一通道的成员互相纠缠，
 * 任一成员 {@link EntangledValue#set} 时，由通道负责把变化通知给其他成员。通道本身不依附于任何成员，
 * 可以独立创建、持有、传递，并可选挂一个 {@link #setName(String) 名称} 与 {@link #setOwner(Object) 归属对象}
 * 用于标识 / 生命周期管理。
 *
 * <h3>两类监听点（双注册）</h3>
 * <ul>
 *   <li><b>通道级监听器</b>（{@link #subscribe(Consumer)}）：以<b>通道身份</b>执行，能看到整组的变化，
 *       适合做中心化协调（日志、聚合、转发）。广播时<b>最先</b>触发（阶段①）。</li>
 *   <li><b>成员级监听器</b>（{@link EntangledValue#addListener}）：以<b>对象身份</b>执行，各成员各自处理，
 *       适合自治逻辑。广播时在通道级之后触发（阶段②）。</li>
 * </ul>
 *
 * <h3>两阶段广播顺序</h3>
 * <pre>{@code
 *   成员 set → 通道.broadcast
 *      ├─ 阶段①：触发通道级监听器（通道身份）
 *      └─ 阶段②：统一遍历所有成员（含触发者），按各自「接收」开关派发
 * }</pre>
 *
 * <h3>成员角色（收发方向）</h3>
 * <p>每个成员在通道内绑定一个 {@link Role}（由 {@code receive} / {@code send} 两开关组合）：
 * <ul>
 *   <li>{@link Role#BOTH} 听发皆可（默认）；{@link Role#SOURCE} 只发不收；{@link Role#SINK} 只听不发；{@link Role#MUTE} 不听不发。</li>
 * </ul>
 * 可在<b>加入时</b>指定（{@link #add(Entangled, Role)} / {@link #addSource(Entangled)} / {@link #addSink(Entangled)}），
 * 也可<b>运行时</b>调整（{@link #setRole(Entangled, Role)} / {@link #setReceiving(Entangled, boolean)} /
 * {@link #setSending(Entangled, boolean)}）。
 *
 * <h3>对象侧与通道侧对偶</h3>
 * <p>绑定关系可从任一侧发起，结果一致：
 * <table border="1"><caption>对偶 API</caption>
 *   <tr><th>操作</th><th>对象侧（{@code EntangledValue}）</th><th>通道侧（本类）</th></tr>
 *   <tr><td>加入（默认 BOTH）</td><td>{@code value.join(ch)}</td><td>{@code ch.add(value)}</td></tr>
 *   <tr><td>加入为只发</td><td>{@code value.joinAsSource(ch)}</td><td>{@code ch.addSource(value)}</td></tr>
 *   <tr><td>加入为只听</td><td>{@code value.joinAsSink(ch)}</td><td>{@code ch.addSink(value)}</td></tr>
 *   <tr><td>离开</td><td>{@code value.leave()}</td><td>{@code ch.remove(value)}</td></tr>
 *   <tr><td>改角色</td><td>{@code value.setRole(role)}</td><td>{@code ch.setRole(value, role)}</td></tr>
 * </table>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>所有结构变更（加入 / 离开 / 角色调整 / 通道合并）与广播快照，通过静态锁 {@link #LOCK}
 *       （整个纠缠体系共用）串行化。</li>
 *   <li>通道级监听器列表为 {@link CopyOnWriteArrayList}；监听器回调在<b>锁外</b>执行。</li>
 *   <li>单个监听器抛出的 {@link RuntimeException} 由 {@link #errorHandler} 捕获，不影响其余监听器。</li>
 * </ul>
 *
 * @see EntangledValue
 * @see Entangled
 * @see Role
 * @see EntangledEvent
 */
@Accessors(chain = true)
public class EntangledChannel {

    /** 保护所有结构变更与广播快照的静态锁（整个纠缠体系共用）。 */
    static final Object LOCK = new Object();

    /** 成员 → 收发绑定（按对象身份）。结构受 {@link #LOCK} 保护。 */
    private final IdentityHashMap<Entangled, MemberBinding> members = new IdentityHashMap<>();

    /** 通道级监听器（以通道身份执行，先于成员级）。 */
    private final List<Consumer<EntangledEvent<?>>> subscribers = new CopyOnWriteArrayList<>();

    /** 可选名称（调试 / 标识用），不影响逻辑。 */
    @Getter
    @Setter
    private volatile String name;

    /** 可选归属对象（生命周期管理 / 调试用），不影响逻辑。 */
    @Getter
    @Setter
    private volatile Object owner;

    /** 通道级监听器异常处理器，默认打印到 {@code System.err}。 */
    @Setter
    private Consumer<RuntimeException> errorHandler = EntangledChannel::defaultErrorHandler;

    /** 创建一个匿名通道。 */
    public EntangledChannel() {
    }

    /**
     * 创建一个带名称的通道。
     *
     * @param name 通道名称（可选，仅用于标识）
     */
    public EntangledChannel(String name) {
        this.name = name;
    }

    // ==================== 成员管理（通道侧） ====================

    /**
     * 加入成员（默认 {@link Role#BOTH}）。
     *
     * @param m 成员，非 null
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel add(Entangled m) {
        return add(m, Role.BOTH);
    }

    /**
     * 加入成员为{@link Role#SOURCE 只发}。
     *
     * @param m 成员，非 null
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel addSource(Entangled m) {
        return add(m, Role.SOURCE);
    }

    /**
     * 加入成员为{@link Role#SINK 只听}。
     *
     * @param m 成员，非 null
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel addSink(Entangled m) {
        return add(m, Role.SINK);
    }

    /**
     * 加入成员并指定角色。
     *
     * @param m    成员，非 null
     * @param role 角色，非 null
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel add(Entangled m, Role role) {
        return add(m, role.receive, role.send);
    }

    /**
     * 加入成员并指定收发开关（最细粒度）。
     *
     * <p>若该成员已在别的通道，会先自动离开旧通道。
     *
     * @param m       成员，非 null
     * @param receive 是否接收他人通知
     * @param send    自身 set 是否广播
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel add(Entangled m, boolean receive, boolean send) {
        Objects.requireNonNull(m, "member");
        if (m instanceof EntangledValue<?> mv) {
            mv.bindChannel(this);
        }
        synchronized (LOCK) {
            members.put(m, new MemberBinding(receive, send));
        }
        return this;
    }

    /**
     * 移除成员。
     *
     * @param m 要移除的成员
     * @return {@code true} 表示该成员原本在本通道且已移除
     */
    public boolean remove(Entangled m) {
        if (m instanceof EntangledValue<?> mv) {
            mv.unbindChannel(this);
        }
        synchronized (LOCK) {
            return members.remove(m) != null;
        }
    }

    // ==================== 角色 / 收发开关（通道侧） ====================

    /**
     * 设置成员在通道内的角色。
     *
     * @param m    成员
     * @param role 新角色
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel setRole(Entangled m, Role role) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            if (b != null) {
                b.receive = role.receive;
                b.send = role.send;
            }
        }
        return this;
    }

    /**
     * 单独设置成员的「接收」开关。
     *
     * @param m       成员
     * @param receive 是否接收
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel setReceiving(Entangled m, boolean receive) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            if (b != null) {
                b.receive = receive;
            }
        }
        return this;
    }

    /**
     * 单独设置成员的「发送」开关。
     *
     * @param m    成员
     * @param send 是否发送
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel setSending(Entangled m, boolean send) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            if (b != null) {
                b.send = send;
            }
        }
        return this;
    }

    /**
     * 查询成员在通道内的角色。
     *
     * @param m 成员
     * @return 角色；成员不在本通道时返回 {@code null}
     */
    public Role roleOf(Entangled m) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            return b == null ? null : Role.of(b.receive, b.send);
        }
    }

    /**
     * 成员是否处于「接收」状态。
     *
     * @param m 成员
     * @return {@code true} 表示会接收他人通知
     */
    public boolean isReceiving(Entangled m) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            return b != null && b.receive;
        }
    }

    /**
     * 成员是否处于「发送」状态。
     *
     * @param m 成员
     * @return {@code true} 表示其 set 会广播
     */
    public boolean isSending(Entangled m) {
        synchronized (LOCK) {
            MemberBinding b = members.get(m);
            return b != null && b.send;
        }
    }

    // ==================== 通道级监听器 ====================

    /**
     * 添加通道级监听器（以通道身份执行，广播阶段①触发）。
     *
     * @param listener 监听器，非 null
     * @return 当前通道（便于链式调用）
     */
    public EntangledChannel subscribe(Consumer<EntangledEvent<?>> listener) {
        subscribers.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * 移除一个通道级监听器。
     *
     * @param listener 要移除的监听器
     * @return {@code true} 表示移除成功
     */
    public boolean unsubscribe(Consumer<EntangledEvent<?>> listener) {
        return subscribers.remove(listener);
    }

    /** 移除所有通道级监听器。 */
    public void clearSubscribers() {
        subscribers.clear();
    }

    // ==================== 查询 ====================

    /** 成员数量。 */
    public int size() {
        synchronized (LOCK) {
            return members.size();
        }
    }

    /** 是否无成员。 */
    public boolean isEmpty() {
        synchronized (LOCK) {
            return members.isEmpty();
        }
    }

    /**
     * 是否包含指定成员。
     *
     * @param m 成员
     * @return {@code true} 表示该成员在本通道
     */
    public boolean contains(Entangled m) {
        synchronized (LOCK) {
            return members.containsKey(m);
        }
    }

    /**
     * 返回成员快照（不可变视图的副本）。
     *
     * @return 成员列表副本
     */
    public List<Entangled> members() {
        return memberSnapshot();
    }

    // ==================== 广播内核（包私有，供 EntangledValue 调用） ====================

    /** 成员快照（内部用）。 */
    List<Entangled> memberSnapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(members.keySet());
        }
    }

    /** 注册成员绑定（不触碰成员的 channel 字段）。调用方负责已持有正确同步。 */
    void membersPut(Entangled m, boolean receive, boolean send) {
        synchronized (LOCK) {
            members.put(m, new MemberBinding(receive, send));
        }
    }

    /** 仅从 members 移除（不触碰成员的 channel 字段），供成员切换通道时清理旧通道用。 */
    void removeInternal(Entangled m) {
        synchronized (LOCK) {
            members.remove(m);
        }
    }

    /**
     * 把另一通道的所有成员并入本通道，并清空另一通道。调用方须持有 {@link #LOCK}。
     * 镜像标志取「或」。
     */
    void mergeFrom(EntangledChannel other) {
        if (other == this) {
            return;
        }
        synchronized (LOCK) {
            for (var e : other.members.entrySet()) {
                this.members.put(e.getKey(), e.getValue());
                if (e.getKey() instanceof EntangledValue<?> mv) {
                    mv.channel = this;
                }
            }
            other.members.clear();
        }
    }

    /**
     * 两阶段广播。由 {@link EntangledValue#set} / {@link EntangledValue#mutate} 在锁外调用。
     *
     * @param trigger 触发者
     * @param oldVal  触发者旧值
     * @param newVal  触发者新值
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    void broadcast(Entangled trigger, Object oldVal, Object newVal) {
        MemberBinding tb;
        List<Entangled> snapshot;
        synchronized (LOCK) {
            tb = members.get(trigger);
            snapshot = new ArrayList<>(members.keySet());
        }
        EntangledEvent event = new EntangledEvent(trigger, oldVal, newVal);

        // 触发者无发送权：不产生任何广播通知（不触发通道级，也不通知任何成员）
        if (tb == null || !tb.send) {
            return;
        }
        // 阶段①：通道级监听器（以通道身份执行）
        for (Consumer<EntangledEvent<?>> l : subscribers) {
            try {
                ((Consumer) l).accept(event);
            } catch (RuntimeException e) {
                if (errorHandler != null) {
                    errorHandler.accept(e);
                }
            }
        }
        // 阶段②：成员级监听器——统一遍历所有成员（含触发者），按各自「接收」开关派发
        for (Entangled m : snapshot) {
            boolean receive;
            synchronized (LOCK) {
                MemberBinding mb = members.get(m);
                receive = mb != null && mb.receive;
            }
            if (receive) {
                m.dispatch(event);
            }
        }
    }

    // ==================== 内部 ====================

    private static void defaultErrorHandler(RuntimeException e) {
        System.err.println("[EntangledChannel] 监听器抛出异常: " + e);
        e.printStackTrace(System.err);
    }

    /** 成员收发绑定（volatile 保证可见性）。 */
    static final class MemberBinding {
        volatile boolean receive;
        volatile boolean send;

        MemberBinding(boolean receive, boolean send) {
            this.receive = receive;
            this.send = send;
        }
    }

    @Override
    public String toString() {
        synchronized (LOCK) {
            return "EntangledChannel{" + (name != null ? name + ", " : "")
                    + members.size() + " members}";
        }
    }
}
