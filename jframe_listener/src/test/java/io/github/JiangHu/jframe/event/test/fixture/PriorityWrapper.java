package io.github.JiangHu.jframe.event.test.fixture;

import cn.nukkit.event.EventPriority;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 优先级排序测试：同一实例上声明 HIGH / NORMAL / LOW 三个处理器（均非独占）。
 * <p>
 * 验证点：框架按 HIGHEST → LOWEST 降序分发，记录顺序应为 HIGH → NORMAL → LOW。
 */
public class PriorityWrapper {

    private static final List<String> ORDER = new ArrayList<>();

    private final String name;

    public PriorityWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(MoveEvent event) {
        return event.getPlayerName();
    }

    @EventRoute
    @EventHandler(priority = EventPriority.HIGH)
    public void onHigh(MoveEvent event) {
        ORDER.add("HIGH");
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
