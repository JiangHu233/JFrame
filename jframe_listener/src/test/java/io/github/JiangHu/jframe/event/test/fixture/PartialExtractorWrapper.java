package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

/**
 * 部分缺失 @KeyExtractor 测试（OBJECT 模式静默失败检测）。
 * <p>
 * 本类声明了 {@link MoveEvent} 的 @KeyExtractor，因此整体走 OBJECT 模式。
 * 但额外有一个处理 {@link ChatEvent} 的 @EventHandler 却<b>没有</b>对应的 @KeyExtractor。
 * <p>
 * 验证点：
 * <ul>
 *   <li>注册时框架打 WARNING 日志，提示 ChatEvent 缺少 @KeyExtractor</li>
 *   <li>dispatch MoveEvent → onMove 正常执行（有 extractor）</li>
 *   <li>dispatch ChatEvent → onChat 静默跳过（无 extractor，无法路由）</li>
 * </ul>
 */
public class PartialExtractorWrapper {

    private static boolean moveInvoked = false;
    private static boolean chatInvoked = false;

    private final String identity;

    public PartialExtractorWrapper(String identity) {
        this.identity = identity;
    }

    /** 仅为 MoveEvent 提供身份提取器 */
    @KeyExtractor
    public static String extractMove(MoveEvent event) {
        return event.getPlayerName();
    }

    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        moveInvoked = true;
    }

    /**
     * 故意不为 ChatEvent 提供 @KeyExtractor。
     * 注册时触发 WARNING，dispatch 时该 handler 静默跳过（不执行）。
     */
    @EventRoute
    @EventHandler
    public void onChat(ChatEvent event) {
        chatInvoked = true;
    }

    public static boolean isMoveInvoked() {
        return moveInvoked;
    }

    public static boolean isChatInvoked() {
        return chatInvoked;
    }

    public static void reset() {
        moveInvoked = false;
        chatInvoked = false;
    }
}
