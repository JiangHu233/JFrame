package io.github.JiangHu.jframe.event.test.fixture;

import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.test.fixture.TestEvents.CombatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 多槽位 @KeyExtractor 测试（OR 语义）。
 * <p>
 * 同一事件类型 {@link CombatEvent} 声明两个提取器：攻击者槽位 / 受害者槽位。
 * 框架逐一尝试，每个非 null 身份都会路由到对应实例并触发处理器。
 * <p>
 * 验证点：
 * <ul>
 *   <li>事件含攻击者 + 受害者 → 两个不同实例都被通知（2 次调用）</li>
 *   <li>受害者为 null（提取器返回 null）→ 仅攻击者实例被通知（1 次调用）</li>
 * </ul>
 */
public class MultiSlotWrapper {

    /** 记录被通知的身份（按到达顺序） */
    private static final List<String> NOTIFIED = new ArrayList<>();

    private final String identity;

    public MultiSlotWrapper(String identity) {
        this.identity = identity;
    }

    /** 槽位1：作为攻击者 */
    @KeyExtractor
    public static String asAttacker(CombatEvent event) {
        return event.getAttacker();
    }

    /** 槽位2：作为受害者（可能为 null → 跳过此槽位） */
    @KeyExtractor
    public static String asVictim(CombatEvent event) {
        return event.getVictim();
    }

    @EventRoute
    @EventHandler
    public void onCombat(CombatEvent event) {
        NOTIFIED.add(identity);
    }

    public static List<String> getNotified() {
        return NOTIFIED;
    }

    public static void reset() {
        NOTIFIED.clear();
    }
}
