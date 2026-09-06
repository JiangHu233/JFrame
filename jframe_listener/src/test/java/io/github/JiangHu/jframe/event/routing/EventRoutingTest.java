package io.github.JiangHu.jframe.event.routing;

import cn.nukkit.event.Event;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.event.EventConsumer;
import io.github.JiangHu.jframe.event.EventEngine;
import io.github.JiangHu.jframe.event.test.NukkitTestBootstrap;
import io.github.JiangHu.jframe.event.test.fixture.ConditionWrapper;
import io.github.JiangHu.jframe.event.test.fixture.ExclusiveWrapper;
import io.github.JiangHu.jframe.event.test.fixture.FactoryWrapper;
import io.github.JiangHu.jframe.event.test.fixture.FilterWrapper;
import io.github.JiangHu.jframe.event.test.fixture.StaticFilterWrapper;
import io.github.JiangHu.jframe.event.test.fixture.MultiSlotWrapper;
import io.github.JiangHu.jframe.event.test.fixture.MixedStaticSingletonWrapper;
import io.github.JiangHu.jframe.event.test.fixture.MutualExclusionWrapper;
import io.github.JiangHu.jframe.event.test.fixture.NoExtractorWrapper;
import io.github.JiangHu.jframe.event.test.fixture.PartialExtractorWrapper;
import io.github.JiangHu.jframe.event.test.fixture.PriorityWrapper;
import io.github.JiangHu.jframe.event.test.fixture.ScanTargetWrapper;
import io.github.JiangHu.jframe.event.test.fixture.ExecutionOrderTracker;
import io.github.JiangHu.jframe.event.test.fixture.SingletonWrapper;
import io.github.JiangHu.jframe.event.test.fixture.StatWrapper;
import io.github.JiangHu.jframe.event.test.fixture.StaticWrapper;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.CombatEvent;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 事件路由逻辑测试（无 Nukkit 服务器，纯 JVM 可运行）。
 * <p>
 * 本类位于 {@code io.github.JiangHu.jframe.event.routing} 包内，因此可直接调用
 * {@link HandlerRegistry#dispatch(Class, Event)}（包级可见）驱动路由引擎，
 * 无需启动 Nukkit 事件管线。
 * <p>
 * <b>测试隔离</b>：每个用例使用独立的 {@link EventEngine} + {@link HandlerRegistry}，
 * 仅注册当前用例所需的包装类，避免跨用例的优先级 / 独占干扰。
 * <p>
 * 覆盖特性一览：
 * <ol>
 *   <li>基础分发 + 默认缓存（实例复用）</li>
 *   <li>evict 驱逐实例</li>
 *   <li>优先级排序（HIGHEST → LOWEST）</li>
 *   <li>跨优先级独占（exclusive）</li>
 *   <li>SpEL condition 条件</li>
 *   <li>filter 方法引用</li>
 *   <li>多槽位 @KeyExtractor（OR 语义）+ null 跳过</li>
 *   <li>@InstanceProvider 工厂 + 显式事件类型 + 工厂返回 null 跳过</li>
 *   <li>register / unregister</li>
 *   <li>无 @KeyExtractor 注册被拒绝</li>
 *   <li>condition 与 filter 互斥（抛异常）</li>
 *   <li>@Wrapper 包扫描注册</li>
 *   <li>EventEngine subscribe / unsubscribe / unsubscribeAll</li>
 * </ol>
 *
 * <p>运行方式：直接执行 {@link #main}。
 */
public final class EventRoutingTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        NukkitTestBootstrap.bootstrap();
        System.out.println("==================== 开始事件路由测试 ====================");

        testBasicDispatchAndDefaultCache();
        testEvict();
        testPriorityOrdering();
        testExclusive();
        testSpelCondition();
        testFilterMethod();
        testStaticFilterMethod();
        testMultiSlotOrSemantics();
        testInstanceProviderFactory();
        testUnregister();
        testSingletonCreationFailure();
        testSingletonMode();
        testStaticMode();
        testStaticTailOrdering();
        testMixedStaticAndInstance();
        testSingletonDedup();
        testMissingExtractorWarning();
        testConditionFilterMutualExclusion();
        testWrapperScan();
        testEventEngineSubscribeUnsubscribe();

        System.out.println("==================== 测试汇总 ====================");
        System.out.println("通过: " + pass + "，失败: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✅");
    }

    // ==================== 用例 ====================

    /** 1. 基础分发 + 默认缓存（实例复用） */
    private static void testBasicDispatchAndDefaultCache() {
        HandlerRegistry reg = newRegistry();
        reg.register(StatWrapper.class);
        StatWrapper.reset();

        // 首次事件：创建实例 + 触发处理器
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 1, 0));
        check("1a. 首次分发触发处理器", StatWrapper.getInvocations().equals(List.of("Alice@1")));
        check("1b. 首次分发创建1个实例", StatWrapper.getCreated().size() == 1);

        // 同一玩家再次事件：复用实例（构造次数不变）
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 2, 0));
        check("1c. 同身份复用实例", StatWrapper.getCreated().size() == 1);
        check("1d. 第二次分发触发处理器",
                StatWrapper.getInvocations().equals(List.of("Alice@1", "Alice@2")));

        // 不同玩家：创建新实例
        reg.dispatch(MoveEvent.class, new MoveEvent("Bob", 3, 0));
        check("1e. 不同身份创建新实例", StatWrapper.getCreated().size() == 2);
    }

    /** 2. evict 驱逐实例 */
    private static void testEvict() {
        HandlerRegistry reg = newRegistry();
        reg.register(StatWrapper.class);
        StatWrapper.reset();

        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 1, 0));
        int before = StatWrapper.getCreated().size();

        // 驱逐 Alice 的缓存实例
        reg.evict(StatWrapper.class, "Alice");
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 2, 0));

        check("2. evict 后重新创建实例", StatWrapper.getCreated().size() == before + 1);
    }

    /** 3. 优先级排序 */
    private static void testPriorityOrdering() {
        HandlerRegistry reg = newRegistry();
        reg.register(PriorityWrapper.class);
        PriorityWrapper.reset();

        reg.dispatch(MoveEvent.class, new MoveEvent("P", 1, 0));
        check("3. 优先级降序分发 HIGH→NORMAL→LOW",
                PriorityWrapper.getOrder().equals(List.of("HIGH", "NORMAL", "LOW")));
    }

    /** 4. 跨优先级独占 */
    private static void testExclusive() {
        HandlerRegistry reg = newRegistry();
        reg.register(ExclusiveWrapper.class);
        ExclusiveWrapper.reset();

        reg.dispatch(MoveEvent.class, new MoveEvent("E", 1, 0));
        check("4. HIGH 独占后 NORMAL/LOW 不执行",
                ExclusiveWrapper.getOrder().equals(List.of("HIGH-EXCL")));
    }

    /** 5. SpEL condition 条件 */
    private static void testSpelCondition() {
        HandlerRegistry reg = newRegistry();
        reg.register(ConditionWrapper.class);
        ConditionWrapper.reset();

        // 包含 "spam" → 条件成立 → 执行
        reg.dispatch(ChatEvent.class, new ChatEvent("C", "this is spam"));
        // 不含 "spam" → 条件不成立 → 不执行
        reg.dispatch(ChatEvent.class, new ChatEvent("C", "hello"));

        check("5. SpEL 条件仅匹配 spam 消息",
                ConditionWrapper.getMatched().equals(List.of("C:this is spam")));
    }

    /** 6. filter 方法引用 */
    private static void testFilterMethod() {
        HandlerRegistry reg = newRegistry();
        reg.register(FilterWrapper.class);
        FilterWrapper.reset();

        // 消息长度 > 5 → filter 返回 true → 执行
        reg.dispatch(ChatEvent.class, new ChatEvent("F", "long message"));
        // 消息长度 <= 5 → filter 返回 false → 不执行
        reg.dispatch(ChatEvent.class, new ChatEvent("F", "hi"));

        check("6. filter 方法仅放行长消息",
                FilterWrapper.getMatched().equals(List.of("F:long message")));
    }

    /** 6b. 静态 filter 方法引用（回归：static filter 的 MethodHandle 无 receiver 参数） */
    private static void testStaticFilterMethod() {
        HandlerRegistry reg = newRegistry();
        reg.register(StaticFilterWrapper.class);
        StaticFilterWrapper.reset();

        // 消息长度 > 5 → static filter 返回 true → 执行
        reg.dispatch(ChatEvent.class, new ChatEvent("S", "long message"));
        // 消息长度 <= 5 → static filter 返回 false → 不执行
        reg.dispatch(ChatEvent.class, new ChatEvent("S", "hi"));

        check("6b. 静态 filter 方法仅放行长消息",
                StaticFilterWrapper.getMatched().equals(List.of("S:long message")));
    }

    /** 7. 多槽位 @KeyExtractor（OR 语义）+ null 跳过 */
    private static void testMultiSlotOrSemantics() {
        HandlerRegistry reg = newRegistry();
        reg.register(MultiSlotWrapper.class);
        MultiSlotWrapper.reset();

        // 攻击者 Alice + 受害者 Bob → 两个槽位都匹配 → 两个实例被通知（顺序不保证）
        reg.dispatch(CombatEvent.class, new CombatEvent("Alice", "Bob", 10));
        check("7a. 双槽位匹配通知两个身份",
                MultiSlotWrapper.getNotified().size() == 2
                        && MultiSlotWrapper.getNotified().containsAll(List.of("Alice", "Bob")));

        // 受害者为 null → 仅攻击者槽位匹配（累计 3 次通知）
        reg.dispatch(CombatEvent.class, new CombatEvent("Alice", null, 5));
        check("7b. null 槽位跳过，仅攻击者通知",
                MultiSlotWrapper.getNotified().size() == 3);
    }

    /** 8. @InstanceProvider 工厂 + 显式事件类型 + 工厂返回 null 跳过 */
    private static void testInstanceProviderFactory() {
        HandlerRegistry reg = newRegistry();
        reg.register(FactoryWrapper.class);
        FactoryWrapper.reset();
        FactoryWrapper.register("Hero"); // 预置外部缓存

        // 已注册身份 → 工厂返回缓存实例 → 执行
        reg.dispatch(MoveEvent.class, new MoveEvent("Hero", 1, 0));
        check("8a. 工厂返回实例并触发处理器",
                FactoryWrapper.getInvocations().equals(List.of("Hero@1")));
        check("8b. 走的是工厂方法（调用次数>=1）", FactoryWrapper.getFactoryCalls() >= 1);

        int callsBefore = FactoryWrapper.getFactoryCalls();
        // 同一身份再次事件 → 工厂仍被调用（工厂模式每次都查缓存），实例复用
        reg.dispatch(MoveEvent.class, new MoveEvent("Hero", 2, 0));
        check("8c. 工厂模式实例复用",
                FactoryWrapper.getInvocations().equals(List.of("Hero@1", "Hero@2")));
        check("8d. 工厂每次分发都被调用", FactoryWrapper.getFactoryCalls() > callsBefore);

        // 未注册身份 → 工厂返回 null → 跳过
        reg.dispatch(MoveEvent.class, new MoveEvent("Unknown", 3, 0));
        check("8e. 工厂返回 null 时跳过事件",
                FactoryWrapper.getInvocations().equals(List.of("Hero@1", "Hero@2")));
    }

    /** 9. register / unregister */
    private static void testUnregister() {
        HandlerRegistry reg = newRegistry();
        reg.register(StatWrapper.class);
        StatWrapper.reset();

        // 注销前：可分发
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 1, 0));
        check("9a. 注销前可分发", !StatWrapper.getInvocations().isEmpty());

        // 注销整个包装类
        reg.unregister(StatWrapper.class);
        StatWrapper.reset();
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 2, 0));
        check("9b. 注销后不再分发", StatWrapper.getInvocations().isEmpty());
    }

    /**
     * 10. SINGLETON 无无参构造时创建失败抛异常。
     * <p>
     * NoExtractorWrapper 无 @KeyExtractor + 实例方法 → 判定为 SINGLETON，
     * 但它只有 `NoExtractorWrapper(String)` 构造，无无参构造 → createSingleton 抛 IllegalStateException。
     */
    private static void testSingletonCreationFailure() {
        HandlerRegistry reg = newRegistry();
        NoExtractorWrapper.reset();
        boolean threw = false;
        try {
            reg.register(NoExtractorWrapper.class);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("10. SINGLETON 无无参构造时抛 IllegalStateException", threw);
    }

    /** 14. SINGLETON 模式：单例实例复用 */
    private static void testSingletonMode() {
        SingletonWrapper.reset();
        HandlerRegistry reg = newRegistry();
        reg.register(SingletonWrapper.class);

        // 多次分发不同身份 → 始终复用同一单例实例
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 1, 0));
        reg.dispatch(MoveEvent.class, new MoveEvent("Bob", 2, 0));
        reg.dispatch(MoveEvent.class, new MoveEvent("Charlie", 3, 0));

        check("14a. SINGLETON 所有事件共享1个实例", SingletonWrapper.getInstanceCount() == 1);
        check("14b. SINGLETON 处理器被调用3次",
                SingletonWrapper.getInvocations().equals(List.of("Alice@1", "Bob@2", "Charlie@3")));
    }

    /** 15. STATIC 模式：走兜底链执行 */
    private static void testStaticMode() {
        EventEngine engine = new EventEngine();
        HandlerRegistry reg = new HandlerRegistry(engine);
        reg.register(StaticWrapper.class);
        ExecutionOrderTracker.reset();

        // STATIC 走 tailConsumers，需通过 EventEngine.dispatch 触发
        invokeEngineDispatch(engine, MoveEvent.class, new MoveEvent("Static", 1, 0));

        check("15. STATIC handler 通过兜底链执行",
                ExecutionOrderTracker.getOrder().equals(List.of("STATIC:Static")));
    }

    /** 16. STATIC 兜底顺序：primary 先于 tail */
    private static void testStaticTailOrdering() {
        EventEngine engine = new EventEngine();
        HandlerRegistry reg = new HandlerRegistry(engine);

        // 注册一个 primary consumer（模拟 OBJECT/SINGLETON 走 primaryConsumers）
        engine.subscribe(MoveEvent.class, event -> {
            ExecutionOrderTracker.record("PRIMARY:" + event.getPlayerName());
            return false;
        });

        // 注册 STATIC wrapper → tail
        reg.register(StaticWrapper.class);
        ExecutionOrderTracker.reset();

        // 通过 EventEngine.dispatch 触发两段分发（primary → tail）
        invokeEngineDispatch(engine, MoveEvent.class, new MoveEvent("Order", 1, 0));

        // PRIMARY 先执行，STATIC 后执行
        List<String> order = ExecutionOrderTracker.getOrder();
        check("16a. PRIMARY 和 STATIC 都执行", order.size() == 2);
        check("16b. PRIMARY 先于 STATIC 执行（兜底顺序保证）",
                order.equals(List.of("PRIMARY:Order", "STATIC:Order")));
    }

    /**
     * 16c. 混合写法（实例 handler + static handler）：static 始终走兜底链，不被误判为 SINGLETON。
     * <p>
     * MixedStaticSingletonWrapper 无 @KeyExtractor，同时含实例方法 onMove 与 static 方法 onMoveStatic。
     * 修复前：类级判定 allStatic=false → 整体 SINGLETON，static handler 被卷入 primary 链路。
     * 修复后：按 handler 级别分流，static handler 进 tailConsumers，实例 handler 进 SINGLETON。
     */
    private static void testMixedStaticAndInstance() {
        EventEngine engine = new EventEngine();
        HandlerRegistry reg = new HandlerRegistry(engine);
        MixedStaticSingletonWrapper.reset();
        reg.register(MixedStaticSingletonWrapper.class);

        // static handler 应进入 tailConsumers（兜底链），而非被卷入 SINGLETON 的 primary 链
        check("16c-1. 混合写法下 static handler 走兜底链（tailConsumers >= 1）",
                tailConsumerCount(engine, MoveEvent.class) >= 1);

        // 通过 EventEngine.dispatch 触发 primary → tail 两段
        invokeEngineDispatch(engine, MoveEvent.class, new MoveEvent("Mix", 1, 0));

        List<String> order = MixedStaticSingletonWrapper.getOrder();
        check("16c-2. 实例与 static handler 均执行", order.size() == 2);
        check("16c-3. 实例 handler（primary）先于 static handler（tail）执行",
                order.equals(List.of("INSTANCE:Mix", "STATIC:Mix")));
        // 实例 handler 走 SINGLETON：只创建 1 个实例
        check("16c-4. 实例 handler 走 SINGLETON（单例复用）",
                MixedStaticSingletonWrapper.getInstanceCount() == 1);

        // reg.dispatch 仅触发 primary（OBJECT/SINGLETON），不应触发 tail 中的 static handler
        MixedStaticSingletonWrapper.reset();
        reg.dispatch(MoveEvent.class, new MoveEvent("Mix2", 2, 0));
        check("16c-5. reg.dispatch（仅 primary）不触发 static 兜底 handler",
                MixedStaticSingletonWrapper.getOrder().equals(List.of("INSTANCE:Mix2")));
    }

    /** 17. SINGLETON 去重：共享引用不被吞 */
    private static void testSingletonDedup() {
        SingletonWrapper.reset();
        HandlerRegistry reg = newRegistry();
        reg.register(SingletonWrapper.class);

        // 同一事件多次分发 → 单例实例只创建一次，handler 每次都触发
        reg.dispatch(MoveEvent.class, new MoveEvent("A", 1, 0));
        reg.dispatch(MoveEvent.class, new MoveEvent("A", 2, 0));

        check("17a. SINGLETON 去重后实例仍为1", SingletonWrapper.getInstanceCount() == 1);
        check("17b. SINGLETON 每次分发都触发 handler",
                SingletonWrapper.getInvocations().size() == 2);
    }

    /**
     * 18. OBJECT 模式下 handler 事件类型缺失 @KeyExtractor → 注册时 WARNING，dispatch 时静默跳过。
     * <p>
     * PartialExtractorWrapper 仅为 MoveEvent 声明了 @KeyExtractor，ChatEvent 的 handler 无对应 extractor。
     * 注册时控制台应输出 WARNING（人工可见），dispatch ChatEvent 时 onChat 不执行。
     */
    private static void testMissingExtractorWarning() {
        PartialExtractorWrapper.reset();
        HandlerRegistry reg = newRegistry();
        // 注册时触发 WARNING 日志（ChatEvent 缺少 @KeyExtractor）
        reg.register(PartialExtractorWrapper.class);

        // MoveEvent 有 extractor → onMove 正常执行
        reg.dispatch(MoveEvent.class, new MoveEvent("Alice", 1, 0));
        check("18a. 有 extractor 的事件类型正常执行", PartialExtractorWrapper.isMoveInvoked());

        // ChatEvent 无 extractor → onChat 静默跳过（不执行）
        reg.dispatch(ChatEvent.class, new ChatEvent("Alice", "hi"));
        check("18b. 缺少 extractor 的事件类型静默跳过（handler 不执行）",
                !PartialExtractorWrapper.isChatInvoked());
    }

    /** 11. condition 与 filter 互斥（抛 IllegalArgumentException） */
    private static void testConditionFilterMutualExclusion() {
        HandlerRegistry reg = newRegistry();
        boolean threw = false;
        try {
            reg.register(MutualExclusionWrapper.class);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("11. condition 与 filter 同时指定时注册抛 IllegalArgumentException", threw);
    }

    /** 12. @Wrapper 包扫描注册 */
    private static void testWrapperScan() {
        EventEngine engine = new EventEngine();
        HandlerRegistry reg = new HandlerRegistry(engine);
        EventAPI api = new EventAPI(reg, engine);
        ScanTargetWrapper.reset();

        // 扫描 fixture 包，自动发现 @Wrapper 类
        List<Class<?>> registered = api.scan("io.github.JiangHu.jframe.event.test.fixture");
        boolean found = registered.stream()
                .anyMatch(c -> c == ScanTargetWrapper.class);
        check("12a. 包扫描发现 @Wrapper 类", found);

        // 扫描注册后事件可正常分发
        reg.dispatch(MoveEvent.class, new MoveEvent("Scan", 1, 0));
        check("12b. 扫描注册的包装类可正常分发",
                ScanTargetWrapper.getInvocations().equals(List.of("Scan@scan")));
    }

    /** 13. EventEngine subscribe / unsubscribe / unsubscribeAll */
    @SuppressWarnings("unchecked")
    private static void testEventEngineSubscribeUnsubscribe() {
        EventEngine engine = new EventEngine();

        EventConsumer<MoveEvent> c1 = event -> true;
        EventConsumer<MoveEvent> c2 = event -> true;

        engine.subscribe(MoveEvent.class, c1);
        engine.subscribe(MoveEvent.class, c2);
        check("13a. subscribe 后消费者数量为 2", consumerCount(engine, MoveEvent.class) == 2);

        engine.unsubscribe(MoveEvent.class, c1);
        check("13b. unsubscribe 移除单个消费者后剩 1", consumerCount(engine, MoveEvent.class) == 1);

        engine.unsubscribeAll(c2);
        check("13c. unsubscribeAll 清空该消费者", consumerCount(engine, MoveEvent.class) == 0);
    }

    // ==================== 工具方法 ====================

    /** 创建隔离的 HandlerRegistry（配套 EventEngine 不绑定 plugin，订阅暂存不触发 Nukkit） */
    private static HandlerRegistry newRegistry() {
        return new HandlerRegistry(new EventEngine());
    }

    /** 通过反射读取 EventEngine 内部消费者列表大小（用于断言订阅管理） */
    @SuppressWarnings("unchecked")
    private static int consumerCount(EventEngine engine, Class<? extends Event> eventType) {
        return countInField(engine, "primaryConsumers", eventType);
    }

    @SuppressWarnings("unchecked")
    private static int tailConsumerCount(EventEngine engine, Class<? extends Event> eventType) {
        return countInField(engine, "tailConsumers", eventType);
    }

    @SuppressWarnings("unchecked")
    private static int countInField(EventEngine engine, String fieldName,
                                    Class<? extends Event> eventType) {
        try {
            Field field = EventEngine.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<Class<? extends Event>, List<EventConsumer<?>>> map =
                    (Map<Class<? extends Event>, List<EventConsumer<?>>>) field.get(engine);
            List<?> list = map.get(eventType);
            return list == null ? 0 : list.size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 通过反射调用 EventEngine.dispatch（private），模拟 Nukkit 事件触发 */
    private static void invokeEngineDispatch(EventEngine engine,
                                             Class<? extends Event> eventType, Event event) {
        try {
            Method dispatch = EventEngine.class.getDeclaredMethod(
                    "dispatch", Class.class, Event.class);
            dispatch.setAccessible(true);
            dispatch.invoke(engine, eventType, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 简易断言：打印 PASS/FAIL 并计数 */
    private static void check(String name, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private EventRoutingTest() {
    }
}
