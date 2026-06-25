package example;

import cn.nukkit.event.EventPriority;
import cn.nukkit.event.player.PlayerChatEvent;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import org.springframework.stereotype.Component;

/**
 * 示例 1：全局处理器（Spring 单例 Bean）。
 * <p>
 * 演示三种特性：
 * <ol>
 *   <li>事件类型自动推断（不填 value，从方法参数推断）</li>
 *   <li>SpEL 条件过滤（condition）</li>
 *   <li>filter 方法引用（复杂条件写在 Java 方法里）</li>
 * </ol>
 */
@Component
public class GlobalChatLogger {

    /**
     * 自动推断：不填 value，框架从参数 PlayerChatEvent 推断事件类型。
     */
    @NukkitEvent
    public void onChat(PlayerChatEvent event) {
        System.out.println("[聊天] " + event.getPlayer().getName() + ": " + event.getMessage());
    }

    /**
     * SpEL 条件：只在创造模式玩家聊天时记录。
     */
    @NukkitEvent(condition = "#event.player.gamemode == 1")
    public void onCreativeChat(PlayerChatEvent event) {
        System.out.println("[创造模式聊天] " + event.getPlayer().getName() + ": " + event.getMessage());
    }

    /**
     * filter 方法引用：复杂判断逻辑写在 Java 方法里，享受 IDE 补全和编译检查。
     * 只有 isSpam(event) 返回 false 时才会执行 onNormalChat。
     */
    @NukkitEvent(filter = "isNotSpam")
    public void onNormalChat(PlayerChatEvent event) {
        System.out.println("[正常聊天] " + event.getPlayer().getName() + ": " + event.getMessage());
    }

    /**
     * 筛选方法：判断是否不是垃圾消息。
     * <p>
     * 可以写任意复杂的逻辑——多条件组合、调用外部服务等。
     */
    private boolean isNotSpam(PlayerChatEvent event) {
        String msg = event.getMessage();
        // 例子：消息太短或全是重复字符视为垃圾消息
        if (msg.length() < 2) {
            return false;
        }
        // 检查是否全是同一个字符重复（如 "aaaaaa"）
        char first = msg.charAt(0);
        boolean allSame = true;
        for (char c : msg.toCharArray()) {
            if (c != first) {
                allSame = false;
                break;
            }
        }
        return !allSame;
    }

    /**
     * 优先级示例：HIGH 优先级，在其他 NORMAL 处理器之前执行。
     */
    @NukkitEvent(priority = EventPriority.HIGH)
    public void onChatHighPriority(PlayerChatEvent event) {
        // HIGH 优先级会比上面的 NORMAL 处理器先执行
    }
}
