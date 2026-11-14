package io.github.JiangHu.jframe.event.routing;

import io.github.JiangHu.jframe.event.EventConsumer;
import io.github.JiangHu.jframe.event.EventEngine;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * 事件分发性能基准：OBJECT / SINGLETON / STATIC 三模式 vs 直接调用基线。
 * <p>
 * 测量单次 dispatch 的平均耗时（纳秒），评估框架相对直接调用的额外开销。
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl jframe_event exec:java \
 *   -Dexec.mainClass="io.github.JiangHu.jframe.event.routing.EventDispatchBenchmark" \
 *   -Dexec.classpathScope=test
 * </pre>
 */
public class EventDispatchBenchmark {

    /** 预热次数（触发 JIT 编译） */
    static final int WARMUP = 100_000;
    /** 测量次数 */
    static final int MEASURE = 2_000_000;
    /** 防止 JIT 死码消除的接收槽 */
    static volatile long sink = 0;

    public static void main(String[] args) throws Exception {
        EventEngine engine = new EventEngine();

        // 每个模式用独立的 registry，避免相互干扰
        HandlerRegistry regObj = new HandlerRegistry(engine);
        regObj.register(BenchObject.class);

        HandlerRegistry regSingleton = new HandlerRegistry(engine);
        regSingleton.register(BenchSingleton.class);

        // STATIC 走 engine.subscribeTail，不进 HandlerRegistry
        HandlerRegistry regStatic = new HandlerRegistry(engine);
        regStatic.register(BenchStatic.class);

        MoveEvent event = new MoveEvent("P", 1, 0);

        // 通过反射获取 STATIC 注册的 tailConsumer
        EventConsumer<MoveEvent> staticConsumer = getTailConsumer(engine, MoveEvent.class);

        System.out.println("==================== 事件分发性能基准 ====================");
        System.out.println("预热: " + WARMUP + " 次, 测量: " + MEASURE + " 次");
        System.out.println("--------------------------------------------------------");
        System.out.printf(" %-18s %10s | %12s%n", "模式", "ns/op", "吞吐量");
        System.out.println("--------------------------------------------------------");

        // 1. 基线：直接 static 方法调用（理论最优）
        benchmark("基线（直接调用）", () -> BenchBaseline.onMove(event));

        // 2. OBJECT：KeyExtractor + cache lookup + MethodHandle
        benchmark("OBJECT 模式", () -> regObj.dispatch(MoveEvent.class, event));

        // 3. SINGLETON：单例字段访问 + MethodHandle
        benchmark("SINGLETON 模式", () -> regSingleton.dispatch(MoveEvent.class, event));

        // 4. STATIC：EventConsumer lambda + MethodHandle（static）
        benchmark("STATIC 模式", () -> staticConsumer.handleEvent(event));

        System.out.println("--------------------------------------------------------");
        System.out.println();
        System.out.println("【开销分析】");
        System.out.println("  OBJECT:   KeyExtractor.invoke + ConcurrentHashMap.get + MethodHandle.invoke");
        System.out.println("  SINGLETON: 单例字段访问 + MethodHandle.invoke");
        System.out.println("  STATIC:   EventConsumer lambda + MethodHandle.invoke（static）");
        System.out.println("  基线:     直接 static 方法调用（无框架开销）");
    }

    /**
     * 执行单次基准测量。
     *
     * @param name 场景名称
     * @param task 待测任务（单次 dispatch）
     */
    static void benchmark(String name, Runnable task) {
        // 预热
        for (int i = 0; i < WARMUP; i++) {
            task.run();
        }
        // 测量
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) {
            task.run();
        }
        long elapsed = System.nanoTime() - start;

        double avgNs = (double) elapsed / MEASURE;
        double mops = MEASURE / (elapsed / 1e9) / 1_000_000;
        System.out.printf(" %-18s %8.1f ns | %8.1f M ops/sec%n", name, avgNs, mops);
        sink ^= elapsed; // 防止优化
    }

    /**
     * 通过反射获取 EventEngine 中 STATIC 模式注册的 tailConsumer。
     */
    @SuppressWarnings("unchecked")
    static EventConsumer<MoveEvent> getTailConsumer(EventEngine engine, Class<?> eventType) throws Exception {
        Field f = EventEngine.class.getDeclaredField("tailConsumers");
        f.setAccessible(true);
        Map<Class<?>, List<EventConsumer<?>>> tailMap =
                (Map<Class<?>, List<EventConsumer<?>>>) f.get(engine);
        return (EventConsumer<MoveEvent>) tailMap.get(eventType).get(0);
    }

    // ==================== Benchmark Fixtures ====================

    /** OBJECT 模式：@KeyExtractor + 实例方法 + 带参构造 */
    public static class BenchObject {
        @KeyExtractor
        public static String extract(MoveEvent e) {
            return e.getPlayerName();
        }

        @EventRoute
        @EventHandler
        public void onMove(MoveEvent e) {
            // 空实现：仅测量分发开销
        }

        public BenchObject(String id) {
        }
    }

    /** SINGLETON 模式：实例方法 + 无参构造 */
    public static class BenchSingleton {
        @EventRoute
        @EventHandler
        public void onMove(MoveEvent e) {
        }

        public BenchSingleton() {
        }
    }

    /** STATIC 模式：static 方法 */
    public static class BenchStatic {
        @EventRoute
        @EventHandler
        public static void onMove(MoveEvent e) {
        }
    }

    /** 基线：直接 static 方法调用 */
    public static class BenchBaseline {
        public static void onMove(MoveEvent e) {
        }
    }
}
