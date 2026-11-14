package io.github.JiangHu.jframe.async.flow;

import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import io.github.JiangHu.jframe.async.release.ChannelAbortedException;
import io.github.JiangHu.jframe.async.release.ReleaseAPI;
import io.github.JiangHu.jframe.async.release.ReleaseChannel;
import io.github.JiangHu.jframe.async.release.ReleasePolicy;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowContext#awaitChannel}（ThreadFlow × ReleaseAPI 集成糖）的单元测试。
 * <p>
 * 覆盖计划文档（plans/async-main-release-queue-plan.md）第 4 章第 13 组场景：
 * 正常等待恢复 / abort 异常传播 / 主线程死锁防护 / 超时变体 / 批次快照语义。
 * 通过可测子类绕开真实 Nukkit Server，手动 tick 模拟主线程 drain。
 *
 * @see FlowContext#awaitChannel(ReleaseChannel)
 */
@DisplayName("FlowContext.awaitChannel 集成糖")
class FlowContextAwaitChannelLogicTest {

    /** 可测 ThreadFlow：主线程标记可变，绕开 Server.getInstance() */
    static class TestableThreadFlow extends ThreadFlow {
        volatile boolean primary = false;
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        TestableThreadFlow(ThreadAPI threadAPI) {
            super(threadAPI);
        }

        @Override
        protected boolean isPrimaryThread() {
            return primary;
        }

        @Override
        protected void logVirtualThreadError(Exception e) {
            errors.add(e);
        }
    }

    /** 可测 ReleaseAPI：手动 tick 推进 drain */
    static class TickableReleaseAPI extends ReleaseAPI {
        @Override
        protected TaskHandler scheduleRepeating() {
            return null;
        }

        @Override
        protected void requirePluginReady() {
            // 测试环境跳过插件绑定检查
        }

        void tick() {
            drain();
        }
    }

    private static Plugin mockPlugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> null);
    }

    @Test
    @DisplayName("正常等待：提交 + close 后 awaitChannel park，批次排干即恢复继续线性流程")
    void awaitChannelResumesAfterDrain() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquire(ReleasePolicy.deadlineSeconds(20));
        CountDownLatch done = new CountDownLatch(1);
        flow.virtual(ctx -> {
            for (int i = 0; i < 5; i++) {
                ch.submit(() -> {
                });
            }
            ch.close();
            ctx.awaitChannel(ch); // park 直至批次排干
            done.countDown();
        });

        // 虚拟线程 park 期间未完成
        assertFalse(done.await(100, TimeUnit.MILLISECONDS), "批次未排干前 awaitChannel 应 park");

        // 模拟主线程逐 tick 排空
        for (int i = 0; i < 10 && release.size() > 0; i++) {
            release.tick();
        }
        assertTrue(done.await(2, TimeUnit.SECONDS), "批次排干后 awaitChannel 应恢复");
    }

    @Test
    @DisplayName("主线程死锁防护：主线程等待活跃批次直接抛 IllegalStateException")
    void mainThreadAwaitThrows() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquire(ReleasePolicy.bestEffort());
        ch.submit(() -> {
        }); // 活跃批次（future 未完成）

        flow.primary = true;
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        flow.virtual(ctx -> {
            try {
                ctx.awaitChannel(ch);
            } catch (IllegalStateException e) {
                caught.set(e);
            }
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(caught.get() instanceof IllegalStateException,
                "主线程等待活跃批次应抛 IllegalStateException，实际: " + caught.get());
    }

    @Test
    @DisplayName("已完成批次主线程调用无害：立即返回")
    void mainThreadAwaitCompletedHarmless() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquire(ReleasePolicy.bestEffort());
        ch.submit(() -> {
        });
        release.tick(); // 排空 → future 完成

        flow.primary = true;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        flow.virtual(ctx -> {
            try {
                ctx.awaitChannel(ch); // 已完成 → 立即返回
            } catch (Throwable e) {
                caught.set(e);
            }
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNull(caught.get(), "已终结批次主线程调用无害");
    }

    @Test
    @DisplayName("abort 传播：等待者抛 CompletionException，cause 为 ChannelAbortedException")
    void abortPropagates() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquire(ReleasePolicy.bestEffort());
        ch.submit(() -> {
        });
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        flow.virtual(ctx -> {
            try {
                ctx.awaitChannel(ch);
            } catch (CompletionException ce) {
                caught.set(ce.getCause());
            }
            done.countDown();
        });
        assertFalse(done.await(100, TimeUnit.MILLISECONDS), "中止前仍在等待");

        ch.abort();
        assertTrue(done.await(2, TimeUnit.SECONDS), "abort 后等待者应恢复");
        assertTrue(caught.get() instanceof ChannelAbortedException,
                "cause 应为 ChannelAbortedException，实际: " + caught.get());
    }

    @Test
    @DisplayName("超时变体：批次未终结到期抛 CompletionException（cause 为 TimeoutException）")
    void awaitChannelTimeout() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquire(ReleasePolicy.bestEffort());
        ch.submit(() -> {
        }); // 保持活跃不排空

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        flow.virtual(ctx -> {
            try {
                ctx.awaitChannel(ch, 150, TimeUnit.MILLISECONDS);
            } catch (CompletionException ce) {
                caught.set(ce.getCause());
            }
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(caught.get() instanceof TimeoutException,
                "超时 cause 应为 TimeoutException，实际: " + caught.get());
        assertEquals(1, ch.size(), "超时不影响通道本身继续等待释放");
    }

    @Test
    @DisplayName("快照语义：AUTO 通道回收后复活是新批次，旧等待者等旧批次不受干扰")
    void snapshotSemantics() throws InterruptedException {
        TestableThreadFlow flow = new TestableThreadFlow(new ThreadAPI());
        flow.bindPlugin(mockPlugin());
        TickableReleaseAPI release = new TickableReleaseAPI();

        ReleaseChannel ch = release.acquireAuto(ReleasePolicy.bestEffort());
        ch.submit(() -> {
        });
        var firstFuture = ch.future();
        release.tick(); // 排空 → 旧批次完成
        assertTrue(firstFuture.isDone());

        // 复活：新批次新 future
        ch.submit(() -> {
        });
        var secondFuture = ch.future();
        assertNotSame(firstFuture, secondFuture, "复活 = 新批次（新 future）");
        assertFalse(secondFuture.isDone());

        // 旧等待者等旧批次：立即返回
        CountDownLatch oldDone = new CountDownLatch(1);
        flow.virtual(ctx -> {
            ctx.awaitChannel(new SnapshotChannel(firstFuture, ch));
            oldDone.countDown();
        });
        assertTrue(oldDone.await(2, TimeUnit.SECONDS), "等待旧批次快照应立即返回（已完成）");

        release.tick();
        assertTrue(secondFuture.isDone(), "新批次排干后完成");
    }

    /** 以指定 future 快照包装的通道视图（模拟「等待调用时刻批次」的旧引用语义） */
    private record SnapshotChannel(java.util.concurrent.CompletableFuture<Void> snapshot,
                                   ReleaseChannel delegate) implements ReleaseChannel {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public ReleasePolicy policy() {
            return delegate.policy();
        }

        @Override
        public void submit(Runnable task) {
            delegate.submit(task);
        }

        @Override
        public boolean trySubmit(Runnable task) {
            return delegate.trySubmit(task);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public void onComplete(Runnable callback) {
            delegate.onComplete(callback);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> future() {
            return snapshot;
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void abort() {
            delegate.abort();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }
    }
}
