package io.github.JiangHu.jframe.async.flow;

import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowContext} 虚拟线程模式的单元测试。
 * <p>
 * 通过 {@link TestableThreadFlow} 覆盖主线程调度为同步执行，
 * 避免依赖真实 Nukkit 服务器。异步步骤使用真实 {@link ThreadAPI}。
 *
 * @see FlowContext
 * @see ThreadFlow#virtual(java.util.function.Consumer)
 */
@DisplayName("FlowContext 虚拟线程模式")
class FlowContextLogicTest {

    private TestableThreadFlow flow;

    @AfterEach
    void tearDown() {
        if (flow != null) {
            flow.getThreadAPI().stopAll();
        }
    }

    // ==================== awaitAsync ====================

    @Nested
    @DisplayName("awaitAsync 异步等待")
    class AwaitAsyncTest {

        @Test
        @DisplayName("awaitAsync 返回任务结果")
        void awaitAsyncReturnsResult() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                String data = ctx.awaitAsync("io", () -> "loaded-data");
                result.set(data);
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("loaded-data", result.get());
        }

        @Test
        @DisplayName("awaitAsync 在异步线程执行")
        void awaitAsyncRunsOnAsyncThread() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                threadName.set(ctx.awaitAsync("worker", () ->
                        Thread.currentThread().getName()));
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotNull(threadName.get());
            assertTrue(threadName.get().startsWith("jframe-thread-worker-"),
                    "应在异步线程执行，实际: " + threadName.get());
        }

        @Test
        @DisplayName("awaitAsync(Runnable) 无返回值版本")
        void awaitAsyncRunnable() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                ctx.awaitAsync("void-q", () -> executed.set(true));
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(executed.get());
        }

        @Test
        @DisplayName("队列不存在时自动创建")
        void awaitAsyncAutoCreateQueue() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                result.set(ctx.awaitAsync("auto-q", () -> "ok"));
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("ok", result.get());
            assertNotNull(flow.getThreadAPI().getThreadExecutor("auto-q"),
                    "队列应已被自动创建");
        }
    }

    // ==================== awaitMain ====================

    @Nested
    @DisplayName("awaitMain 主线程等待")
    class AwaitMainTest {

        @Test
        @DisplayName("awaitMain 通过 scheduleOnMain 调度")
        void awaitMainScheduled() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                result.set(ctx.awaitMain(() -> "main-result"));
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("main-result", result.get());
            assertTrue(flow.scheduleOnMainCount.get() > 0,
                    "scheduleOnMain 应被调用");
        }

        @Test
        @DisplayName("awaitMain(Runnable) 无返回值版本")
        void awaitMainRunnable() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                ctx.awaitMain(() -> executed.set(true));
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(executed.get());
        }

        @Test
        @DisplayName("已在主线程时 awaitMain 直接同步执行（防死锁）")
        void awaitMainOnMainThreadDirectExecution() {
            // 使用 isPrimaryThread 返回 true 的 flow
            TestableThreadFlow mainThreadFlow = new TestableThreadFlow() {
                @Override
                protected boolean isPrimaryThread() {
                    return true;
                }
            };
            flow = mainThreadFlow;

            AtomicBoolean scheduleCalled = new AtomicBoolean(false);
            int countBefore = mainThreadFlow.scheduleOnMainCount.get();

            // 直接创建 FlowContext 并调用 awaitMain（不通过 virtual）
            FlowContext ctx = new FlowContext(mainThreadFlow);
            String result = ctx.awaitMain(() -> {
                scheduleCalled.set(true);
                return "direct";
            });

            assertEquals("direct", result);
            assertTrue(scheduleCalled.get(), "任务应直接执行");
            assertEquals(countBefore, mainThreadFlow.scheduleOnMainCount.get(),
                    "在主线程时不应调用 scheduleOnMain");
        }
    }

    // ==================== 多步串联 ====================

    @Nested
    @DisplayName("多步串联")
    class MultiStepTest {

        @Test
        @DisplayName("awaitAsync → awaitMain → awaitAsync 串联")
        void sequentialAwaits() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                // 异步线程：加载数据
                int data = ctx.awaitAsync("load", () -> 100);

                // 主线程：处理数据
                int processed = ctx.awaitMain(() -> data + 50);

                // 异步线程：保存结果
                ctx.awaitAsync("save", () -> {
                    result.set("saved-" + processed);
                    return null;
                });

                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("saved-150", result.get());
        }

        @Test
        @DisplayName("局部变量在 await 间自然传递")
        void localVariablesPassedBetweenAwaits() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                String step1 = ctx.awaitAsync("q1", () -> "A");
                String step2 = ctx.awaitAsync("q2", () -> step1 + "B");
                String step3 = ctx.awaitMain(() -> step2 + "C");
                result.set(step3);
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("ABC", result.get());
        }
    }

    // ==================== 异常处理 ====================

    @Nested
    @DisplayName("异常处理")
    class ExceptionTest {

        @Test
        @DisplayName("awaitAsync 中抛异常，传播为 CompletionException")
        void awaitAsyncExceptionPropagates() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                try {
                    ctx.awaitAsync("err-q", () -> {
                        throw new IllegalStateException("async-boom");
                    });
                } catch (CompletionException e) {
                    caught.set(e.getCause());
                    done.countDown();
                }
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotNull(caught.get());
            assertInstanceOf(IllegalStateException.class, caught.get());
            assertEquals("async-boom", caught.get().getMessage());
        }

        @Test
        @DisplayName("awaitMain 中抛异常，传播为 CompletionException")
        void awaitMainExceptionPropagates() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                try {
                    ctx.awaitMain(() -> {
                        throw new IllegalArgumentException("main-boom");
                    });
                } catch (CompletionException e) {
                    caught.set(e.getCause());
                    done.countDown();
                }
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotNull(caught.get());
            assertInstanceOf(IllegalArgumentException.class, caught.get());
            assertEquals("main-boom", caught.get().getMessage());
        }

        @Test
        @DisplayName("try/catch 统一处理多步异常")
        void unifiedExceptionHandling() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                try {
                    ctx.awaitAsync("q1", () -> "data");
                    ctx.awaitMain(() -> {
                        throw new RuntimeException("fail");
                    });
                    ctx.awaitAsync("q2", () -> "should-not-run");
                } catch (CompletionException e) {
                    result.set("caught: " + e.getCause().getMessage());
                    done.countDown();
                }
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("caught: fail", result.get());
        }
    }

    // ==================== fire 方法 ====================

    @Nested
    @DisplayName("fire 方法（非等待型）")
    class FireTest {

        @Test
        @DisplayName("fireAsync 提交到异步队列")
        void fireAsyncSubmits() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                ctx.fireAsync("fire-q", () -> {
                    executed.set(true);
                    done.countDown();
                });
            });

            assertTrue(done.await(3, TimeUnit.SECONDS), "fireAsync 任务应执行");
            assertTrue(executed.get());
        }

        @Test
        @DisplayName("fireMain 提交到主线程")
        void fireMainSubmits() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                ctx.fireMain(() -> {
                    executed.set(true);
                    done.countDown();
                });
            });

            assertTrue(done.await(3, TimeUnit.SECONDS), "fireMain 任务应执行");
            assertTrue(executed.get());
            assertTrue(flow.fireMainCount.get() > 0,
                    "fireMain 应被调用");
        }
    }

    // ==================== isMainThread ====================

    @Nested
    @DisplayName("线程检测")
    class ThreadDetectionTest {

        @Test
        @DisplayName("虚拟线程中 isMainThread 返回 false")
        void isMainThreadInVirtualThread() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Boolean> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.virtual(ctx -> {
                result.set(ctx.isMainThread());
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(result.get(), "虚拟线程中应返回 false");
        }
    }

    // ==================== 测试辅助（与 ThreadFlowLogicTest 共享结构） ====================

    /**
     * 可测试的 ThreadFlow，覆盖主线程调度为同步执行。
     */
    static class TestableThreadFlow extends ThreadFlow {

        final java.util.concurrent.atomic.AtomicInteger scheduleOnMainCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger fireMainCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        TestableThreadFlow() {
            super(new ThreadAPI());
            super.bindPlugin(createMockPlugin());
        }

        ThreadAPI getThreadAPI() {
            return super.getThreadAPI();
        }

        @Override
        java.util.concurrent.ExecutorService getExecutor(String queue) {
            // 测试便利：自动创建队列，避免每个测试手动 createThreadTask
            getThreadAPI().createThreadTask(queue);
            return getThreadAPI().getThreadExecutor(queue);
        }

        @Override
        protected <T> CompletableFuture<T> scheduleOnMain(Supplier<T> task) {
            scheduleOnMainCount.incrementAndGet();
            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                future.complete(task.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        @Override
        protected void fireMain(Runnable runnable) {
            fireMainCount.incrementAndGet();
            runnable.run();
        }

        @Override
        protected boolean isPrimaryThread() {
            return false;
        }

        @SuppressWarnings("unchecked")
        private static cn.nukkit.plugin.Plugin createMockPlugin() {
            return (cn.nukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                    cn.nukkit.plugin.Plugin.class.getClassLoader(),
                    new Class<?>[]{cn.nukkit.plugin.Plugin.class},
                    (proxy, method, args) -> {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType.isPrimitive()) return 0;
                        return null;
                    });
        }
    }
}
