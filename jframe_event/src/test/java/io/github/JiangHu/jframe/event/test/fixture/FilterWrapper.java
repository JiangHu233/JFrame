package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * filter 方法引用测试：{@code @EventRoute(filter = "isLongMessage")}。
 * <p>
 * 验证点：
 * <ul>
 *   <li>filter 方法（同类 boolean 签名）返回 true 时处理器执行</li>
 *   <li>返回 false 时处理器不执行</li>
 *   <li>filter 可以是 private 方法</li>
 * </ul>
 */
public class FilterWrapper {

    private static final List<String> MATCHED = new ArrayList<>();

    private final String name;

    public FilterWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(ChatEvent event) {
        return event.getPlayerName();
    }

    @EventRoute(filter = "isLongMessage")
    @EventHandler
    public void onLong(ChatEvent event) {
        MATCHED.add(name + ":" + event.getMessage());
    }

    /** 筛选方法：消息长度 > 5 才放行。private 方法亦可被框架反射调用。 */
    private boolean isLongMessage(ChatEvent event) {
        return event.getMessage().length() > 5;
    }

    public static List<String> getMatched() {
        return MATCHED;
    }

    public static void reset() {
        MATCHED.clear();
    }
}
