package io.github.JiangHu.jframe.async.task;

import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SuspendableTask} 与 {@link TaskAPI} 的单元测试。
 * <p>
 * 通过覆盖 {@link SuspendableTask#scheduleTask()} 模拟 Nukkit 调度，
 * 无需启动真实服务器即可验证状态机逻辑。
 */
@DisplayName("可挂起任务系统")
class SuspendableTaskLogicTest {

    /** 动态代理创建的 mock Plugin，避免依赖真实 Nukkit 服务器 */
    private static final Plugin MOCK_PLUGIN = mockPlugin();

    /**
     * 创建一个对所有方法返回默认值的 Plugin 代理。
     */
    private static Plugin mockPlugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt.isPrimitive()) {
                        return Array.get(Array.newInstance(rt, 1), 0);
                    }
                    return null;
                });
    }

    /**
     * 可测试的任务子类：覆盖 scheduleTask 避免调用真实 Nukkit 调度器。
     * 供 StateMachineTest 与 TaskAPIRegistryTest 共用。
     */
    static class TestableSuspendableTask extends SuspendableTask {
        final AtomicInteger tickCount = new AtomicInteger();
        final AtomicInteger scheduleCount = new AtomicInteger();
        final AtomicInteger onStartCount = new AtomicInteger();
        final AtomicInteger onFinishCount = new AtomicInteger();
        final AtomicBoolean valid = new AtomicBoolean(true);

        TestableSuspendableTask() {
            super(MOCK_PLUGIN, 1);
        }

        @Override
        protected TaskHandler scheduleTask() {
            scheduleCount.incrementAndGet();
            return null;
        }

        @Override
        protected void onTick() {
            tickCount.incrementAndGet();
        }

        @Override
        protected boolean isValid() {
            return valid.get();
        }

        @Override
        protected void onStart() {
            onStartCount.incrementAndGet();
        }

        @Override
        protected void onFinish() {
            onFinishCount.incrementAndGet();
        }
    }

    // ==================== 构造校验 ====================

    @Nested
    @DisplayName("构造参数校验")
    class ConstructorTest {

        @Test
        @DisplayName("plugin 为 null 抛出 NullPointerException")
        void nullPluginThrows() {
            assertThrows(NullPointerException.class,
                    () -> new SuspendableTask.Functional(null, 1, () -> {}, () -> true));
        }

        @Test
        @DisplayName("interval <= 0 抛出 IllegalArgumentException")
        void nonPositiveIntervalThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SuspendableTask.Functional(MOCK_PLUGIN, 0, () -> {}, () -> true));
            assertThrows(IllegalArgumentException.class,
                    () -> new SuspendableTask.Functional(MOCK_PLUGIN, -1, () -> {}, () -> true));
        }

        @Test
        @DisplayName("Functional 的 tickAction / validChecker 为 null 抛出 NPE")
        void nullFunctionalArgsThrow() {
            assertThrows(NullPointerException.class,
                    () -> new SuspendableTask.Functional(MOCK_PLUGIN, 1, null, () -> true));
            assertThrows(NullPointerException.class,
                    () -> new SuspendableTask.Functional(MOCK_PLUGIN, 1, () -> {}, null));
        }
    }

    // ==================== Functional 委托 ====================

    @Nested
    @DisplayName("Functional 委托")
    class FunctionalDelegationTest {

        @Test
        @DisplayName("onTick 委托给 tickAction")
        void onTickDelegates() {
            AtomicInteger count = new AtomicInteger();
            SuspendableTask.Functional task = new SuspendableTask.Functional(
                    MOCK_PLUGIN, 1, count::incrementAndGet, () -> true);

            task.tick(); // 包级可见，直接调用

            assertEquals(1, count.get(), "tickAction 应被执行一次");
        }

        @Test
        @DisplayName("isValid 委托给 validChecker")
        void isValidDelegates() {
            AtomicBoolean valid = new AtomicBoolean(true);
            SuspendableTask.Functional task = new SuspendableTask.Functional(
                    MOCK_PLUGIN, 1, () -> {}, valid::get);

            assertTrue(task.isValid());
            valid.set(false);
            assertFalse(task.isValid());
        }
    }

    // ==================== 状态机 ====================

    @Nested
    @DisplayName("状态机：执行 / 幂等 / 挂起 / 恢复")
    class StateMachineTest {

        /**
         * 测试用任务子类：覆盖 scheduleTask 避免调用真实 Nukkit 调度器，
         * 并记录各回调的调用次数。
         */
        private static class TestableTask extends SuspendableTask {
            final AtomicInteger tickCount = new AtomicInteger();
            final AtomicInteger scheduleCount = new AtomicInteger();
            final AtomicInteger onStartCount = new AtomicInteger();
            final AtomicInteger onFinishCount = new AtomicInteger();
            final AtomicBoolean valid = new AtomicBoolean(true);

            TestableTask() {
                super(MOCK_PLUGIN, 1);
            }

            @Override
            protected TaskHandler scheduleTask() {
                scheduleCount.incrementAndGet();
                return null; // 不实际注册调度
            }

            @Override
            protected void onTick() {
                tickCount.incrementAndGet();
            }

            @Override
            protected boolean isValid() {
                return valid.get();
            }

            @Override
            protected void onStart() {
                onStartCount.incrementAndGet();
            }

            @Override
            protected void onFinish() {
                onFinishCount.incrementAndGet();
            }
        }

        @Test
        @DisplayName("初始状态：未运行、未完成")
        void initialState() {
            TestableTask task = new TestableTask();
            assertFalse(task.isRunning());
            assertFalse(task.isFinished());
        }

        @Test
        @DisplayName("execute() 启动任务：标记运行、调用 onStart、注册调度")
        void executeStartsTask() {
            TestableTask task = new TestableTask();

            task.execute();

            assertTrue(task.isRunning());
            assertEquals(1, task.scheduleCount.get(), "应注册一次调度");
            assertEquals(1, task.onStartCount.get(), "应调用一次 onStart");
            assertFalse(task.isFinished());
        }

        @Test
        @DisplayName("运行中再次 execute() 幂等：不重复注册、不重复 onStart")
        void executeWhileRunningIsIdempotent() {
            TestableTask task = new TestableTask();
            task.execute();

            task.execute(); // 再次调用
            task.execute(); // 第三次

            assertEquals(1, task.scheduleCount.get(), "不应重复注册调度");
            assertEquals(1, task.onStartCount.get(), "不应重复调用 onStart");
            assertTrue(task.isRunning());
        }

        @Test
        @DisplayName("tick() 在有效时执行 onTick")
        void tickWhenValidExecutesOnTick() {
            TestableTask task = new TestableTask();
            task.execute();

            task.tick();
            task.tick();
            task.tick();

            assertEquals(3, task.tickCount.get(), "onTick 应被调用 3 次");
            assertTrue(task.isRunning(), "仍应处于运行态");
        }

        @Test
        @DisplayName("isValid() 返回 false 时自动挂起：取消调度 + 触发 onFinish")
        void autoSuspendWhenInvalid() {
            TestableTask task = new TestableTask();
            task.execute();
            task.tick(); // 有效，执行一次

            task.valid.set(false);
            task.tick(); // 无效 → 自动挂起

            assertEquals(1, task.tickCount.get(), "无效后不应执行 onTick");
            assertFalse(task.isRunning(), "应已挂起（不在运行）");
            assertTrue(task.isFinished(), "应标记为已完成");
            assertEquals(1, task.onFinishCount.get(), "应调用一次 onFinish");
        }

        @Test
        @DisplayName("手动 cancel() 不触发 onFinish")
        void manualCancelDoesNotTriggerOnFinish() {
            TestableTask task = new TestableTask();
            task.execute();

            task.cancel();

            assertFalse(task.isRunning());
            assertFalse(task.isFinished(), "手动取消不应标记为已完成");
            assertEquals(0, task.onFinishCount.get(), "不应调用 onFinish");
        }

        @Test
        @DisplayName("挂起后可恢复：再次 execute() 重新注册调度")
        void resumeAfterSuspend() {
            TestableTask task = new TestableTask();
            task.execute();
            task.valid.set(false);
            task.tick(); // 自动挂起

            assertFalse(task.isRunning());
            task.valid.set(true); // 恢复有效状态

            task.execute(); // 重新启动

            assertTrue(task.isRunning(), "应重新进入运行态");
            assertEquals(2, task.scheduleCount.get(), "应第二次注册调度");
            assertEquals(2, task.onStartCount.get(), "应第二次调用 onStart");
            assertFalse(task.isFinished(), "finished 应被重置");
        }

        @Test
        @DisplayName("cancel 后可恢复：再次 execute() 重新注册调度")
        void resumeAfterCancel() {
            TestableTask task = new TestableTask();
            task.execute();
            task.cancel();

            task.execute(); // 重新启动

            assertTrue(task.isRunning());
            assertEquals(2, task.scheduleCount.get(), "应第二次注册调度");
        }

        @Test
        @DisplayName("完整生命周期：启动 → 执行 → 完成 → 恢复 → 再完成")
        void fullLifecycle() {
            TestableTask task = new TestableTask();

            // 第一轮
            task.execute();
            task.tick();
            task.tick();
            task.valid.set(false);
            task.tick(); // 完成
            assertEquals(2, task.tickCount.get());
            assertTrue(task.isFinished());

            // 第二轮（恢复）
            task.valid.set(true);
            task.execute();
            assertFalse(task.isFinished(), "恢复后 finished 应重置");
            task.tick();
            assertEquals(3, task.tickCount.get(), "第二轮应继续累计");
            task.valid.set(false);
            task.tick(); // 再次完成
            assertEquals(2, task.onFinishCount.get(), "应共调用 2 次 onFinish");
        }
    }

    // ==================== TaskAPI 注册表 ====================

    @Nested
    @DisplayName("TaskAPI 注册表管理")
    class TaskAPIRegistryTest {

        @Test
        @DisplayName("createTask 创建并注册任务")
        void createTaskRegisters() {
            TaskAPI api = new TaskAPI();
            api.bindPlugin(MOCK_PLUGIN);

            SuspendableTask task = api.createTask("job", 1, () -> {}, () -> true);

            assertNotNull(task);
            assertSame(task, api.getTask("job"));
            assertEquals(1, api.size());
        }

        @Test
        @DisplayName("createTask 同名幂等：返回已有实例")
        void createTaskIdempotent() {
            TaskAPI api = new TaskAPI();
            api.bindPlugin(MOCK_PLUGIN);

            SuspendableTask first = api.createTask("dup", 1, () -> {}, () -> true);
            SuspendableTask second = api.createTask("dup", 1, () -> {}, () -> true);

            assertSame(first, second, "同名应返回同一实例");
            assertEquals(1, api.size());
        }

        @Test
        @DisplayName("createTask 未绑定插件时抛出 IllegalStateException")
        void createTaskWithoutPluginThrows() {
            TaskAPI api = new TaskAPI();
            assertThrows(IllegalStateException.class,
                    () -> api.createTask("no-plugin", 1, () -> {}, () -> true));
        }

        @Test
        @DisplayName("register 注册自定义子类实例")
        void registerCustomTask() {
            TaskAPI api = new TaskAPI();
            SuspendableTask.Functional task = new SuspendableTask.Functional(
                    MOCK_PLUGIN, 1, () -> {}, () -> true);

            SuspendableTask registered = api.register("custom", task);

            assertSame(task, registered);
            assertSame(task, api.getTask("custom"));
        }

        @Test
        @DisplayName("register 同名幂等：返回已有实例")
        void registerIdempotent() {
            TaskAPI api = new TaskAPI();
            SuspendableTask.Functional first = new SuspendableTask.Functional(
                    MOCK_PLUGIN, 1, () -> {}, () -> true);
            SuspendableTask.Functional second = new SuspendableTask.Functional(
                    MOCK_PLUGIN, 1, () -> {}, () -> true);

            api.register("name", first);
            SuspendableTask result = api.register("name", second);

            assertSame(first, result, "同名应返回先注册的实例");
            assertSame(first, api.getTask("name"));
        }

        @Test
        @DisplayName("execute(name) 触发已注册任务")
        void executeByName() {
            TaskAPI api = new TaskAPI();
            api.bindPlugin(MOCK_PLUGIN);

            AtomicInteger scheduleCount = new AtomicInteger();
            SuspendableTask task = new SuspendableTask(MOCK_PLUGIN, 1) {
                @Override
                protected TaskHandler scheduleTask() {
                    scheduleCount.incrementAndGet();
                    return null;
                }

                @Override
                protected void onTick() {}

                @Override
                protected boolean isValid() {
                    return true;
                }
            };
            api.register("trigger", task);

            api.execute("trigger");

            assertEquals(1, scheduleCount.get(), "应通过名称触发调度注册");
            assertTrue(api.isRunning("trigger"));
        }

        @Test
        @DisplayName("execute(unknown) 静默忽略")
        void executeUnknownIgnored() {
            TaskAPI api = new TaskAPI();
            assertDoesNotThrow(() -> api.execute("ghost"));
        }

        @Test
        @DisplayName("cancel(name) 取消并移除任务")
        void cancelByName() {
            TaskAPI api = new TaskAPI();
            api.bindPlugin(MOCK_PLUGIN);
            // 注册可测试任务（覆盖 scheduleTask 避免真实调度）
            SuspendableTask task = new TestableSuspendableTask();
            api.register("kill", task);
            api.execute("kill");

            assertTrue(task.isRunning());
            assertTrue(api.cancel("kill"), "取消已存在任务应返回 true");
            assertNull(api.getTask("kill"), "移除后不应再获取到任务");
            assertFalse(task.isRunning(), "任务应已被取消");
            assertFalse(api.cancel("kill"), "再次取消应返回 false");
        }

        @Test
        @DisplayName("cancelAll 取消所有任务并清空注册表")
        void cancelAll() {
            TaskAPI api = new TaskAPI();
            api.bindPlugin(MOCK_PLUGIN);
            api.createTask("a", 1, () -> {}, () -> true);
            api.createTask("b", 1, () -> {}, () -> true);
            api.createTask("c", 1, () -> {}, () -> true);

            api.cancelAll();

            assertEquals(0, api.size(), "注册表应已清空");
            assertNull(api.getTask("a"));
            assertNull(api.getTask("b"));
            assertNull(api.getTask("c"));
        }

        @Test
        @DisplayName("bindPlugin 注入插件后 getPlugin 可用")
        void bindPluginMakesPluginAvailable() {
            TaskAPI api = new TaskAPI();
            assertNull(api.getPlugin());

            api.bindPlugin(MOCK_PLUGIN);

            assertSame(MOCK_PLUGIN, api.getPlugin());
        }
    }
}
