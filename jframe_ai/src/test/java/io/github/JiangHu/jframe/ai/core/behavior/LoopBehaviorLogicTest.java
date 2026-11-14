package io.github.JiangHu.jframe.ai.core.behavior;

import io.github.JiangHu.jframe.ai.core.executor.PlannedPath;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 循环行为骨架纯逻辑单元测试。
 * <p>
 * 覆盖不依赖运行中服务器的部分:构造防御、静态查询、
 * {@link BehaviorOutcome} 终态语义、{@link ComputeCarriers} 内建载体。
 * 涉及真实实体与调度的 {@code start()/tick()} 全周期流转
 * 需在真实服务器环境集成验证,此处不覆盖。
 *
 * @see LoopBehavior
 * @see ComputeCarriers
 */
@DisplayName("循环行为骨架")
class LoopBehaviorLogicTest {

    private final AStarPathFinder strategy = new AStarPathFinder();
    private final NavigatorManager navigators = new NavigatorManager();

    // ==================== 构造防御 ====================

    @Nested
    @DisplayName("构造参数防御")
    class Construction {

        @Test
        @DisplayName("self 为 null 抛 NullPointerException")
        void nullSelfRejected() {
            assertThrows(NullPointerException.class,
                    () -> new LoopBehavior(null, strategy, navigators));
        }

        @Test
        @DisplayName("defaultStrategy 为 null 抛 NullPointerException（self 非空时）")
        void nullStrategyRejected() {
            // self 传 null 时构造器先对 self 抛 NPE——同样证明防御存在
            assertThrows(NullPointerException.class,
                    () -> new LoopBehavior(null, null, navigators));
        }

        @Test
        @DisplayName("navigators 为 null 抛 NullPointerException（self 非空时）")
        void nullNavigatorManagerRejected() {
            assertThrows(NullPointerException.class,
                    () -> new LoopBehavior(null, strategy, null));
        }
    }

    // ==================== 静态查询 ====================

    @Nested
    @DisplayName("静态查询")
    class StaticQueries {

        @Test
        @DisplayName("isRunning(null) 返回 false")
        void isRunningNullIsFalse() {
            assertFalse(LoopBehavior.isRunning(null));
        }

        @Test
        @DisplayName("activeBehavior(null) 返回 null")
        void activeBehaviorNullIsNull() {
            assertNull(LoopBehavior.activeBehavior(null));
        }
    }

    // ==================== BehaviorOutcome ====================

    @Nested
    @DisplayName("BehaviorOutcome 终态")
    class Outcomes {

        @Test
        @DisplayName("四种终态齐备:ARRIVED/STOPPED/TARGET_LOST/PATH_FAILED")
        void allOutcomesPresent() {
            assertNotNull(BehaviorOutcome.valueOf("ARRIVED"));
            assertNotNull(BehaviorOutcome.valueOf("STOPPED"));
            assertNotNull(BehaviorOutcome.valueOf("TARGET_LOST"));
            assertNotNull(BehaviorOutcome.valueOf("PATH_FAILED"));
        }
    }

    // ==================== ComputeCarriers.sync ====================

    @Nested
    @DisplayName("sync 同步载体")
    class SyncCarrier {

        @Test
        @DisplayName("sync() 返回共享单例")
        void syncIsSingleton() {
            assertSame(ComputeCarriers.sync(), ComputeCarriers.sync());
        }

        @Test
        @DisplayName("dispatch 同步执行 run 并以 complete 交还结果")
        void dispatchRunsAndCompletesSynchronously() {
            AtomicInteger runs = new AtomicInteger();
            AtomicReference<PlannedPath> completed = new AtomicReference<>(null);
            // 哨兵:标记 complete 是否被调用(PlannedPath 无法脱离服务器构造,
            // 以"是否被改写"代替对具体值的断言)
            AtomicBoolean completedCalled = new AtomicBoolean(false);
            AtomicReference<Throwable> failed = new AtomicReference<>();

            ComputeCarriers.sync().dispatch(new ComputeTask() {
                @Override
                public LoopContext context() {
                    return null;
                }

                @Override
                public PlannedPath run() {
                    runs.incrementAndGet();
                    return null;
                }

                @Override
                public void complete(PlannedPath plan) {
                    completedCalled.set(true);
                    completed.set(plan);
                }

                @Override
                public void fail(Throwable error) {
                    failed.set(error);
                }
            });

            assertEquals(1, runs.get(), "run 应恰好执行一次");
            assertTrue(completedCalled.get(), "run 正常返回后应回调 complete");
            assertNull(completed.get(), "run 返回 null(目标失效约定)应原样传递");
            assertNull(failed.get(), "成功路径不应触发 fail");
        }

        @Test
        @DisplayName("run 抛异常时经 fail 交还而非吞掉")
        void dispatchPropagatesFailure() {
            AtomicReference<Throwable> failed = new AtomicReference<>();
            RuntimeException boom = new RuntimeException("boom");

            ComputeCarriers.sync().dispatch(new ComputeTask() {
                @Override
                public LoopContext context() {
                    return null;
                }

                @Override
                public PlannedPath run() {
                    throw boom;
                }

                @Override
                public void complete(PlannedPath plan) {
                }

                @Override
                public void fail(Throwable error) {
                    failed.set(error);
                }
            });

            assertSame(boom, failed.get(), "异常应原样交给 fail");
        }
    }

    // ==================== ComputeCarriers.of ====================

    @Nested
    @DisplayName("of 委托载体")
    class DelegatingCarrier {

        @Test
        @DisplayName("of(null) 抛 NullPointerException")
        void nullExecutorRejected() {
            assertThrows(NullPointerException.class, () -> ComputeCarriers.of(null));
        }

        @Test
        @DisplayName("dispatch 委托给外部 Executor 执行")
        void dispatchDelegatesToExecutor() {
            AtomicInteger executed = new AtomicInteger();
            // 直接执行式 Executor(测试用):提交即运行
            Executor direct = Runnable::run;
            ComputeCarrier carrier = ComputeCarriers.of(direct);

            carrier.dispatch(new ComputeTask() {
                @Override
                public LoopContext context() {
                    return null;
                }

                @Override
                public PlannedPath run() {
                    executed.incrementAndGet();
                    return null;
                }

                @Override
                public void complete(PlannedPath plan) {
                }

                @Override
                public void fail(Throwable error) {
                }
            });

            assertEquals(1, executed.get(), "run 应经 Executor 执行恰好一次");
        }

        @Test
        @DisplayName("of(Executor) 可用 lambda 载体实现（函数式接口）")
        void carrierIsFunctionalInterface() {
            AtomicInteger dispatched = new AtomicInteger();
            ComputeCarrier counting = task -> dispatched.incrementAndGet();

            counting.dispatch(new ComputeTask() {
                @Override
                public LoopContext context() {
                    return null;
                }

                @Override
                public PlannedPath run() {
                    return null;
                }

                @Override
                public void complete(PlannedPath plan) {
                }

                @Override
                public void fail(Throwable error) {
                }
            });

            assertEquals(1, dispatched.get());
        }
    }
}
