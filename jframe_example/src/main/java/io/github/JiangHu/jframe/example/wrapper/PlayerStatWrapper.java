package io.github.JiangHu.jframe.example.wrapper;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例 1：每个玩家独立的统计实例（对象级处理器）。
 * <p>
 * 演示要点：
 * <ul>
 *   <li>{@link KeyExtractor} —— 从 4 种事件中提取 {@link Player} 身份（同一事件类型多提取器 / 多事件类型）</li>
 *   <li>{@link InstanceProvider} —— 自定义工厂，实例存于<b>可被外部访问</b>的静态注册表
 *       （表单界面可读取实时统计）</li>
 *   <li>{@link EventHandler} —— 多个处理器共享同一个玩家实例</li>
 *   <li>SpEL {@code condition} —— 仅对 OP 玩家触发的处理器</li>
 *   <li>玩家退出时清理实例</li>
 * </ul>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 玩家移动 / 聊天 / 进服 / 退服 → 对应事件触发
 *     ↓
 * ① @KeyExtractor 从事件提取 Player
 *     ↓
 * ② @InstanceProvider provide(player) → 从注册表获取（或首次创建）该玩家的统计实例
 *     ↓
 * ③ 调用匹配的 @EventHandler 方法（每个玩家只收到属于自己的事件）
 * </pre>
 */
@Wrapper
public class PlayerStatWrapper {

    /** 玩家 -> 统计实例。使用自定义 @InstanceProvider，因此需自行维护此表。 */
    private static final Map<Player, PlayerStatWrapper> REGISTRY = new ConcurrentHashMap<>();

    private final Player player;
    private int moveCount = 0;
    private int chatCount = 0;

    public PlayerStatWrapper(Player player) {
        this.player = player;
    }

    /** 供表单等外部代码读取某玩家的统计实例（玩家尚未触发任何事件时可能为 null）。 */
    public static PlayerStatWrapper get(Player player) {
        return REGISTRY.get(player);
    }

    /** 玩家退出时移除其实例，避免内存泄漏。 */
    public static void remove(Player player) {
        REGISTRY.remove(player);
    }

    // ==================== 身份提取（必需，static） ====================

    @KeyExtractor
    public static Player extract(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    @KeyExtractor
    public static Player extract(PlayerChatEvent event) {
        return event.getPlayer();
    }

    @KeyExtractor
    public static Player extract(PlayerJoinEvent event) {
        return event.getPlayer();
    }

    @KeyExtractor
    public static Player extract(PlayerQuitEvent event) {
        return event.getPlayer();
    }

    // ==================== 实例工厂（static） ====================

    /**
     * 自定义工厂：按 Player 查找或创建统计实例。
     * <p>
     * 因为使用了自定义 @InstanceProvider，{@code EventAPI.evict()} 不再生效，
     * 需在玩家退出时通过 {@link #remove(Player)} 自行清理。
     */
    @InstanceProvider
    public static PlayerStatWrapper provide(Player player) {
        return REGISTRY.computeIfAbsent(player, PlayerStatWrapper::new);
    }

    // ==================== 事件处理（实例方法） ====================

    @EventHandler
    @EventRoute
    public void onJoin(PlayerJoinEvent event) {
        player.sendMessage("§a欢迎来到 JFrame 示例服务器！");
    }

    /**
     * SpEL 条件演示：仅当玩家是 OP 时触发（{@code #event.player.op} 等价于 {@code player.isOp()}）。
     * <p>
     * 与上面的 {@link #onJoin} 处理同一事件类型，二者都会被分发（同优先级、非独占）。
     */
    @EventHandler
    @EventRoute(condition = "#event.player.op")
    public void onOpJoin(PlayerJoinEvent event) {
        player.sendMessage("§6[管理员] 欢迎管理员上线！");
    }

    @EventHandler
    @EventRoute
    public void onMove(PlayerMoveEvent event) {
        moveCount++;
        // 每 100 次移动给一次反馈（PlayerMoveEvent 触发频繁，避免刷屏）
        if (moveCount % 100 == 0) {
            player.sendMessage("§7你已经累计移动 §e" + moveCount + " §7次。");
        }
    }

    @EventHandler
    @EventRoute
    public void onChat(PlayerChatEvent event) {
        chatCount++;
    }

    @EventHandler
    @EventRoute
    public void onQuit(PlayerQuitEvent event) {
        // 自定义 @InstanceProvider 需自行清理注册表
        remove(player);
    }

    // ==================== Getter（供表单读取） ====================

    public int getMoveCount() {
        return moveCount;
    }

    public int getChatCount() {
        return chatCount;
    }
}
