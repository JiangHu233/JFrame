package io.github.JiangHu.jframe.event.test.fixture;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享执行顺序记录器：用于验证 STATIC 兜底顺序。
 * <p>
 * OBJECT/SINGLETON（primary）和 STATIC（tail）都往此记录，
 * 测试通过检查列表顺序验证 primary 先于 tail 执行。
 */
public final class ExecutionOrderTracker {

    private static final List<String> order = new ArrayList<>();

    public static void record(String tag) {
        order.add(tag);
    }

    public static List<String> getOrder() {
        return order;
    }

    public static void reset() {
        order.clear();
    }

    private ExecutionOrderTracker() {
    }
}
