package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>静态</b> filter 方法引用测试：{@code @EventRoute(filter = "isLongMessageStatic")}。
 * <p>
 * 与 {@link FilterWrapper} 的区别在于筛选方法是 {@code static}。这是回归用例：
 * 修复前 {@code HandlerTemplate} 对 static filter 仍以
 * {@code filterHandle.invoke(target, event)}（两个参数）调用，
 * 而 static 方法的 MethodHandle 类型为 {@code (ChatEvent)boolean}（仅一个参数），
 * 会抛 {@code WrongMethodTypeException} 并被吞掉，导致处理器永不执行。
 *
 * <ul>
 *   <li>验证点：static filter 返回 true 时处理器执行</li>
 *   <li>验证点：static filter 返回 false 时处理器不执行</li>
 * </ul>
 */
public class StaticFilterWrapper {

    private static final List<String> MATCHED = new ArrayList<>();

    private final String name;

    public StaticFilterWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(ChatEvent event) {
        return event.getPlayerName();
    }

    @EventRoute(filter = "isLongMessageStatic")
    @EventHandler
    public void onLong(ChatEvent event) {
        MATCHED.add(name + ":" + event.getMessage());
    }

    /** 静态筛选方法：消息长度 > 5 才放行。 */
    public static boolean isLongMessageStatic(ChatEvent event) {
        return event.getMessage().length() > 5;
    }

    public static List<String> getMatched() {
        return MATCHED;
    }

    public static void reset() {
        MATCHED.clear();
    }
}
