package io.github.JiangHu.jframe.event.test.fixture;

import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨优先级独占测试：HIGH 处理器声明 {@code exclusive = true}。
 * <p>
 * 验证点：HIGH 执行后声明独占，NORMAL / LOW 不再执行。
 * 记录顺序应仅含 {@code ["HIGH-EXCL"]}。
 */
public class ExclusiveWrapper {

    private static final List<String> ORDER = new ArrayList<>();

    private final String name;

    public ExclusiveWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(MoveEvent event) {
        return event.getPlayerName();
    }

    @EventRoute
    @EventHandler(priority = EventPriority.HIGH, exclusive = true)
    public void onHighExclusive(MoveEvent event) {
        ORDER.add("HIGH-EXCL");
    }

    @EventRoute
    @EventHandler(priority = EventPriority.NORMAL)
    public void onNormal(MoveEvent event) {
        ORDER.add("NORMAL");
    }

    @EventRoute
    @EventHandler(priority = EventPriority.LOW)
    public void onLow(MoveEvent event) {
        ORDER.add("LOW");
    }

    public static List<String> getOrder() {
        return ORDER;
    }

    public static void reset() {
        ORDER.clear();
    }
}
