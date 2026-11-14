package io.github.JiangHu.jframe.async.release;

import cn.nukkit.scheduler.TaskHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReleaseAPI} 多通道缓释调度器的单元测试（脱离真实 Nukkit Server）。
 * <p>
 * 通过 {@link TestableReleaseAPI} 覆盖 Nukkit 桥接方法（scheduleRepeating / cancelHandler /
 * requirePluginReady / logError），由测试手动调用 {@code drain()} 模拟 tick 推进。
 * 覆盖计划文档（plans/async-main-release-queue-plan.md）第 4 章的 1-12 组场景。
 *
 * @see ReleaseAPI
 * @see ReleasePolicy
 */
@DisplayName("ReleaseAPI 多通道缓释调度器")
class ReleaseAPILogicTest {

    /**
     * 可测实现：绕开 Nukkit 静态依赖，记录调度 / 取消 / 异常。
     */
    static class TestableReleaseAPI extends ReleaseAPI {
        int scheduledCount = 0;
        int cancelledCount = 0;
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        protected TaskHandler scheduleRepeating() {
            scheduledCount++;
            // 真实 TaskHandler 存根（非 null）：挂起判定 / isSuspended 才有区分度，
            // 由测试手动 tick() 驱动 drain，此任务不会被 Nukkit 真正调度
            return new TaskHandler(null, () -> {
            }, 1, false);
        }

        @Override
        protected void cancelHandler(TaskHandler handler) {
            if (handler != null) {
                cancelledCount++;
            }
        }

        @Override
        protected void requirePluginReady() {
            // 测试环境跳过插件绑定检查
        }

        @Override
        protected void logError(Throwable e) {
            errors.add(e);
        }

        void tick() {
            drain();
        }
    }

    // ==================== 1. best-effort 预算限流 ====================

    @Nested
    @DisplayName("best-effort 预算限流")
    class BudgetTest {

        @Test
        @DisplayName("默认通道入队 500：drain 两次各消费 200，第三次收尾")
        void bestEffortLimitedByBudget() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            AtomicInteger executed = new AtomicInteger();
            for (int i = 0; i < 500; i++) {
                api.submit(executed::incrementAndGet);
            }
            assertEquals(500, api.size());

            api.tick();
            assertEquals(300, api.size());
            assertEquals(200, executed.get());

            api.tick();
            assertEquals(100, api.size());

            api.tick();
            assertEquals(0, api.size());
            assertEquals(500, executed.get());
        }
    }

    // ==================== 2. DEADLINE 收敛 / 批次边界 / 超期退化 ====================

    @Nested
    @DisplayName("DEADLINE 截止时间")
    class DeadlineTest {

        @Test
        @DisplayName("deadline 10 tick 入队 100：每 tick 配额 ≈ ceil(pending/剩余tick)，期限内收敛")
        void deadlineConverges() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.channel("castle", ReleasePolicy.deadlineTicks(10));
            for (int i = 0; i < 100; i++) {
                ch.submit(() -> {
                });
            }
            // 第 1 tick：remain = 10 - 1 = 9，quota = ceil(100/9) = 12
            api.tick();
            assertEquals(88, ch.size());

            // 连续推进到第 9 tick：数学上 ceil 收敛，应在期限内清空
            for (int i = 0; i < 8; i++) {
                api.tick();
            }
            assertEquals(0, ch.size(), "deadline 10 tick 内应收敛清空");
        }

        @Test
        @DisplayName("批次边界：队列清空后再次 submit 视为新波，deadline 重新起算")
        void newWaveResetsDeadline() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.channel("wave", ReleasePolicy.deadlineTicks(10));

            for (int i = 0; i < 10; i++) {
                ch.submit(() -> {
                });
            }
            // deadline 10 tick 的收敛数学：每 tick 配额 ceil(pending/剩余tick)，非一 tick 清空
            for (int i = 0; i < 10 && ch.size() > 0; i++) {
                api.tick();
            }
            assertEquals(0, ch.size(), "期限内收敛清空");
            assertTrue(ch.future().isDone(), "第一波清空后当前批次 future 完成");

            // 新一波：新 future、重新计时
            var future2 = ch.future();
            ch.submit(() -> {
            });
            assertNotSame(future2, ch.future(), "新一批应换新 future（快照语义）");
            assertFalse(ch.future().isDone());
            api.tick();
            assertEquals(0, ch.size());
        }

        @Test
        @DisplayName("超期退化：违约 DEADLINE 通道退化为弹性，不挤压期限内刚性通道")
        void overdueDegradesToElastic() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            api.setGlobalBudgetPerTick(10);

            // A：deadline 3 tick、200 任务（消费能力不足必然违约）；B：deadline 50 tick、50 任务
            ReleaseChannel a = api.channel("overdue", ReleasePolicy.deadlineTicks(3));
            ReleaseChannel b = api.channel("ontime", ReleasePolicy.deadlineTicks(50));
            for (int i = 0; i < 200; i++) {
                a.submit(() -> {
                });
            }
            for (int i = 0; i < 50; i++) {
                b.submit(() -> {
                });
            }

            api.tick(); // ct=1：均在期限内，按比例缩放（A=10 保底、B=1 保底）
            api.tick(); // ct=2
            assertEquals(180, a.size());
            assertEquals(48, b.size());

            api.tick(); // ct=3：A 超期退化为弹性；B 刚性 demand=2 <= 10 足额，剩余 8 分给弹性 A
            assertEquals(46, b.size(), "期限内刚性通道不受超期通道挤压，按配额足额释放");
            assertEquals(172, a.size(), "超期通道退化为弹性，分食剩余带宽 8");
        }
    }

    // ==================== 3. RATE 小数配额 ====================

    @Nested
    @DisplayName("RATE 固定速率")
    class RateTest {

        @Test
        @DisplayName("0.5/tick：每 2 tick 释放 1 个")
        void fractionalRate() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.channel("slow", ReleasePolicy.ratePerTick(0.5));
            for (int i = 0; i < 5; i++) {
                ch.submit(() -> {
                });
            }
            api.tick();
            assertEquals(5, ch.size(), "累积器 0.5 不足以释放");
            api.tick();
            assertEquals(4, ch.size(), "累积到 1.0 释放 1 个");
            api.tick();
            assertEquals(4, ch.size());
            api.tick();
            assertEquals(3, ch.size());
        }

        @Test
        @DisplayName("rate 不超过 pending：尾巴截断")
        void rateCappedByPending() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.channel("fast", ReleasePolicy.ratePerTick(5));
            for (int i = 0; i < 12; i++) {
                ch.submit(() -> {
                });
            }
            api.tick();
            assertEquals(7, ch.size());
            api.tick();
            assertEquals(2, ch.size());
            api.tick();
            assertEquals(0, ch.size(), "尾巴 2 个一次释放（quota 截断到 pending）");
        }
    }

    // ==================== 4. 多通道仲裁 ====================

    @Nested
    @DisplayName("多通道仲裁")
    class ArbitrationTest {

        @Test
        @DisplayName("刚性按配额释放，best-effort 吃剩余；超预算按比例缩放保底 1")
        void rigidElasticArbitration() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            api.setGlobalBudgetPerTick(10);
            ReleaseChannel a = api.channel("rate-a", ReleasePolicy.ratePerTick(6));
            ReleaseChannel b = api.channel("rate-b", ReleasePolicy.ratePerTick(4));
            ReleaseChannel c = api.channel("be");
            for (int i = 0; i < 100; i++) {
                a.submit(() -> {
                });
                b.submit(() -> {
                });
            }
            for (int i = 0; i < 50; i++) {
                c.submit(() -> {
                });
            }

            // 刚性需求 10 == 预算：A=6、B=4，弹性 C 无剩余
            api.tick();
            assertEquals(94, a.size());
            assertEquals(96, b.size());
            assertEquals(50, c.size());

            // 预算调大到 20：刚性 10 足额，C 吃剩余 10
            api.setGlobalBudgetPerTick(20);
            api.tick();
            assertEquals(88, a.size());
            assertEquals(92, b.size());
            assertEquals(40, c.size());

            // 预算缩到 5：刚性需求 10 超预算 → A=round(6*5/10)=3、B=round(4*5/10)=2，C=0
            api.setGlobalBudgetPerTick(5);
            api.tick();
            assertEquals(85, a.size());
            assertEquals(90, b.size());
            assertEquals(40, c.size());
        }
    }

    // ==================== 5. 异常隔离 ====================

    @Nested
    @DisplayName("异常隔离")
    class IsolationTest {

        @Test
        @DisplayName("批内第 2 个任务抛异常，第 3 个仍执行")
        void taskExceptionIsolated() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            List<Exception> handled = new CopyOnWriteArrayList<>();
            api.setExceptionHandler(handled::add);

            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            AtomicInteger executed = new AtomicInteger();
            ch.submit(executed::incrementAndGet);
            ch.submit(() -> {
                throw new IllegalStateException("boom");
            });
            ch.submit(executed::incrementAndGet);

            api.tick();
            assertEquals(1, handled.size(), "异常交给 exceptionHandler");
            assertEquals(2, executed.get(), "异常前后任务均执行");
            assertEquals(0, api.size());
        }
    }

    // ==================== 6. 挂起与唤醒 ====================

    @Nested
    @DisplayName("挂起与唤醒")
    class SuspendTest {

        @Test
        @DisplayName("全空后取消调度（挂起）；再次 submit 自动唤醒（重新调度）")
        void suspendAndWake() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            api.submit(() -> {
            });
            assertEquals(1, api.scheduledCount);

            api.tick();
            assertTrue(api.isIdle());
            assertTrue(api.isSuspended(), "全空后调度挂起（空闲零开销）");

            api.submit(() -> {
            });
            assertEquals(2, api.scheduledCount, "新提交自动唤醒重新调度");
            assertFalse(api.isSuspended());
        }
    }

    // ==================== 7. 挂起唤醒竞态 ====================

    @Nested
    @DisplayName("并发竞态")
    class RaceTest {

        @Test
        @DisplayName("并发 submit 与 drain 交错无任务丢失")
        void concurrentSubmitAndDrain() throws InterruptedException {
            TestableReleaseAPI api = new TestableReleaseAPI();
            AtomicLong executed = new AtomicLong();
            int threads = 8;
            int perThread = 125;
            CountDownLatch done = new CountDownLatch(threads);

            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            ch.submit(executed::incrementAndGet);
                        }
                    } finally {
                        done.countDown();
                    }
                }, "submitter-" + t);
                worker.setDaemon(true);
                worker.start();
            }

            assertTrue(done.await(5, TimeUnit.SECONDS), "提交线程应全部完成");
            // 排空（模拟主线程持续 tick）
            for (int i = 0; i < 100 && api.size() > 0; i++) {
                api.tick();
            }
            assertEquals(0, api.size());
            assertEquals(threads * perThread, executed.get(), "并发提交与消费交错无任务丢失");
        }
    }

    // ==================== 8. 通道背压 ====================

    @Nested
    @DisplayName("通道背压")
    class BackpressureTest {

        @Test
        @DisplayName("maxBacklog=10：满后 submit 阻塞 / trySubmit false；消费后放行")
        void backpressureBlocksAndReleases() throws InterruptedException {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort(), 5);
            for (int i = 0; i < 5; i++) {
                ch.submit(() -> {
                });
            }
            assertFalse(ch.trySubmit(() -> {
            }), "背压满时 trySubmit 返回 false");

            // 第 6 个 submit 阻塞在背压信号量上
            AtomicBoolean submitted = new AtomicBoolean(false);
            Thread blocker = new Thread(() -> {
                ch.submit(() -> {
                });
                submitted.set(true);
            });
            blocker.setDaemon(true);
            blocker.start();

            Thread.sleep(150);
            assertFalse(submitted.get(), "背压满时 submit 阻塞");

            api.tick(); // 释放预算内全部任务 → 释放背压名额
            blocker.join(2000);
            assertTrue(submitted.get(), "消费释放名额后阻塞的 submit 放行");
        }
    }

    // ==================== 9. clear ====================

    @Nested
    @DisplayName("clear 清空")
    class ClearTest {

        @Test
        @DisplayName("清空后 pending 归零、背压名额全部释放、批次 future 完成")
        void clearReleasesBackpressureAndFuture() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort(), 5);
            for (int i = 0; i < 5; i++) {
                ch.submit(() -> {
                });
            }
            assertFalse(ch.trySubmit(() -> {
            }));

            ch.clear();
            assertEquals(0, ch.size());
            assertTrue(ch.trySubmit(() -> {
            }), "清空后背压名额全部释放");

            api.tick(); // clear 唤醒调度 → postRelease 完成当前批次 future
            assertTrue(ch.future().isDone(), "清空视为排干，当前批次 future 完成");
        }
    }

    // ==================== 10. removeChannel ====================

    @Nested
    @DisplayName("removeChannel 注销")
    class RemoveTest {

        @Test
        @DisplayName("注销后同名再取是新实例，旧实例被 abort")
        void removeThenRecreate() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel first = api.channel("x");
            first.submit(() -> {
            });

            assertTrue(api.removeChannel("x"));
            assertTrue(first.isClosed(), "注销 = abort 语义（显式终结）");
            assertEquals(0, first.size(), "注销丢弃存量");

            ReleaseChannel second = api.channel("x");
            assertNotSame(first, second, "同名再取是新实例");
            assertFalse(api.removeChannel("x-nowhere"));
        }
    }

    // ==================== 11. AUTO / MANUAL 生命周期 ====================

    @Nested
    @DisplayName("通道生命周期")
    class LifecycleTest {

        @Test
        @DisplayName("AUTO：空闲超阈值自动回收并触发回调；回收后再 submit 惰性复活")
        void autoRecycleAndRevive() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            api.setIdleRecycleTicks(3);
            AtomicInteger recycled = new AtomicInteger();
            AtomicInteger executed = new AtomicInteger();

            ReleaseChannel ch = api.acquireAuto(ReleasePolicy.bestEffort());
            ch.submit(executed::incrementAndGet);
            ch.onComplete(recycled::incrementAndGet);
            api.tick(); // 释放 + lastActive = ct1
            assertEquals(1, executed.get());

            api.tick(); // 空闲 1
            api.tick(); // 空闲 2
            assertEquals(0, recycled.get(), "空闲未超阈值不回收");
            api.tick(); // 空闲 3 >= 3 → 回收
            assertEquals(1, recycled.get(), "空闲超阈值自动回收并触发完成回调");

            // 惰性复活：回收后再 submit 正常入队执行（新生命周期、新批次 future）
            var oldFuture = ch.future();
            assertTrue(oldFuture.isDone());
            ch.submit(executed::incrementAndGet);
            assertNotSame(oldFuture, ch.future(), "复活 = 新批次（新 future）");
            api.tick();
            assertEquals(2, executed.get());
        }

        @Test
        @DisplayName("AUTO 两条匿名通道互不影响")
        void twoAutoChannelsIndependent() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            ReleaseChannel a = api.acquireAuto(ReleasePolicy.bestEffort());
            ReleaseChannel b = api.acquireAuto(ReleasePolicy.bestEffort());
            assertNotEquals(a.name(), b.name());

            a.submit(() -> {
            });
            assertEquals(1, a.size());
            assertEquals(0, b.size());
            api.tick();
            assertEquals(0, a.size());
        }

        @Test
        @DisplayName("MANUAL（acquire 默认）：空闲超过阈值不回收（零管理开销回归断言）")
        void manualNotRecycled() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            api.setIdleRecycleTicks(3);
            AtomicInteger callback = new AtomicInteger();

            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            ch.submit(() -> {
            });
            ch.onComplete(callback::incrementAndGet);
            api.tick();
            for (int i = 0; i < 6; i++) {
                api.tick(); // 空闲 6 tick，远超阈值
            }
            assertEquals(0, callback.get(), "MANUAL 通道不自动回收、不触发回调（需显式 close/abort）");
            assertFalse(ch.isClosed());
        }
    }

    // ==================== 12. 完成回调与终结 ====================

    @Nested
    @DisplayName("完成回调与终结")
    class CompletionTest {

        @Test
        @DisplayName("close：拒绝新提交；存量继续释放，排干后 onComplete 恰好一次、future 完成")
        void closeGraceful() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            AtomicInteger callback = new AtomicInteger();
            AtomicInteger executed = new AtomicInteger();

            // best-effort：预算内一 tick 全量释放，聚焦 close 语义本身
            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            for (int i = 0; i < 5; i++) {
                ch.submit(executed::incrementAndGet);
            }
            ch.onComplete(callback::incrementAndGet);
            ch.close();

            assertThrows(IllegalStateException.class, () -> ch.submit(() -> {
            }), "close 后拒绝新提交");

            api.tick(); // 存量全部释放 → 排干 → 回调
            assertEquals(5, executed.get(), "存量任务继续释放");
            assertEquals(1, callback.get(), "排干后 onComplete 恰好触发一次");
            assertTrue(ch.future().isDone());

            api.tick();
            assertEquals(1, callback.get(), "通道注销后回调不再触发");
        }

        @Test
        @DisplayName("close 时队列已空：立即（下个 tick）触发回调")
        void closeOnEmptyFiresSoon() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            AtomicInteger callback = new AtomicInteger();
            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            ch.onComplete(callback::incrementAndGet);
            ch.close();
            assertEquals(0, callback.get());

            api.tick(); // close 唤醒调度 → 立即处理
            assertEquals(1, callback.get());
        }

        @Test
        @DisplayName("abort：丢弃存量、不触发回调、future 异常完成（cause 为 ChannelAbortedException）")
        void abortDiscards() {
            TestableReleaseAPI api = new TestableReleaseAPI();
            AtomicInteger callback = new AtomicInteger();
            ReleaseChannel ch = api.acquire(ReleasePolicy.bestEffort());
            for (int i = 0; i < 5; i++) {
                ch.submit(() -> {
                });
            }
            ch.onComplete(callback::incrementAndGet);
            ch.abort();

            assertEquals(0, ch.size(), "abort 丢弃全部存量");
            assertTrue(ch.isClosed());
            assertTrue(ch.future().isCompletedExceptionally(), "future 异常完成");
            try {
                ch.future().join();
                fail("abort 后 join 应抛 CompletionException");
            } catch (CompletionException ce) {
                assertTrue(ce.getCause() instanceof ChannelAbortedException,
                        "cause 应为 ChannelAbortedException，实际: " + ce.getCause());
            }
            assertEquals(0, callback.get(), "abort 不触发回调");

            api.tick();
            assertEquals(0, callback.get());
        }
    }
}
