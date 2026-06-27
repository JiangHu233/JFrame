package io.github.JiangHu.jframe.example.wrapper;

import cn.nukkit.event.EventPriority;
import cn.nukkit.event.player.PlayerChatEvent;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

import java.util.Locale;
import java.util.Set;

/**
 * 示例 2：全局聊天审核（单例处理器）。
 * <p>
 * 演示要点：
 * <ul>
 *   <li><b>全局单例模式</b>：{@link KeyExtractor} 返回恒定身份，
 *       {@link InstanceProvider} 始终返回同一个实例 —— 所有聊天事件路由到同一对象</li>
 *   <li><b>HIGH 优先级 + 独占</b>：检测到违禁词时取消事件，
 *       并阻止更低优先级（NORMAL/LOW…）的处理器执行</li>
 *   <li><b>filter 方法引用</b>：复杂条件用 Java 方法实现（编译期类型检查）</li>
 * </ul>
 *
 * <h3>与示例 1 的联动</h3>
 * {@link PlayerStatWrapper#onChat} 也是 NORMAL 优先级的聊天处理器。当玩家发送违禁词时：
 * <pre>
 * ChatGuardWrapper.onBannedChat (HIGH, exclusive) → 取消事件并声明独占
 *     ↓ 独占生效
 * PlayerStatWrapper.onChat (NORMAL) → 被跳过，违禁消息不计入统计
 * </pre>
 * 正常消息则两个处理器都会执行（HIGH 的 filter 不通过，不声明独占）。
 */
@Wrapper
public class ChatGuardWrapper {

    /** 全局唯一实例。 */
    private static final ChatGuardWrapper INSTANCE = new ChatGuardWrapper();

    /** 违禁词表（统一小写比较）。 */
    private static final Set<String> BANNED_WORDS = Set.of("fuck", "shit", "idiot");

    @KeyExtractor
    public static Object extract(PlayerChatEvent event) {
        // 恒定身份：所有 PlayerChatEvent 都路由到同一个实例
        return Boolean.TRUE;
    }

    @InstanceProvider
    public static ChatGuardWrapper provider(Object identity) {
        // 工厂始终返回固定单例
        return INSTANCE;
    }

    /**
     * HIGH 优先级 + 独占：检测到违禁词时取消事件，
     * 并阻止所有更低优先级的处理器执行（如统计计数）。
     */
    @EventHandler(priority = EventPriority.HIGH, exclusive = true)
    @EventRoute(filter = "containsBannedWord")
    public void onBannedChat(PlayerChatEvent event) {
        event.setCancelled(true);
        event.getPlayer().sendMessage("§c请文明发言！检测到违禁词，消息已被拦截。");
    }

    /**
     * filter 方法：判断消息是否包含违禁词。
     * <p>
     * 要求：单参数（兼容事件类型）、返回 boolean，可以是 private。
     * 相比 SpEL {@code condition}，filter 拥有 IDE 补全与编译期检查。
     */
    private boolean containsBannedWord(PlayerChatEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return BANNED_WORDS.stream().anyMatch(lower::contains);
    }
}
