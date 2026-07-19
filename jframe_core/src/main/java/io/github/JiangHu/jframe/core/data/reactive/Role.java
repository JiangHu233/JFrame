package io.github.JiangHu.jframe.core.data.reactive;

/**
 * 纠缠成员在通道内的<b>收发方向角色</b>。
 *
 * <p>每个纠缠成员（{@link EntangledValue}）加入通道（{@link EntangledChannel}）时都会绑定一个角色，
 * 角色由两个独立开关组合而成：
 * <ul>
 *   <li><b>{@link #receive}</b>：是否<b>接收</b>通道内其他成员发来的通知（即「听」）。</li>
 *   <li><b>{@link #send}</b>：自身 {@link EntangledValue#set} 时是否向通道<b>广播</b>通知（即「发」）。</li>
 * </ul>
 *
 * <p>本枚举提供 4 个常用预设；若需更细粒度控制，可直接通过
 * {@link EntangledValue#setReceiving(boolean)} / {@link EntangledValue#setSending(boolean)}
 * 或通道侧 {@link EntangledChannel#setReceiving(Entangled, boolean)} 等方法单独调整任一开关。
 *
 * <h3>四种预设</h3>
 * <table border="1">
 *   <caption>角色与收发开关对照</caption>
 *   <tr><th>角色</th><th>receive（听）</th><th>send（发）</th><th>典型用途</th></tr>
 *   <tr><td>{@link #BOTH}</td><td>✅</td><td>✅</td><td>双向同步的普通成员（默认）</td></tr>
 *   <tr><td>{@link #SOURCE}</td><td>❌</td><td>✅</td><td>传感器 / 数据源——产生数据但不关心他人</td></tr>
 *   <tr><td>{@link #SINK}</td><td>✅</td><td>❌</td><td>显示器 / 消费者——只响应不主动广播</td></tr>
 *   <tr><td>{@link #MUTE}</td><td>❌</td><td>❌</td><td>静默成员——留在通道但暂不收发</td></tr>
 * </table>
 *
 * <h3>广播门控规则</h3>
 * <p>广播时，触发者与所有其他成员一视同仁——统一按各自「接收」开关决定是否派发事件：
 * <ul>
 *   <li><b>SOURCE 成员</b>（{@code receive=false}）：不收到任何通知，<b>包括自身 set 产生的事件</b>。</li>
 *   <li><b>SINK 成员</b>（{@code send=false}）：其 {@link EntangledValue#set} 不产生任何广播通知。</li>
 *   <li><b>BOTH 成员</b>（{@code receive=true}）：会收到通道内任意成员（含自身）set 产生的事件。</li>
 * </ul>
 *
 * @see EntangledChannel
 * @see EntangledValue
 */
public enum Role {

    /** 听发皆可：既接收他人通知，自身 set 也广播。默认角色。 */
    BOTH(true, true),

    /** 只发不收：自身 set 会广播，但不接收他人通知。适合数据源 / 传感器。 */
    SOURCE(false, true),

    /** 只听不发：接收他人通知，但自身 set 不广播。适合显示器 / 消费者。 */
    SINK(true, false),

    /** 既不听也不发：留在通道内但暂停收发。适合临时静默。 */
    MUTE(false, false);

    /** 是否接收通道内其他成员的通知。 */
    public final boolean receive;

    /** 自身 set 时是否向通道广播通知。 */
    public final boolean send;

    Role(boolean receive, boolean send) {
        this.receive = receive;
        this.send = send;
    }

    /**
     * 根据两个开关推断最匹配的预设角色。
     *
     * @param receive 是否接收
     * @param send    是否发送
     * @return 与给定开关匹配的 {@link Role}；无精确匹配时返回 {@link #BOTH}
     */
    public static Role of(boolean receive, boolean send) {
        for (Role r : values()) {
            if (r.receive == receive && r.send == send) {
                return r;
            }
        }
        return BOTH;
    }
}
