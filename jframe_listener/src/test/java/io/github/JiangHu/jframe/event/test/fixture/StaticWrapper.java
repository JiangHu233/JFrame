package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

/**
 * STATIC 模式 fixture：无 @KeyExtractor + 全 static 方法。
 * <p>
 * 验证点：走 EventEngine 兜底链（tailConsumers），无实例，
 * 始终在 OBJECT/SINGLETON（primaryConsumers）之后执行。
 * <p>
 * 执行记录写入 {@link ExecutionOrderTracker}，与 primary 消费者共享同一时间线。
 */
public class StaticWrapper {

    @EventRoute
    @EventHandler
    public static void onMove(MoveEvent event) {
        ExecutionOrderTracker.record("STATIC:" + event.getPlayerName());
    }
}
