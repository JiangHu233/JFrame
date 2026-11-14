package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * SINGLETON 模式 fixture：无 @KeyExtractor + 实例方法 + 无参构造。
 * <p>
 * 验证点：框架创建唯一单例实例，所有事件共享同一实例。
 * 多次分发后 instanceCount 始终为 1（单例复用）。
 */
public class SingletonWrapper {

    private static final List<String> invocations = new ArrayList<>();
    private static int instanceCount = 0;

    /** 无参构造：SINGLETON 模式必需 */
    public SingletonWrapper() {
        instanceCount++;
    }

    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        invocations.add(event.getPlayerName() + "@" + event.getX());
    }

    public static List<String> getInvocations() {
        return invocations;
    }

    public static int getInstanceCount() {
        return instanceCount;
    }

    public static void reset() {
        invocations.clear();
        instanceCount = 0;
    }
}
