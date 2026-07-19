package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;

/**
 * 错误用例：{@code @EventRoute} 的 condition 与 filter 同时指定（互斥）。
 * <p>
 * 验证点：注册时 {@link io.github.JiangHu.jframe.event.routing.HandlerTemplate} 构造器
 * 抛出 {@link IllegalArgumentException}（condition 和 filter 不能同时使用）。
 * <p>
 * 注意：本类<b>故意同时</b>指定 condition 和 filter。
 */
public class MutualExclusionWrapper {

    public MutualExclusionWrapper(String name) {
    }

    @KeyExtractor
    public static String extract(ChatEvent event) {
        return event.getPlayerName();
    }

    @EventRoute(condition = "#event.message.length() > 0", filter = "alwaysTrue")
    @EventHandler
    public void onChat(ChatEvent event) {
    }

    @SuppressWarnings("unused")
    private boolean alwaysTrue(ChatEvent event) {
        return true;
    }
}
