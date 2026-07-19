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
import io.github.JiangHu.jframe.event.test.fixture.MutualExclusionWrapper;
import io.github.JiangHu.jframe.event.test.fixture.NoExtractorWrapper;
import io.github.JiangHu.jframe.event.test.fixture.PriorityWrapper;
import io.github.JiangHu.jframe.event.test.fixture.ScanTargetWrapper;
import io.github.JiangHu.jframe.event.test.fixture.StatWrapper;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.ChatEvent;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.CombatEvent;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.MoveEvent;

import java.lang.reflect.Field;
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
        testNoExtractorRejected();
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

        // 攻击者 Alice + 受害者 Bob → 两个槽位都匹配 → 两个实例被通知
        reg.dispatch(CombatEvent.class, new CombatEvent("Alice", "Bob", 10));
        check("7a. 双槽位匹配通知两个身份",
                MultiSlotWrapper.getNotified().equals(List.of("Alice", "Bob")));

        // 受害者为 null → 仅攻击者槽位匹配
        reg.dispatch(CombatEvent.class, new CombatEvent("Alice", null, 5));
        check("7b. null 槽位跳过，仅攻击者通知",
                MultiSlotWrapper.getNotified().equals(List.of("Alice", "Bob", "Alice")));
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

    /** 10. 无 @KeyExtractor 注册被拒绝 */
    private static void testNoExtractorRejected() {
        HandlerRegistry reg = newRegistry();
        NoExtractorWrapper.reset();
        // 注册不应抛异常（内部记录警告并返回）
        reg.register(NoExtractorWrapper.class);

        // 分发后处理器不应被调用
        reg.dispatch(MoveEvent.class, new MoveEvent("X", 1, 0));
        check("10. 无 @KeyExtractor 的类注册被拒绝，处理器不执行",
                !NoExtractorWrapper.isInvoked());
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
        try {
            Field consumersField = EventEngine.class.getDeclaredField("consumers");
            consumersField.setAccessible(true);
            Map<Class<? extends Event>, List<EventConsumer<?>>> map =
                    (Map<Class<? extends Event>, List<EventConsumer<?>>>) consumersField.get(engine);
            List<?> list = map.get(eventType);
            return list == null ? 0 : list.size();
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
