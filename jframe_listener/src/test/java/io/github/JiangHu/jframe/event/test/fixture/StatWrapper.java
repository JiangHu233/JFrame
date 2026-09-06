package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略1：默认缓存（无 @InstanceProvider）。
 * <p>
 * 验证点：
 * <ul>
 *   <li>基础事件分发：事件到达 → @KeyExtractor 提取身份 → 构造实例 → @EventHandler 执行</li>
 *   <li>实例复用：同一身份多次事件复用同一实例（构造次数 < 事件次数）</li>
 *   <li>evict 驱逐：驱逐后下次事件创建新实例</li>
 *   <li>事件类型自动推断（@EventRoute 未指定 value，从方法参数推断 MoveEvent）</li>
 * </ul>
 */
public class StatWrapper {

    /** 记录所有处理器调用（playerName@x） */
    private static final List<String> INVOCATIONS = new ArrayList<>();

    /** 记录所有被创建的实例（用于断言构造次数 / 实例复用） */
    private static final List<StatWrapper> CREATED = new ArrayList<>();

    private final String playerName;

    public StatWrapper(String playerName) {
        this.playerName = playerName;
        CREATED.add(this);
    }

    /** 身份提取器：从 MoveEvent 提取玩家名（String 身份） */
    @KeyExtractor
    public static String extract(MoveEvent event) {
        return event.getPlayerName();
    }

    /** 处理器：自动推断事件类型为 MoveEvent */
    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        INVOCATIONS.add(playerName + "@" + event.getX());
    }

    public static List<String> getInvocations() {
        return INVOCATIONS;
    }

    public static List<StatWrapper> getCreated() {
        return CREATED;
    }

    public String getPlayerName() {
        return playerName;
    }

    public static void reset() {
        INVOCATIONS.clear();
        CREATED.clear();
    }
}
