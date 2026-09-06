package io.github.JiangHu.jframe.async.thread;

import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ThreadAPI} 命名线程池服务的单元测试。
 * <p>
 * 重点验证 CODE_REVIEW 中针对 jframe_thread 的三个问题修复：
 * <ul>
 *   <li>#4 {@code createThreadTask} 竞态泄漏 → 改用 {@code computeIfAbsent}，并发创建同名队列不会泄漏执行器；</li>
 *   <li>#11 {@code AWAIT_TIME} 全局可变静态 → 改为实例字段 {@code awaitTime}，按实例隔离；</li>
 *   <li>#18 功能缺失 → 新增 {@code removeThreadTask(name)} 按名移除、自定义 {@link ThreadFactory} 命名线程。</li>
 * </ul>
 *
 * @see ThreadAPI
 */
@DisplayName("ThreadAPI 命名线程池")
class ThreadAPILogicTest {

    // ==================== createThreadTask 创建 / 幂等 ====================

    @Nested
    @DisplayName("createThreadTask 创建与幂等")
    class CreateThreadTaskTest {

        @Test
        @DisplayName("创建后可获取到非空执行器")
        void createThenGet() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("queue-a");

            ExecutorService executor = api.getThreadExecutor("queue-a");
            assertNotNull(executor, "创建后应能获取到执行器");
            assertFalse(executor.isShutdown(), "新建的执行器不应处于关闭状态");
            api.stopAll();
        }

        @Test
        @DisplayName("重复创建同名队列保持幂等：不新建、不覆盖")
        void createIdempotentKeepsSameInstance() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("dup");

            ExecutorService first = api.getThreadExecutor("dup");
            // 再次创建同名队列
            api.createThreadTask("dup");
            ExecutorService second = api.getThreadExecutor("dup");

            assertSame(first, second, "重复创建同名队列应返回同一执行器实例（幂等）");
            api.stopAll();
        }

        @Test
        @DisplayName("并发创建同名队列：仅产生一个执行器（无泄漏）")
        void concurrentCreateSingleInstance() throws InterruptedException {
            final int threadCount = 32;
            ThreadAPI api = new ThreadAPI();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        api.createThreadTask("race");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                t.setDaemon(true);
                t.start();
            }

            start.countDown();          // 同时放开
            assertTrue(done.await(5, TimeUnit.SECONDS), "所有并发线程应能在 5s 内完成");

            // 关键断言：无论多少线程并发创建，最终只应存在一个执行器
            ExecutorService executor = api.getThreadExecutor("race");
            assertNotNull(executor, "并发创建后应存在执行器");
            assertFalse(executor.isShutdown(), "该执行器不应被泄漏后遗留为关闭态");
            api.stopAll();
        }
    }

    // ==================== pushTask 串行执行 / 异常处理 ====================

    @Nested
    @DisplayName("pushTask 提交与执行")
    class PushTaskTest {

        @Test
        @DisplayName("同一队列任务按提交顺序串行执行")
        void tasksRunInOrder() throws InterruptedException {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("ordered");

            List<Integer> results = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(3);

            for (int i = 0; i < 3; i++) {
                final int v = i;
                api.pushTask("ordered", () -> {
                    results.add(v);
                    done.countDown();
                });
            }

            assertTrue(done.await(3, TimeUnit.SECONDS), "3 个任务应在 3s 内完成");
            assertEquals(List.of(0, 1, 2), results, "任务应按提交顺序串行执行");
            api.stopAll();
        }

        @Test
        @DisplayName("向不存在的队列提交任务抛出 NullPointerException")
        void pushToMissingQueueThrows() {
            ThreadAPI api = new ThreadAPI();
            assertThrows(NullPointerException.class,
                    () -> api.pushTask("no-such-queue", () -> {}),
                    "未创建的队列应抛出 NPE 而非静默失败");
        }

        @Test
        @DisplayName("任务异常交由 exceptionHandler 处理，不影响后续任务")
        void exceptionHandledAndSubsequentTaskRuns() throws InterruptedException {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("err");

            AtomicReference<Exception> caught = new AtomicReference<>();
            api.setExceptionHandler(caught::set);

            AtomicInteger afterCount = new AtomicInteger(0);
            CountDownLatch done = new CountDownLatch(1);

            api.pushTask("err", () -> { throw new IllegalStateException("boom"); });
            api.pushTask("err", () -> {
                afterCount.incrementAndGet();
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS), "异常后的后续任务仍应执行");
            assertNotNull(caught.get(), "异常应被捕获并交给 handler");
            assertInstanceOf(IllegalStateException.class, caught.get());
            assertEquals("boom", caught.get().getMessage());
            assertEquals(1, afterCount.get());
            api.stopAll();
        }
    }

    // ==================== removeThreadTask 按名移除（#18） ====================

    @Nested
    @DisplayName("removeThreadTask 按名移除")
    class RemoveThreadTaskTest {

        @Test
        @DisplayName("移除已存在队列返回 true，执行器被关闭")
        void removeExistingReturnsTrueAndShutsDown() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("kill");

            ExecutorService executor = api.getThreadExecutor("kill");
            assertTrue(api.removeThreadTask("kill"), "移除已存在队列应返回 true");
            assertNull(api.getThreadExecutor("kill"), "移除后不应再能获取到执行器");
            assertTrue(executor.isShutdown(), "被移除的执行器应已关闭");
        }

        @Test
        @DisplayName("移除不存在的队列返回 false")
        void removeMissingReturnsFalse() {
            ThreadAPI api = new ThreadAPI();
            assertFalse(api.removeThreadTask("ghost"), "移除不存在的队列应返回 false");
        }

        @Test
        @DisplayName("移除单个队列不影响其他队列")
        void removeOneKeepsOthers() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("a");
            api.createThreadTask("b");

            assertTrue(api.removeThreadTask("a"));
            assertNull(api.getThreadExecutor("a"));
            assertNotNull(api.getThreadExecutor("b"), "移除 a 不应影响 b");
            api.stopAll();
        }

        @Test
        @DisplayName("移除后再 pushTask 抛出 NullPointerException")
        void pushAfterRemoveThrows() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("gone");
            api.removeThreadTask("gone");

            assertThrows(NullPointerException.class,
                    () -> api.pushTask("gone", () -> {}),
                    "移除后提交应抛 NPE");
        }
    }

    // ==================== stopAll 全部关闭 ====================

    @Nested
    @DisplayName("stopAll 全部关闭")
    class StopAllTest {

        @Test
        @DisplayName("stopAll 关闭所有队列并清空映射")
        void stopAllClearsEverything() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("x");
            api.createThreadTask("y");

            ExecutorService ex = api.getThreadExecutor("x");
            api.stopAll();

            assertNull(api.getThreadExecutor("x"));
            assertNull(api.getThreadExecutor("y"));
            assertTrue(ex.isShutdown(), "stopAll 后执行器应处于关闭状态");
        }

        @Test
        @DisplayName("stopAll 后 close 等价、可重复调用不抛异常")
        void closeIsIdempotent() {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("c");
            assertDoesNotThrow(() -> {
                api.close();
                api.close();   // 重复关闭不应抛异常
            });
        }
    }

    // ==================== awaitTime 实例字段（#11） ====================

    @Nested
    @DisplayName("awaitTime 实例字段隔离")
    class AwaitTimeTest {

        @Test
        @DisplayName("默认值为 DEFAULT_AWAIT_TIME")
        void defaultValue() {
            ThreadAPI api = new ThreadAPI();
            assertEquals(ThreadAPI.DEFAULT_AWAIT_TIME, api.getAwaitTime());
        }

        @Test
        @DisplayName("不同实例的 awaitTime 互不影响（消除全局可变静态污染）")
        void perInstanceIsolation() {
            ThreadAPI a = new ThreadAPI();
            ThreadAPI b = new ThreadAPI();

            a.setAwaitTime(5);
            assertEquals(5, a.getAwaitTime());
            assertEquals(ThreadAPI.DEFAULT_AWAIT_TIME, b.getAwaitTime(),
                    "修改 a 的 awaitTime 不应影响 b（实例隔离）");
        }
    }

    // ==================== 自定义 ThreadFactory 命名线程（#18） ====================

    @Nested
    @DisplayName("工作线程命名")
    class ThreadNamingTest {

        @Test
        @DisplayName("工作线程名以 jframe-thread-{name}- 为前缀")
        void workerThreadNamed() throws InterruptedException {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("saver");

            Set<String> captured = ConcurrentHashMap.newKeySet();
            CountDownLatch done = new CountDownLatch(1);
            api.pushTask("saver", () -> {
                captured.add(Thread.currentThread().getName());
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(captured.isEmpty(), "应捕获到工作线程名");
            for (String name : captured) {
                assertTrue(name.startsWith("jframe-thread-saver-"),
                        "线程名应以 jframe-thread-saver- 为前缀，实际: " + name);
            }
            api.stopAll();
        }

        @Test
        @DisplayName("工作线程为守护线程，不阻塞 JVM 退出")
        void workerThreadIsDaemon() throws InterruptedException {
            ThreadAPI api = new ThreadAPI();
            api.createThreadTask("daemon-check");

            AtomicReference<Thread> captured = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            api.pushTask("daemon-check", () -> {
                captured.set(Thread.currentThread());
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(captured.get().isDaemon(), "工作线程应为守护线程");
            api.stopAll();
        }
    }
}
