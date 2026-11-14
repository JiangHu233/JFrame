package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合写法 fixture：无 @KeyExtractor + 实例 handler + static handler 共存。
 * <p>
 * 回归场景：修复前 {@code detectMode} 是类级判定，只要存在实例 handler，
 * {@code allMatch(isHandlerStatic)} 即为 false，整个类被降级为 SINGLETON，
 * 导致 static handler {@link #onMoveStatic} 被卷入 primary 链路，丢失兜底语义。
 * <p>
 * 修复后按 handler 级别分流：
 * <ul>
 *   <li>{@link #onMove}（实例方法）→ SINGLETON，走 primary 链路</li>
 *   <li>{@link #onMoveStatic}（static 方法）→ STATIC，走 tail 兜底链，最后执行</li>
 * </ul>
 * 执行记录写入内部 list，与 {@link ExecutionOrderTracker} 共享同一时间线语义。
 */
public class MixedStaticSingletonWrapper {

    private static final List<String> order = new ArrayList<>();
    private static int instanceCount = 0;

    /** 无参构造：实例 handler 走 SINGLETON 模式必需 */
    public MixedStaticSingletonWrapper() {
        instanceCount++;
    }

    /** 实例 handler：走 SINGLETON（primary 链路） */
    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        order.add("INSTANCE:" + event.getPlayerName());
    }

    /** static handler：走 STATIC（tail 兜底链，最后执行） */
    @EventRoute
    @EventHandler
    public static void onMoveStatic(MoveEvent event) {
        order.add("STATIC:" + event.getPlayerName());
    }

    public static List<String> getOrder() {
        return order;
    }

    public static int getInstanceCount() {
        return instanceCount;
    }

    public static void reset() {
        order.clear();
        instanceCount = 0;
    }
}
