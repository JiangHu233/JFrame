package io.github.JiangHu.jframe.core.data.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 纠缠值（{@link EntangledValue}）与通道（{@link EntangledChannel}）逻辑测试（纯 JVM 可运行，无外部依赖）。
 *
 * <p>运行方式：直接执行 {@link #main}。
 *
 * <p>覆盖特性一览：
 * <ol>
 *   <li>基础读写 / 工厂 / 链式</li>
 *   <li>通知模式：纠缠后通知其他成员，但不同步其值</li>
 *   <li>纠缠可传递性（a-b-c）</li>
 *   <li>两个纠缠组（通道）合并</li>
 *   <li>解纠缠后不再收发通知</li>
 *   <li>自身监听器触发与事件内容正确性</li>
 *   <li>distinct 去重</li>
 *   <li>setSilently 静默更新</li>
 *   <li>监听器异常隔离</li>
 *   <li>removeListener / clearListeners</li>
 *   <li>partners / isEntangledWith / 多值 entangle</li>
 *   <li>异构纠缠（Integer + String 同通道）</li>
 *   <li>容器突变 mutate（原地修改 + 广播）</li>
 *   <li>角色门控（SOURCE 只发 / SINK 只听，统一收发门控）</li>
 *   <li>通道级监听器 + 两阶段广播顺序</li>
 *   <li>收发开关（对象侧 / 通道侧 setReceiving / setSending）</li>
 *   <li>显式通道 join / add / leave / 名称</li>
 * </ol>
 */
public final class EntangledValueTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("==================== 开始纠缠值测试 ====================");

        // 基础与兼容性
        testBasicReadWrite();
        testNotifyMode();
        testTransitivity();
        testGroupMerge();
        testUnentangle();
        testSelfListenerAndEventContent();
        testDistinct();
        testSetSilently();
        testListenerExceptionIsolation();
        testRemoveAndClearListener();
        testPartnersAndMultiEntangle();

        // 异构 / 通道 / 角色 / 容器
        testHeterogeneousEntangle();
        testMutate();
        testRoleSourceSink();
        testChannelLevelListener();
        testReceiveSendSwitch();
        testExplicitChannelJoin();

        System.out.println("==================== 测试汇总 ====================");
        System.out.println("通过: " + pass + "，失败: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✅");
    }

    // ==================== 基础与兼容性 ====================

    /** 1. 基础读写 / 工厂 / 链式 */
    private static void testBasicReadWrite() {
        var a = EntangledValue.of(1);
        check("1a. of 设置初始值", a.get() == 1);
        check("1b. set 返回自身（链式）", a.set(2) == a);
        check("1c. set 后值更新", a.get() == 2);

        var empty = EntangledValue.<Integer>empty();
        check("1d. empty 值为 null", empty.get() == null);
        check("1e. isPresent 为 false", !empty.isPresent());
        check("1f. getOrDefault 返回默认值", empty.getOrDefault(99) == 99);
        check("1g. of(5).isPresent 为 true", EntangledValue.of(5).isPresent());
    }

    /** 2. 通知模式（默认）：纠缠后通知其他成员，但不同步其值 */
    private static void testNotifyMode() {
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);
        a.entangle(b);

        List<Integer> bSeen = new ArrayList<>();
        b.addListener(e -> bSeen.add(e.newValue()));

        a.set(5);
        check("2a. 通知模式：b 收到新值 5", bSeen.equals(List.of(5)));
        check("2b. 通知模式：b 自身值不变（仍为 2）", b.get() == 2);
        check("2c. 通知模式：a 自身值已更新为 5", a.get() == 5);
        check("2d. a 与 b 已纠缠", a.isEntangled());
    }

    /** 4. 纠缠可传递性（a-b-c） */
    private static void testTransitivity() {
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);
        var c = EntangledValue.of(3);
        a.entangle(b);
        b.entangle(c);

        List<Integer> cSeen = new ArrayList<>();
        c.addListener(e -> cSeen.add(e.newValue()));

        a.set(9);
        check("4a. 可传递：c 收到 a 的通知", cSeen.equals(List.of(9)));
        check("4b. a 与 c 同通道", a.isEntangledWith(c));
    }

    /** 5. 两个纠缠组（通道）合并 */
    private static void testGroupMerge() {
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);
        var c = EntangledValue.of(3);
        var d = EntangledValue.of(4);
        a.entangle(b);
        c.entangle(d);

        List<Integer> dSeen = new ArrayList<>();
        d.addListener(e -> dSeen.add(e.newValue()));

        a.entangle(c); // 合并 {a,b} 与 {c,d}
        a.set(7);
        check("5a. 合并后 d 收到 a 的通知", dSeen.equals(List.of(7)));
        check("5b. 合并后 b 与 d 同通道", b.isEntangledWith(d));
        check("5c. 合并后通道内有 4 个成员", a.partners().size() == 3);
    }

    /** 6. 解纠缠后不再收发通知 */
    private static void testUnentangle() {
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);
        a.entangle(b);

        List<Integer> bSeen = new ArrayList<>();
        b.addListener(e -> bSeen.add(e.newValue()));

        a.unentangle();
        check("6a. 解纠缠后 a.isEntangled 为 false", !a.isEntangled());

        a.set(8);
        check("6b. 解纠缠后 b 不再收到通知", bSeen.isEmpty());
        check("6c. 解纠缠后 a 与 b 不再同通道", !a.isEntangledWith(b));
    }

    /** 7. 自身监听器触发与事件内容正确性（无通道的独立值） */
    private static void testSelfListenerAndEventContent() {
        var a = EntangledValue.of(1);
        List<EntangledEvent<Integer>> evts = new ArrayList<>();
        a.addListener(evts::add);

        a.set(2);
        check("7a. 自身监听器触发一次", evts.size() == 1);
        EntangledEvent<Integer> e = evts.get(0);
        check("7b. 事件 source 是 a", e.source() == a);
        check("7c. 事件 oldValue 为 1", e.oldValue() == 1);
        check("7d. 事件 newValue 为 2", e.newValue() == 2);
    }

    /** 8. distinct 去重 */
    private static void testDistinct() {
        var a = EntangledValue.of(1);
        List<Integer> seen = new ArrayList<>();
        a.addListener(e -> seen.add(e.newValue()));
        a.setDistinct(true);

        a.set(1); // 与当前值相同 → 不通知
        check("8a. distinct：相同值不通知", seen.isEmpty());

        a.set(2); // 不同 → 通知
        check("8b. distinct：不同值通知", seen.equals(List.of(2)));

        a.set(2); // 再次相同 → 不通知
        check("8c. distinct：再次相同值不通知", seen.equals(List.of(2)));
    }

    /** 9. setSilently 静默更新 */
    private static void testSetSilently() {
        var a = EntangledValue.of(1);
        List<Integer> seen = new ArrayList<>();
        a.addListener(e -> seen.add(e.newValue()));

        a.setSilently(5);
        check("9a. setSilently 不触发监听器", seen.isEmpty());
        check("9b. setSilently 值已更新为 5", a.get() == 5);
    }

    /** 10. 监听器异常隔离 */
    private static void testListenerExceptionIsolation() {
        var a = EntangledValue.of(1);
        List<Integer> seen = new ArrayList<>();
        // 第一个监听器抛异常
        a.addListener(e -> {
            throw new RuntimeException("boom");
        });
        // 第二个监听器正常记录
        a.addListener(e -> seen.add(e.newValue()));
        // 静默错误处理器，避免污染输出
        a.setErrorHandler(ex -> { });

        a.set(2);
        check("10. 异常监听器不影响后续监听器", seen.equals(List.of(2)));
    }

    /** 11. removeListener / clearListeners */
    private static void testRemoveAndClearListener() {
        var a = EntangledValue.of(1);
        List<Integer> seen = new ArrayList<>();
        Consumer<EntangledEvent<Integer>> l = e -> seen.add(e.newValue());
        a.addListener(l);

        a.set(2);
        boolean removed = a.removeListener(l);
        a.set(3);
        check("11a. removeListener 返回 true", removed);
        check("11b. removeListener 后不再触发", seen.equals(List.of(2)));

        a.addListener(e -> seen.add(e.newValue()));
        a.addListener(e -> seen.add(e.newValue()));
        a.clearListeners();
        a.set(4);
        check("11c. clearListeners 后不再触发", seen.equals(List.of(2)));
    }

    /** 12. partners / isEntangledWith / 多值 entangle */
    private static void testPartnersAndMultiEntangle() {
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);
        var c = EntangledValue.of(3);
        a.entangle(b, c); // 一次纠缠多个（可变参数）

        check("12a. 多值 entangle 后 a 与 b 同通道", a.isEntangledWith(b));
        check("12b. partners 含 b、c", a.partners().size() == 2
                && a.partners().contains(b) && a.partners().contains(c));
        check("12c. 未纠缠对象 partners 为空", EntangledValue.of(1).partners().isEmpty());
        check("12d. 未纠缠对象 isEntangled 为 false", !EntangledValue.of(1).isEntangled());
    }

    // ==================== 异构 / 通道 / 角色 / 容器 ====================

    /** 14. 异构纠缠（Integer + String 同通道） */
    private static void testHeterogeneousEntangle() {
        var num = EntangledValue.of(1);   // Integer
        var txt = EntangledValue.of("one"); // String
        num.entangle(txt); // 异构纠缠

        List<Object> txtSeen = new ArrayList<>();
        txt.addListener(e -> txtSeen.add(e.newValue()));

        check("14a. 异构纠缠：num 与 txt 同通道", num.isEntangledWith(txt));

        num.set(2);
        check("14b. 异构：txt 收到 num 的通知（值 2）", txtSeen.equals(List.of(2)));
        check("14c. 异构：txt 自身值不变（仍 one）", "one".equals(txt.get()));
        check("14d. 异构：num 自身值已更新为 2", num.get() == 2);
    }

    /** 15. 容器突变 mutate（原地修改 + 广播，通知模式：他人值不变但收到事件） */
    private static void testMutate() {
        var list = EntangledValue.of(new ArrayList<String>());
        var copy = EntangledValue.of(new ArrayList<String>());
        list.entangle(copy); // 通知模式：纠缠但不同步值

        List<Integer> copySizes = new ArrayList<>();
        copy.addListener(e -> copySizes.add(e.newValue().size()));

        list.mutate(l -> l.add("a"));
        check("15a. mutate 后自身含 a", list.get().contains("a"));
        check("15b. 通知模式：copy 自身值不变（仍为空）", copy.get().isEmpty());
        check("15c. mutate 触发监听器（copy 收到 size=1）", copySizes.equals(List.of(1)));

        list.mutate(l -> l.add("b"));
        check("15d. 再次 mutate（copy 收到 size=2）", copySizes.equals(List.of(1, 2)));
    }

    /** 16. 角色门控（SOURCE 只发 / SINK 只听，统一收发门控） */
    private static void testRoleSourceSink() {
        var ch = new EntangledChannel();
        var sensor = EntangledValue.of(0);
        var display = EntangledValue.of(0);
        sensor.joinAsSource(ch);  // 只发（receive=false, send=true）
        display.joinAsSink(ch);   // 只听（receive=true, send=false）

        List<Integer> displaySeen = new ArrayList<>();
        display.addListener(e -> displaySeen.add(e.newValue()));
        List<Integer> sensorSeen = new ArrayList<>();
        sensor.addListener(e -> sensorSeen.add(e.newValue()));

        // SOURCE 发送 → SINK 接收
        sensor.set(5);
        check("16a. SOURCE 发送：SINK display 收到 5", displaySeen.equals(List.of(5)));
        check("16b. sensor 角色 SOURCE", sensor.getRole() == Role.SOURCE);
        check("16c. display 角色 SINK", display.getRole() == Role.SINK);

        // SOURCE 不接收：sensor 自身 set 也不触发自己的本地监听器（统一收发门控）
        check("16d. SOURCE 自身 set 不触发本地监听器（receive=false）", sensorSeen.isEmpty());

        // SINK 不广播：display.set 不通知任何人（含自身）
        display.set(9);
        check("16e. SINK set 不广播（sensorSeen 仍为空）", sensorSeen.isEmpty());
        check("16f. SINK set 仍更新自身值（display=9）", display.get() == 9);
    }

    /** 17. 通道级监听器 + 两阶段广播顺序 */
    private static void testChannelLevelListener() {
        var ch = new EntangledChannel();
        var a = EntangledValue.of(1).join(ch);
        var b = EntangledValue.of(1).join(ch);

        List<String> order = new ArrayList<>();
        ch.subscribe(e -> order.add("channel:" + e.newValue()));
        a.addListener(e -> order.add("a:" + e.newValue()));
        b.addListener(e -> order.add("b:" + e.newValue()));

        a.set(7); // 阶段① channel 先，阶段② 统一遍历成员（a、b 均为 BOTH）
        check("17a. 通道级监听先于成员级", order.get(0).equals("channel:7"));
        check("17b. 触发者 a 收到通知", order.contains("a:7"));
        check("17c. 其他成员 b 收到通知", order.contains("b:7"));
    }

    /** 18. 收发开关（对象侧 / 通道侧 setReceiving / setSending） */
    private static void testReceiveSendSwitch() {
        var ch = new EntangledChannel();
        var a = EntangledValue.of(1).join(ch);
        var b = EntangledValue.of(1).join(ch);

        List<Integer> bSeen = new ArrayList<>();
        b.addListener(e -> bSeen.add(e.newValue()));

        a.set(2);
        check("18a. 初始 BOTH：b 收到", bSeen.equals(List.of(2)));

        // 对象侧关闭 b 的接收
        b.setReceiving(false);
        a.set(3);
        check("18b. 对象侧 setReceiving(false)：b 不收到", bSeen.equals(List.of(2)));

        // 通道侧重新开启
        ch.setReceiving(b, true);
        a.set(4);
        check("18c. 通道侧 setReceiving(true)：b 恢复接收", bSeen.equals(List.of(2, 4)));

        // 关闭 a 的发送：a.set 不产生任何广播（含自身也不收到）
        a.setSending(false);
        List<Integer> aSeen = new ArrayList<>();
        a.addListener(e -> aSeen.add(e.newValue()));
        a.set(5);
        check("18d. 对象侧 setSending(false)：a.set 不广播，b 不收到", bSeen.equals(List.of(2, 4)));
        check("18e. setSending(false) 时 a 自身也不收到（统一门控）", aSeen.isEmpty());
    }

    /** 19. 显式通道 join / add / leave / 名称 */
    private static void testExplicitChannelJoin() {
        var ch = new EntangledChannel("test");
        var a = EntangledValue.of(1);
        var b = EntangledValue.of(2);

        // 对象侧 join 与通道侧 add 等价
        a.join(ch);
        ch.add(b);
        check("19a. 对象侧 join 与通道侧 add 都加入通道", ch.contains(a) && ch.contains(b));
        check("19b. 通道 size=2", ch.size() == 2);
        check("19c. 通道名称", "test".equals(ch.getName()));
        check("19d. a 与 b 同通道", a.isEntangledWith(b));

        List<Integer> bSeen = new ArrayList<>();
        b.addListener(e -> bSeen.add(e.newValue()));
        a.set(5);
        check("19e. 显式通道：b 收到 a 的通知", bSeen.equals(List.of(5)));

        // leave
        a.leave();
        check("19f. leave 后不在通道", !ch.contains(a));
        a.set(6);
        check("19g. leave 后 b 不收到", bSeen.equals(List.of(5)));
    }

    // ==================== 工具方法 ====================

    /** 简易断言：打印 PASS/FAIL 并计数。 */
    private static void check(String name, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private EntangledValueTest() {
    }
}
