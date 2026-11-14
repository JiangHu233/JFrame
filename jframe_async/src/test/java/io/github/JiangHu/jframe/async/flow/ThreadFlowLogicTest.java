package io.github.JiangHu.jframe.async.flow;

import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ThreadFlow} 回调链（{@link FlowChain}）模式的单元测试。
 * <p>
 * 通过 {@link TestableThreadFlow} 覆盖 {@code scheduleOnMain} 为同步执行，
 * 避免依赖真实 Nukkit 服务器。异步步骤使用真实 {@link ThreadAPI}。
 *
 * @see ThreadFlow
 * @see FlowChain
 */
@DisplayName("ThreadFlow 回调链模式")
class ThreadFlowLogicTest {

    /** 测试用的 ThreadFlow，主线程步骤同步执行 */
    private TestableThreadFlow flow;

    @AfterEach
    void tearDown() {
        if (flow != null) {
            flow.getThreadAPI().stopAll();
        }
    }

    // ==================== 基本链式执行 ====================

    @Nested
    @DisplayName("基本链式执行")
    class BasicChainTest {

        @Test
        @DisplayName("async 步骤在异步线程执行")
        void asyncRunsOnAsyncThread() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("test-queue", () -> {
                        threadName.set(Thread.currentThread().getName());
                        return null;
                    })
                    .onComplete(done::countDown)
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS), "链应在 3s 内完成");
            assertNotNull(threadName.get());
            assertTrue(threadName.get().startsWith("jframe-thread-test-queue-"),
                    "应在异步线程执行，实际线程: " + threadName.get());
        }

        @Test
        @DisplayName("async + thenAsync 数据正确传递")
        void asyncThenAsyncDataPassing() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> "hello")
                    .thenAsync("q2", s -> s + " world")
                    .onSuccess(s -> {
                        result.set(s);
                        done.countDown();
                    })
                    .onError(e -> done.countDown())
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("hello world", result.get());
        }

        @Test
        @DisplayName("Void 变体 async(queue, Runnable) 正确工作")
        void asyncRunnableVariant() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("void-q", () -> executed.set(true))
                    .onComplete(done::countDown)
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(executed.get(), "Runnable 任务应已执行");
        }
    }

    // ==================== 主线程步骤 ====================

    @Nested
    @DisplayName("主线程步骤")
    class MainStepTest {

        @Test
        @DisplayName("thenMain 通过 scheduleOnMain 调度")
        void thenMainScheduledOnMain() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> "data")
                    .thenMain(s -> {
                        flow.assertScheduleOnMainCalled();
                        return s.toUpperCase();
                    })
                    .onSuccess(s -> {
                        result.set(s);
                        done.countDown();
                    })
                    .onError(e -> done.countDown())
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("DATA", result.get());
            assertTrue(flow.scheduleOnMainCount.get() > 0,
                    "scheduleOnMain 应被调用");
        }

        @Test
        @DisplayName("main(Supplier) 忽略前值，产出新值")
        void mainSupplierIgnoresPrevious() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Integer> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> "ignored")
                    .main(() -> 42)
                    .onSuccess(s -> {
                        result.set(s);
                        done.countDown();
                    })
                    .onError(e -> done.countDown())
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(42, result.get());
        }

        @Test
        @DisplayName("main(Runnable) 无返回值")
        void mainRunnableNoReturn() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean executed = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .main(() -> executed.set(true))
                    .onComplete(done::countDown)
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(executed.get());
        }
    }

    // ==================== 多步链与线程切换 ====================

    @Nested
    @DisplayName("多步链与线程切换")
    class MultiStepTest {

        @Test
        @DisplayName("3 步链：async → thenMain → thenAsync")
        void threeStepChain() throws InterruptedException {
            flow = new TestableThreadFlow();

            List<String> steps = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> {
                        steps.add("async");
                        return 1;
                    })
                    .thenMain(v -> {
                        steps.add("main:" + v);
                        return v + 1;
                    })
                    .thenAsync("q2", v -> {
                        steps.add("thenAsync:" + v);
                        return null;
                    })
                    .onComplete(done::countDown)
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(3, steps.size(), "应执行 3 个步骤");
            assertEquals("async", steps.get(0));
            assertEquals("main:1", steps.get(1));
            assertEquals("thenAsync:2", steps.get(2));
        }

        @Test
        @DisplayName("引用不存在的队列时抛出 IllegalArgumentException")
        void queueNotFoundThrows() {
            // 使用未覆盖 getExecutor 的原始 ThreadFlow 测试严格行为
            ThreadAPI api = new ThreadAPI();
            ThreadFlow strictFlow = new ThreadFlow(api);

            assertThrows(IllegalArgumentException.class,
                    () -> strictFlow.getExecutor("nonexistent"),
                    "引用不存在的队列应抛出异常");

            api.stopAll();
        }
    }

    // ==================== 异常处理 ====================

    @Nested
    @DisplayName("异常处理")
    class ExceptionTest {

        @Test
        @DisplayName("async 步骤抛异常，onError 收到解包后的原始异常")
        void asyncExceptionPropagatedToOnError() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("err-q", () -> {
                        throw new IllegalStateException("boom");
                    })
                    .onError(e -> {
                        caught.set(e);
                        done.countDown();
                    })
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotNull(caught.get(), "onError 应收到异常");
            assertInstanceOf(IllegalStateException.class, caught.get(),
                    "异常应被解包为原始类型");
            assertEquals("boom", caught.get().getMessage());
        }

        @Test
        @DisplayName("异常后后续步骤不执行")
        void subsequentStepSkippedAfterError() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean step2Executed = new AtomicBoolean(false);
            AtomicReference<Throwable> caught = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> {
                        throw new RuntimeException("fail");
                    })
                    .thenAsync("q2", v -> {
                        step2Executed.set(true);
                        return null;
                    })
                    .onError(e -> {
                        caught.set(e);
                        done.countDown();
                    })
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(step2Executed.get(), "异常后后续步骤不应执行");
            assertNotNull(caught.get());
        }

        @Test
        @DisplayName("thenMain 步骤抛异常，onError 收到原始异常")
        void mainStepExceptionPropagated() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> "data")
                    .thenMain((java.util.function.Function<String, Object>) s -> {
                        throw new IllegalArgumentException("main error");
                    })
                    .onError(e -> {
                        caught.set(e);
                        done.countDown();
                    })
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, caught.get());
            assertEquals("main error", caught.get().getMessage());
        }
    }

    // ==================== 回调触发 ====================

    @Nested
    @DisplayName("回调触发")
    class CallbackTest {

        @Test
        @DisplayName("onSuccess 在链正常完成时触发")
        void onSuccessTriggered() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> "success")
                    .onSuccess(s -> {
                        result.set(s);
                        done.countDown();
                    })
                    .onError(e -> done.countDown())
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals("success", result.get());
        }

        @Test
        @DisplayName("onSuccess 在链异常时不触发")
        void onSuccessNotTriggeredOnError() throws InterruptedException {
            flow = new TestableThreadFlow();

            AtomicBoolean successCalled = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            flow.create()
                    .async("q1", () -> {
                        throw new RuntimeException("fail");
                    })
                    .onSuccess(v -> successCalled.set(true))
                    .onError(e -> {})
                    .onComplete(done::countDown)
                    .start();

            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(successCalled.get(), "异常时 onSuccess 不应触发");
        }

        @Test
        @DisplayName("onComplete 无论成功或失败都触发")
        void onCompleteAlwaysTriggered() throws InterruptedException {
            flow = new TestableThreadFlow();

            CountDownLatch successDone = new CountDownLatch(1);
            CountDownLatch errorDone = new CountDownLatch(1);

            // 成功场景
            flow.create()
                    .async("q1", () -> null)
                    .onComplete(successDone::countDown)
                    .start();

            // 失败场景
            flow.create()
                    .async("q2", () -> {
                        throw new RuntimeException("fail");
                    })
                    .onError(e -> {})
                    .onComplete(errorDone::countDown)
                    .start();

            assertTrue(successDone.await(3, TimeUnit.SECONDS), "成功时 onComplete 应触发");
            assertTrue(errorDone.await(3, TimeUnit.SECONDS), "失败时 onComplete 也应触发");
        }
    }

    // ==================== future() 终端操作 ====================

    @Nested
    @DisplayName("future() 终端操作")
    class FutureTest {

        @Test
        @DisplayName("future() 返回的 CompletableFuture 可等待结果")
        void futureCanBeAwaited() throws Exception {
            flow = new TestableThreadFlow();

            CompletableFuture<String> future = flow.create()
                    .async("q1", () -> "result")
                    .future();

            assertEquals("result", future.get(3, TimeUnit.SECONDS));
        }
    }

    // ==================== 插件未绑定校验 ====================

    @Nested
    @DisplayName("插件绑定校验")
    class PluginBindingTest {

        @Test
        @DisplayName("插件未绑定时 create() 抛出 IllegalStateException")
        void createBeforeBindThrows() {
            ThreadFlow unbound = new ThreadFlow(new ThreadAPI());
            assertThrows(IllegalStateException.class, unbound::create,
                    "插件未绑定时应抛出异常");
        }

        @Test
        @DisplayName("插件未绑定时 virtual() 抛出 IllegalStateException")
        void virtualBeforeBindThrows() {
            ThreadFlow unbound = new ThreadFlow(new ThreadAPI());
            assertThrows(IllegalStateException.class,
                    () -> unbound.virtual(ctx -> {}),
                    "插件未绑定时应抛出异常");
        }
    }

    // ==================== 测试辅助 ====================

    /**
     * 可测试的 ThreadFlow，覆盖主线程调度为同步执行。
     * <p>
     * {@code scheduleOnMain} 直接在当前线程执行任务并完成 Future，
     * 避免调用 {@code Server.getInstance()}（测试环境无 Nukkit 服务器）。
     */
    static class TestableThreadFlow extends ThreadFlow {

        final java.util.concurrent.atomic.AtomicInteger scheduleOnMainCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger fireMainCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        TestableThreadFlow() {
            super(new ThreadAPI());
            // 使用 JDK 动态代理 mock Plugin
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

        void assertScheduleOnMainCalled() {
            assertTrue(scheduleOnMainCount.get() > 0,
                    "scheduleOnMain 应已被调用");
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
            return false; // 模拟非主线程环境
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
