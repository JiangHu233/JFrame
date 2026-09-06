package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * SpEL 条件测试：{@code @EventRoute(condition = "#event.message.contains('spam')")}。
 * <p>
 * 验证点：
 * <ul>
 *   <li>消息包含 "spam" 时处理器执行</li>
 *   <li>消息不含 "spam" 时处理器不执行（条件求值为 false）</li>
 * </ul>
 */
public class ConditionWrapper {

    private static final List<String> MATCHED = new ArrayList<>();

    private final String name;

    public ConditionWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(ChatEvent event) {
        return event.getPlayerName();
    }

    @EventRoute(condition = "#event.message.contains('spam')")
    @EventHandler
    public void onSpam(ChatEvent event) {
        MATCHED.add(name + ":" + event.getMessage());
    }

    public static List<String> getMatched() {
        return MATCHED;
    }

    public static void reset() {
        MATCHED.clear();
    }
}
