package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.annotation.Wrapper;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 包扫描目标：标注 {@link Wrapper @Wrapper}，用于测试 {@code EventAPI.scan(...)} 自动发现。
 * <p>
 * 验证点：扫描器能在指定包下发现本类并自动注册，注册后事件可正常分发。
 */
@Wrapper
public class ScanTargetWrapper {

    private static final List<String> INVOCATIONS = new ArrayList<>();

    private final String name;

    public ScanTargetWrapper(String name) {
        this.name = name;
    }

    @KeyExtractor
    public static String extract(MoveEvent event) {
        return event.getPlayerName();
    }

    @EventRoute
    @EventHandler
    public void onMove(MoveEvent event) {
        INVOCATIONS.add(name + "@scan");
    }

    public static List<String> getInvocations() {
        return INVOCATIONS;
    }

    public static void reset() {
        INVOCATIONS.clear();
    }
}
