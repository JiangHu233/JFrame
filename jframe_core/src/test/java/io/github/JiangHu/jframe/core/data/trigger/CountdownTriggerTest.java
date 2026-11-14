package io.github.JiangHu.jframe.core.data.trigger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 倒计时触发器（{@link CountdownTrigger}）逻辑测试（纯 JVM 可运行，无外部依赖）。
 *
 * <p>运行方式：直接执行 {@link #main}。
 *
 * <p>覆盖特性一览：
 * <ol>
 *   <li>基础计数：tick 递减、归零触发、返回值正确性</li>
 *   <li>参数校验：count < 1 / action 为 null / mode 为 null</li>
 *   <li>边界 count=1：首次 tick 即触发</li>
 *   <li>RESETTABLE 模式：触发后失效，后续 tick 不计数</li>
 *   <li>reset 复活：恢复计数、清除取消状态、链式返回</li>
 *   <li>RECURRING 模式：自动循环、多轮触发、triggerCount 累计</li>
 *   <li>cancel：取消后永不触发、幂等、reset 可复活</li>
 *   <li>异常隔离：action 抛异常不影响计数，RECURRING 仍继续下一轮</li>
 *   <li>锁外执行：action 内调用 tick / reset / cancel 不死锁</li>
 *   <li>并发恰好一次：多线程同时 tick，action 恰好执行一次</li>
 *   <li>查询方法：getCount / getRemaining / isTriggered / isExpired / getMode</li>
 * </ol>
 */
public final class CountdownTriggerTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("==================== 开始倒计时触发器测试 ====================");

        testBasicCounting();
        testArgumentValidation();
        testCountOneBoundary();
        testResettableMode();
        testReset();
        testRecurringMode();
        testCancel();
        testExceptionIsolation();
        testActionRunsOutsideLock();
        testConcurrentExactlyOnce();
        testQueries();

        System.out.println("==================== 测试汇总 ====================");
        System.out.println("通过: " + pass + "，失败: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✅");
    }

    // ==================== 1. 基础计数 ====================

    /** 1. 基础计数：tick 递减、归零触发、返回值正确性 */
    private static void testBasicCounting() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(3, fired::incrementAndGet);

        check("1a. 初始剩余 = 设定次数", t.getRemaining() == 3);
        check("1b. 第 1 次 tick 未触发", !t.tick() && fired.get() == 0);
        check("1c. 剩余递减", t.getRemaining() == 2);
        check("1d. 第 2 次 tick 未触发", !t.tick() && fired.get() == 0);
        check("1e. 第 3 次 tick 触发", t.tick() && fired.get() == 1);
    }

    // ==================== 2. 参数校验 ====================

    /** 2. 参数校验：count < 1 / action 为 null / mode 为 null */
    private static void testArgumentValidation() {
        boolean threw = false;
        try {
            CountdownTrigger.of(0, () -> { });
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("2a. count=0 抛 IllegalArgumentException", threw);

        threw = false;
        try {
            CountdownTrigger.of(-5, () -> { });
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("2b. count=-5 抛 IllegalArgumentException", threw);

        threw = false;
        try {
            CountdownTrigger.of(3, null);
        } catch (NullPointerException expected) {
            threw = true;
        }
        check("2c. action=null 抛 NullPointerException", threw);

        threw = false;
        try {
            CountdownTrigger.of(3, () -> { }, null);
        } catch (NullPointerException expected) {
            threw = true;
        }
        check("2d. mode=null 抛 NullPointerException", threw);

        check("2e. count=1 合法", CountdownTrigger.of(1, () -> { }) != null);
    }

    // ==================== 3. 边界 count=1 ====================

    /** 3. 边界 count=1：首次 tick 即触发 */
    private static void testCountOneBoundary() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(1, fired::incrementAndGet);

        check("3a. count=1 首次 tick 即触发", t.tick() && fired.get() == 1);
        check("3b. 触发后失效", t.isExpired() && !t.tick() && fired.get() == 1);
    }

    // ==================== 4. RESETTABLE 模式 ====================

    /** 4. RESETTABLE 模式（默认）：触发后失效，后续 tick 不计数 */
    private static void testResettableMode() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(2, fired::incrementAndGet);

        check("4a. 默认模式为 RESETTABLE", t.getMode() == CountdownTrigger.Mode.RESETTABLE);
        t.tick();
        t.tick();
        check("4b. 触发一次", fired.get() == 1);
        check("4c. 触发后 isExpired", t.isExpired());
        check("4d. 失效后 tick 不触发", !t.tick() && !t.tick());
        check("4e. 失效后不再执行 action", fired.get() == 1);
        check("4f. isTriggered 为 true", t.isTriggered());
    }

    // ==================== 5. reset ====================

    /** 5. reset 复活：恢复计数、清除取消状态、链式返回 */
    private static void testReset() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(2, fired::incrementAndGet);

        t.tick();
        t.tick();
        check("5a. 已触发失效", t.isExpired());

        var returned = t.reset();
        check("5b. reset 返回自身（链式）", returned == t);
        check("5c. reset 后恢复剩余", t.getRemaining() == 2 && !t.isExpired());
        check("5d. reset 后可重新触发", !t.tick() && t.tick() && fired.get() == 2);

        // reset 清除取消状态
        t.cancel();
        check("5e. 已取消", t.isCancelled());
        t.reset();
        check("5f. reset 清除取消状态", !t.isCancelled());
        check("5g. 复活后可触发", !t.tick() && t.tick() && fired.get() == 3);

        // 未触发前 reset：剩余恢复满
        var t2 = CountdownTrigger.of(5, () -> { });
        t2.tick();
        t2.tick();
        t2.reset();
        check("5h. 中途 reset 恢复满计数", t2.getRemaining() == 5);
    }

    // ==================== 6. RECURRING 模式 ====================

    /** 6. RECURRING 模式：自动循环、多轮触发、triggerCount 累计 */
    private static void testRecurringMode() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(3, fired::incrementAndGet, CountdownTrigger.Mode.RECURRING);

        check("6a. 模式为 RECURRING", t.getMode() == CountdownTrigger.Mode.RECURRING);

        int trueCount = 0;
        for (int i = 1; i <= 7; i++) {
            if (t.tick()) {
                trueCount++;
            }
        }
        check("6b. 7 次 tick 触发 2 次（第 3、6 次）", trueCount == 2 && fired.get() == 2);
        check("6c. 触发后自动恢复计数（不失效）", !t.isExpired() && t.getRemaining() == 2);
        check("6d. triggerCount 累计", t.getTriggerCount() == 2);

        t.tick();   // 剩余 2→1
        t.tick();   // 剩余 1→0，触发第 3 轮
        check("6e. 第 3 轮继续触发", fired.get() == 3 && t.getTriggerCount() == 3);

        // RECURRING + reset：中途重置重新开始本轮
        var t2 = CountdownTrigger.of(4, () -> { }, CountdownTrigger.Mode.RECURRING);
        t2.tick();
        t2.tick();
        t2.reset();
        check("6f. RECURRING 中途 reset 恢复满计数", t2.getRemaining() == 4);
    }

    // ==================== 7. cancel ====================

    /** 7. cancel：取消后永不触发、幂等、reset 可复活 */
    private static void testCancel() {
        var fired = new AtomicInteger();
        var t = CountdownTrigger.of(3, fired::incrementAndGet);

        t.tick();
        var returned = t.cancel();
        check("7a. cancel 返回自身（链式）", returned == t);
        check("7b. isCancelled", t.isCancelled());
        check("7c. 取消后 tick 不触发", !t.tick() && !t.tick() && fired.get() == 0);
        check("7d. 取消不算失效", !t.isExpired());
        t.cancel();
        check("7e. cancel 幂等", t.isCancelled() && fired.get() == 0);

        // reset 复活被取消的触发器
        t.reset();
        check("7f. reset 复活", !t.isCancelled() && t.getRemaining() == 3);
        check("7g. 复活后正常触发", !t.tick() && !t.tick() && t.tick() && fired.get() == 1);
    }

    // ==================== 8. 异常隔离 ====================

    /** 8. 异常隔离：action 抛异常不影响计数，RECURRING 仍继续下一轮 */
    private static void testExceptionIsolation() {
        var attempts = new AtomicInteger();
        var t = CountdownTrigger.of(2, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        t.tick();
        boolean triggered = t.tick();   // 触发但 action 抛异常
        check("8a. action 异常时 tick 仍返回 true", triggered);
        check("8b. action 确实执行过", attempts.get() == 1);
        check("8c. 异常后状态一致（RESETTABLE 失效）", t.isExpired() && t.isTriggered());

        // RECURRING：异常不阻断下一轮
        var loop = CountdownTrigger.of(1, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }, CountdownTrigger.Mode.RECURRING);
        loop.tick();
        loop.tick();
        check("8d. RECURRING 异常后仍继续下一轮", attempts.get() == 3 && loop.getTriggerCount() == 2);

        // 自定义 errorHandler
        var handled = new AtomicInteger();
        var t2 = CountdownTrigger.of(1, () -> {
            throw new IllegalArgumentException("custom");
        });
        t2.setErrorHandler(e -> {
            if (e instanceof IllegalArgumentException) {
                handled.incrementAndGet();
            }
        });
        t2.tick();
        check("8e. 自定义 errorHandler 收到异常", handled.get() == 1);
    }

    // ==================== 9. 锁外执行 ====================

    /** 9. 锁外执行：action 内调用 tick / reset / cancel 不死锁 */
    private static void testActionRunsOutsideLock() {
        // lambda 不能引用正在初始化的局部变量，用单元素数组持有自引用
        final CountdownTrigger[] t = new CountdownTrigger[1];
        t[0] = CountdownTrigger.of(1, () -> {
            // action 在锁外执行：此处再进入同步段不应死锁
            t[0].cancel();
        });
        t[0].tick();
        check("9a. action 内 cancel 不死锁且生效", t[0].isCancelled());

        final CountdownTrigger[] r = new CountdownTrigger[1];
        r[0] = CountdownTrigger.of(1, () -> r[0].reset());
        r[0].tick();
        check("9b. action 内 reset 不死锁且生效", r[0].getRemaining() == 1 && !r[0].isExpired());

        final CountdownTrigger[] loop = new CountdownTrigger[1];
        loop[0] = CountdownTrigger.of(1, () -> {
            if (loop[0].getTriggerCount() < 3) {
                loop[0].tick();   // 递归触发下一轮（锁外，安全）
            }
        }, CountdownTrigger.Mode.RECURRING);
        loop[0].tick();
        check("9c. action 内递归 tick 连锁触发", loop[0].getTriggerCount() == 3);
    }

    // ==================== 10. 并发恰好一次 ====================

    /** 10. 并发恰好一次：多线程同时 tick，action 恰好执行一次 */
    private static void testConcurrentExactlyOnce() throws Exception {
        int threads = 16;
        int rounds = 50;
        for (int round = 0; round < rounds; round++) {
            var fired = new AtomicInteger();
            var t = CountdownTrigger.of(threads, fired::incrementAndGet);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                        t.tick();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            done.await();
            check("10-" + round + ". " + threads + " 线程并发 tick 恰好触发一次", fired.get() == 1);
        }
    }

    // ==================== 11. 查询方法 ====================

    /** 11. 查询方法：getCount / getRemaining / isTriggered / isExpired / getMode */
    private static void testQueries() {
        var t = CountdownTrigger.of(4, () -> { }, CountdownTrigger.Mode.RECURRING);

        check("11a. getCount 恒定", t.getCount() == 4);
        check("11b. 初始未触发", !t.isTriggered() && t.getTriggerCount() == 0);
        t.tick();
        t.tick();
        t.tick();
        t.tick();   // 第 4 次触发
        check("11c. 触发后 isTriggered", t.isTriggered());
        check("11d. RECURRING 触发后不失效、计数恢复", !t.isExpired() && t.getRemaining() == 4);
        check("11e. getMode 正确", t.getMode() == CountdownTrigger.Mode.RECURRING);
        check("11f. toString 包含关键状态", t.toString().contains("RECURRING"));
    }

    // ==================== 断言工具 ====================

    private static void check(String label, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  ✔ " + label);
        } else {
            fail++;
            System.out.println("  ✘ " + label);
        }
    }
}
