package example;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import io.github.JiangHu.jframe.core.event.routing.ObjectEventRouter;

/**
 * 示例 2：对象级处理器（包装类）。
 * <p>
 * 每个玩家拥有独立的 {@code PlayerWrapper} 实例，只接收属于该玩家的事件。
 * 通过 {@code ObjectEventRouter.register(wrapper, player)} 绑定到 Player 对象。
 * <p>
 * <b>注意：此类不是 Spring Bean</b>（不加 {@code @Component}），
 * 而是在玩家上线时手动 new 出来并注册。
 * <p>
 * 演示：事件类型自动推断 + filter 方法引用。
 */
public class PlayerWrapper {

    private final Player player;
    private final ObjectEventRouter router;

    public PlayerWrapper(Player player, ObjectEventRouter router) {
        this.player = player;
        this.router = router;
    }

    /**
     * 玩家上线时调用：创建包装类并注册到路由器。
     */
    public static PlayerWrapper onJoin(Player player, ObjectEventRouter router) {
        PlayerWrapper wrapper = new PlayerWrapper(player, router);
        // Key = Player 对象，框架会从 PlayerMoveEvent 中提取 Player 并匹配
        router.register(wrapper, player);
        return wrapper;
    }

    /**
     * 玩家下线时调用：注销包装类，释放所有事件监听。
     */
    public void onQuit() {
        router.unregister(this);
    }

    // ==================== 事件处理方法 ====================

    /**
     * 自动推断事件类型（不填 value），只收到 this.player 的移动事件。
     */
    @NukkitEvent
    public void onMove(PlayerMoveEvent event) {
        player.sendMessage("§a你移动到了 " + event.getTo());
    }

    /**
     * filter 方法引用：只在右键点击方块 + 创造模式时触发。
     * 复杂判断逻辑写在 isRightClickBlockInCreative() 里。
     */
    @NukkitEvent(filter = "isRightClickBlockInCreative")
    public void onRightClickBlock(PlayerInteractEvent event) {
        player.sendMessage("§b你右键点击了方块！");
    }

    /**
     * 筛选方法：判断是否是"创造模式下右键点击方块"。
     * <p>
     * 可以访问包装类的实例字段（this.player），编写任意复杂的逻辑。
     */
    private boolean isRightClickBlockInCreative(PlayerInteractEvent event) {
        return event.getAction().name().equals("RIGHT_CLICK_BLOCK")
                && player.getGamemode() == 1;  // 1 = 创造模式
    }

    /**
     * 监听自己退出游戏的事件，自动清理。
     */
    @NukkitEvent
    public void onSelfQuit(PlayerQuitEvent event) {
        onQuit();
        System.out.println("[PlayerWrapper] " + player.getName() + " 已清理事件监听");
    }
}
