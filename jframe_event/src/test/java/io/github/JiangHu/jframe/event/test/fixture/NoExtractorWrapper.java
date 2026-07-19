package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

/**
 * 错误用例：缺少 @KeyExtractor。
 * <p>
 * 验证点：无身份提取器的类注册时被拒绝（{@code register} 内部记录警告并返回），
 * 不会抛出异常，也不会注册任何处理器。
 * <p>
 * 注意：本类<b>故意不声明</b> {@code @KeyExtractor}。
 */
public class NoExtractorWrapper {

    private static boolean invoked = false;

    public NoExtractorWrapper(String name) {
    }

    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        invoked = true;
    }

    public static boolean isInvoked() {
        return invoked;
    }

    public static void reset() {
        invoked = false;
    }
}
