package io.github.JiangHu.jframe.transform;

import io.github.JiangHu.jframe.transform.bridge.HookContext;
import io.github.JiangHu.jframe.transform.bridge.MethodHook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令式 Hook API 全语义矩阵测试（设计方案 §6 用例 1-7/11/12/14）。
 * <p>
 * surefire JVM 内自附加 agent；目标为测试类（系统类加载器，bridge 同加载器单副本）。
 */
class TransformHookTest {

    // ==================== 测试目标 ====================

    /** 常规目标：实例/static/私有/void/基本类型/异常路径 */
    public static class SampleTarget {
        public int add(int a, int b) {
            return a + b;
        }

        public String greet(String name) {
            return "hello " + name;
        }

        public int counter;

        public void bump() {
            counter++;
        }

        private double secretFactor(double v) {
            return v * 2;
        }

        public static int stat(int x) {
            return x + 1;
        }

        public boolean boom(boolean flag) {
            if (flag) {
                throw new IllegalStateException("orig-boom");
            }
            return flag;
        }
    }

    /** 通配目标 A */
    public static class WildA {
        public int value() {
            return 1;
        }
    }

    /** 通配目标 B */
    public static class WildB {
        public int value() {
            return 2;
        }
    }

    private static String cn(Class<?> cls) {
        return cls.getName();
    }

    /** after-only 钩子构造助手（lambda 体即非空相位） */
    static MethodHook afterHook(Consumer<HookContext> body) {
        return new MethodHook() {
            @Override
            public void after(HookContext ctx) {
                body.accept(ctx);
            }
        };
    }

    /** before-only 钩子构造助手 */
    static MethodHook beforeHook(Consumer<HookContext> body) {
        return new MethodHook() {
            @Override
            public void before(HookContext ctx) {
                body.accept(ctx);
            }
        };
    }

    // ==================== 用例 1：attach ====================

    @Test
    void agentAvailableAndIdempotent() {
        assertTrue(TransformTestBase.registry().isAvailable());
        assertTrue(TransformTestBase.registry().isAvailable());   // 幂等
    }

    // ==================== 用例 2：before/after + 改参 + 改返回 + 短路 + 强抛 ====================

    @Test
    void beforeAfterObservedAndModifyArgs() {
        List<String> events = new ArrayList<>();
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                new io.github.JiangHu.jframe.transform.bridge.MethodHook() {
                    @Override
                    public void before(io.github.JiangHu.jframe.transform.bridge.HookContext ctx) {
                        events.add("before:" + ctx.getArgs()[0] + "," + ctx.getArgs()[1]);
                        ctx.setArg(0, 100);
                    }

                    @Override
                    public void after(io.github.JiangHu.jframe.transform.bridge.HookContext ctx) {
                        events.add("after:" + ctx.getReturn());
                    }
                }, HookPriority.NORMAL, true);

        try {
            assertEquals(102, new SampleTarget().add(1, 2));
            assertEquals(List.of("before:1,2", "after:102"), events);
        } finally {
            handle.unhook();
        }
        assertEquals(3, new SampleTarget().add(1, 2));   // 还原
    }

    @Test
    void setReturnOverridesResult() {
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "greet", null,
                afterHook(ctx -> ctx.setReturn("[" + ctx.getReturn() + "]")));
        try {
            assertEquals("[hello world]", new SampleTarget().greet("world"));
        } finally {
            handle.unhook();
        }
    }

    @Test
    void skipWithSkipsOriginalBody() {
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                beforeHook(ctx -> ctx.skipWith(9999)));
        try {
            assertEquals(9999, new SampleTarget().add(1, 2));
        } finally {
            handle.unhook();
        }
        assertEquals(3, new SampleTarget().add(1, 2));
    }

    @Test
    void skipDefaultsForPrimitiveAndForceThrow() {
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                new MethodHook() {
                    boolean first = true;

                    @Override
                    public void before(HookContext ctx) {
                        if (first) {
                            first = false;
                            ctx.skip();   // 基本类型 → 默认值 0
                        } else {
                            ctx.forceThrow(new UnsupportedOperationException("blocked"));
                        }
                    }
                });
        try {
            assertEquals(0, new SampleTarget().add(5, 6));   // skip 默认值
            assertThrows(UnsupportedOperationException.class, () -> new SampleTarget().add(5, 6));
        } finally {
            handle.unhook();
        }
    }

    // ==================== 用例 3：needsArgs=false ====================

    @Test
    void noArgsModeSkipsArgumentBoxing() {
        List<Object> seen = new ArrayList<>();
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "bump", null,
                beforeHook(ctx -> seen.add(ctx.getArgs())), HookPriority.NORMAL, false);
        try {
            SampleTarget target = new SampleTarget();
            target.bump();
            assertEquals(1, seen.size());
            assertNull(seen.get(0));   // needsArgs=false：before 阶段无参数数组
            assertEquals(1, target.counter);
        } finally {
            handle.unhook();
        }
    }

    // ==================== 用例 4：优先级链 / 参数接力 / skip 截断 ====================

    @Test
    void priorityChainWithArgsRelayAndSkipTruncation() {
        List<String> order = new ArrayList<>();
        HookHandle low = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                new MethodHook() {
                    @Override
                    public void before(HookContext ctx) {
                        order.add("low");
                        ctx.setArg(1, ((Integer) ctx.getArgs()[1]) + 1);   // 参数接力
                    }
                }, HookPriority.LOW, true);
        HookHandle high = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                new MethodHook() {
                    @Override
                    public void before(HookContext ctx) {
                        order.add("high");
                        ctx.skipWith(7);   // 截断：low 不再执行
                    }

                    @Override
                    public void after(HookContext ctx) {
                        order.add("after-high:" + ctx.getReturn());
                    }
                }, HookPriority.HIGH, true);
        try {
            assertEquals(7, new SampleTarget().add(1, 2));
            assertEquals(List.of("high", "after-high:7"), order);
        } finally {
            high.unhook();
            low.unhook();
        }
    }

    // ==================== 用例 5：unhook 幂等 ====================

    @Test
    void unhookRehookIdempotent() {
        TransformPluginScope scope = TransformTestBase.scope();
        HookHandle handle = scope.hook(cn(SampleTarget.class), "greet", null,
                afterHook(ctx -> ctx.setReturn("x")));
        handle.unhook();
        handle.unhook();   // 幂等
        assertEquals("hello k", new SampleTarget().greet("k"));
        HookHandle again = scope.hook(cn(SampleTarget.class), "greet", null,
                afterHook(ctx -> ctx.setReturn("y")));
        assertEquals("y", new SampleTarget().greet("k"));
        again.unhook();
        assertEquals("hello k", new SampleTarget().greet("k"));
    }

    // ==================== 用例 6：钩子异常不影响结果 ====================

    @Test
    void hookExceptionDoesNotAffectResult() {
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "add", null,
                beforeHook(ctx -> {
                    throw new RuntimeException("hook-bug");
                }));
        try {
            assertEquals(3, new SampleTarget().add(1, 2));   // 按未修改继续
        } finally {
            handle.unhook();
        }
    }

    // ==================== 用例 7：JDK 类拒绝 ====================

    @Test
    void jdkClassRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TransformTestBase.scope().hook("java.lang.String", "length",
                        afterHook(ctx -> {
                        })));
        assertThrows(IllegalArgumentException.class,
                () -> TransformTestBase.scope().hook("sun.nio.fs.FileSystems", "foo",
                        afterHook(ctx -> {
                        })));
    }

    // ==================== 用例 8：静态/私有方法 + REPLACE 全语义（用例 11） ====================

    @Test
    void staticAndPrivateMethodHook() {
        HookHandle statHandle = TransformTestBase.scope().hook(cn(SampleTarget.class), "stat", null,
                new MethodHook() {
                    @Override
                    public void before(HookContext ctx) {
                        ctx.setArg(0, 10);
                    }
                }, HookPriority.NORMAL, true);
        HookHandle secretHandle = TransformTestBase.scope().hook(cn(SampleTarget.class), "secretFactor", null,
                afterHook(ctx -> ctx.setReturn(((Double) ctx.getReturn()) + 1)));
        try {
            assertEquals(11, SampleTarget.stat(1));   // static：改参生效
            assertEquals(3.0, new SampleTarget().secretFactor(1.0), 1e-9);   // 私有：改返回
        } finally {
            statHandle.unhook();
            secretHandle.unhook();
        }
    }

    @Test
    void replaceSemantics() {
        TransformPluginScope scope = TransformTestBase.scope();
        HookHandle intHandle = scope.replace(cn(SampleTarget.class), "add",
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> 42);
        try {
            assertEquals(42, new SampleTarget().add(1, 2));
        } finally {
            intHandle.unhook();
        }

        // void 方法忽略返回值；null → 基本类型默认值 + 不抛
        HookHandle voidHandle = scope.replace(cn(SampleTarget.class), "bump",
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> "ignored");
        HookHandle nullHandle = scope.replace(cn(SampleTarget.class), "stat",
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> null);
        try {
            SampleTarget t = new SampleTarget();
            t.bump();
            assertEquals(0, t.counter);   // 原体未执行
            assertEquals(0, SampleTarget.stat(5));   // null → int 默认 0
        } finally {
            voidHandle.unhook();
            nullHandle.unhook();
        }

        // REPLACE 抛出传播给调用方
        HookHandle throwing = scope.replace(cn(SampleTarget.class), "greet",
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> {
                    throw new IllegalStateException("replaced");
                });
        try {
            assertThrows(IllegalStateException.class, () -> new SampleTarget().greet("x"));
        } finally {
            throwing.unhook();
        }
    }

    @Test
    void replacePriorityWinsAndAfterStillRuns() {
        List<String> events = new ArrayList<>();
        TransformPluginScope scope = TransformTestBase.scope();
        HookHandle lowReplace = scope.replace(cn(SampleTarget.class), "add", null,
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> 1,
                HookPriority.LOW, true);
        HookHandle highReplace = scope.replace(cn(SampleTarget.class), "add", null,
                (io.github.JiangHu.jframe.transform.bridge.ReplaceHook) ctx -> 2,
                HookPriority.HIGH, true);
        HookHandle afterHook = scope.hook(cn(SampleTarget.class), "add", null,
                new io.github.JiangHu.jframe.transform.bridge.MethodHook() {
                    @Override
                    public void after(io.github.JiangHu.jframe.transform.bridge.HookContext ctx) {
                        events.add("after:" + ctx.getReturn());
                    }
                });
        try {
            assertEquals(2, new SampleTarget().add(3, 4));   // HIGH 胜出
            assertEquals(List.of("after:2"), events);   // after 仍执行
        } finally {
            afterHook.unhook();
            highReplace.unhook();
            lowReplace.unhook();
        }
    }

    // ==================== 用例 12：PrivateAccess + ctx.field/call ====================

    @Test
    void privateAccessAndContextFieldHelpers() {
        SampleTarget target = new SampleTarget();
        target.counter = 5;
        assertEquals(5, PrivateAccess.get(target, "counter"));
        PrivateAccess.set(target, "counter", 9);
        assertEquals(9, target.counter);
        assertEquals(9.5 * 2, (double) PrivateAccess.invoke(target, "secretFactor", 9.5), 1e-9);

        List<Object> fieldSeen = new ArrayList<>();
        HookHandle handle = TransformTestBase.scope().hook(cn(SampleTarget.class), "bump", null,
                new MethodHook() {
                    @Override
                    public void after(HookContext ctx) {
                        fieldSeen.add(ctx.field("counter"));
                        ctx.setField("counter", 100);
                        fieldSeen.add(ctx.call("secretFactor", 1.0));
                        fieldSeen.add(ctx.getTargetClass() + "#" + ctx.getTargetMethod());
                    }
                });
        try {
            target.bump();
            assertEquals(10, fieldSeen.get(0));
            assertEquals(2.0, (double) fieldSeen.get(1), 1e-9);
            assertTrue(((String) fieldSeen.get(2)).endsWith("#bump"));
            assertEquals(100, target.counter);
        } finally {
            handle.unhook();
        }
    }

    // ==================== 用例 9：通配 ====================

    @Test
    void wildcardMatchesMultipleClasses() {
        HookHandle handle = TransformTestBase.scope().hook(
                cn(WildA.class).replace("WildA", "Wild*"), "value",
                afterHook(ctx -> ctx.setReturn(77)));
        try {
            assertEquals(77, new WildA().value());
            assertEquals(77, new WildB().value());
            assertTrue(handle.touchedClassNames().contains(cn(WildA.class)));
            assertTrue(handle.touchedClassNames().contains(cn(WildB.class)));
        } finally {
            handle.unhook();
        }
        assertEquals(1, new WildA().value());
        assertEquals(2, new WildB().value());
    }

    // ==================== 用例 14：相位省略 + 形状协调 ====================

    @Test
    void phaseElisionShapeAndRetransformCoordination() {
        TransformPluginScope scope = TransformTestBase.scope();
        // after-only：形状 EXIT
        HookHandle afterOnly = scope.hook(cn(SampleTarget.class), "greet", null,
                new io.github.JiangHu.jframe.transform.bridge.MethodHook() {
                    @Override
                    public void after(io.github.JiangHu.jframe.transform.bridge.HookContext ctx) {
                        ctx.setReturn(ctx.getReturn() + "!");
                    }
                });
        try {
            assertEquals(InstrumentShape.EXIT, afterOnly.shape());
            assertEquals("hello w!", new SampleTarget().greet("w"));
        } finally {
            afterOnly.unhook();
        }

        // 空相位钩子被拒（接口两相位皆空）
        assertThrows(IllegalArgumentException.class,
                () -> scope.hook(cn(SampleTarget.class), "greet",
                        new io.github.JiangHu.jframe.transform.bridge.MethodHook() {
                        }));

        // 同形状增删不触发 retransform；形状跃迁触发
        HookHandle base = statExitOnlyHook();
        try {
            int before = TransformTestBase.registry().retransformCount();
            HookHandle sameShape = TransformTestBase.scope().hook(cn(SampleTarget.class), "stat", null,
                    afterHook(ctx -> ctx.setReturn(0)));
            int mid = TransformTestBase.registry().retransformCount();
            assertEquals(before, mid);   // 同形状（EXIT→EXIT）零 retransform
            sameShape.unhook();
            assertEquals(before, TransformTestBase.registry().retransformCount());   // 仍同形状

            HookHandle beforeHook = TransformTestBase.scope().hook(cn(SampleTarget.class), "stat", null,
                    new MethodHook() {
                        @Override
                        public void before(HookContext ctx) {
                            ctx.setArg(0, 100);
                        }
                    }, HookPriority.NORMAL, true);
            try {
                assertTrue(TransformTestBase.registry().retransformCount() > before);   // EXIT→BOTH 重织
                assertEquals(101, SampleTarget.stat(1));   // before 改参 100 → 原体 100+1
            } finally {
                beforeHook.unhook();
            }
        } finally {
            base.unhook();
        }
        assertFalse(base.status() == HookStatus.FAILED);
    }

    /** stat 的 EXIT 相位基座钩子（读返回值，非空体） */
    private HookHandle statExitOnlyHook() {
        return TransformTestBase.scope().hook(cn(SampleTarget.class), "stat", null,
                afterHook(ctx -> {
                    ctx.getReturn();
                }));
    }
}
