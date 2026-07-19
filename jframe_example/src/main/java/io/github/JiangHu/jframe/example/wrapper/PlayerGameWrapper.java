package io.github.JiangHu.jframe.example.wrapper;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerJumpEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.event.annotation.*;

/**
 * 示例：玩家游戏行为增强（玩家级 Wrapper）。
 * <p>
 * 演示两个玩法：
 * <ul>
 *   <li><b>大跳</b> —— 玩家跳跃时沿朝向获得向前的额外动量</li>
 *   <li><b>强化击退</b> —— 玩家攻击其他实体时，水平击退 ×2、垂直击退 ×3</li>
 * </ul>
 */
@Wrapper
public class PlayerGameWrapper {

    /** 大跳：水平运动速度放大倍数（当前水平动量 × 该值）。 */
    private static final double HORIZONTAL_SCALE = 1.1;

    /** 大跳：固定的垂直起跳速度（方块/tick，Nukkit 默认约 0.42）。 */
    private static final double JUMP_Y_VELOCITY = 0.5;

    private final Player player;

    /** 追踪的水平移动速度（每 tick 位移）。Bedrock 客户端权威移动下 motionX/Z 始终为 0，需自行计算。 */
    private double trackedSpeedX = 0;
    private double trackedSpeedZ = 0;

    public PlayerGameWrapper(Player player) {
        this.player = player;
    }

    // ==================== 实例工厂（static） ====================


    // ==================== 身份提取（必需，static） ====================

    @KeyExtractor
    public static Player extract(PlayerJumpEvent event) {
        return event.getPlayer();
    }

    @KeyExtractor
    public static Player extract(PlayerMoveEvent event) {
        return event.getPlayer();
    }

    /**
     * 从伤害事件中提取攻击者（Player）。
     * <p>
     * 参数使用父类 {@link EntityDamageEvent} 而非 {@link EntityDamageByEntityEvent}：
     * Nukkit 事件多态下，注册监听子类时父类事件（如摔落伤害）也会通知该监听器，
     * 若 extractor 参数为子类则反射调用时 {@code argument type mismatch}。
     * <p>
     * 当事件不是 ByEntity 或 damager 不是 Player 时返回 {@code null}，
     * 框架 {@code HandlerRegistry} 会在 {@code key == null} 时自动跳过。
     */
    @KeyExtractor
    public static Player extract(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            return damager instanceof Player ? (Player) damager : null;
        }
        return null;
    }

    // ==================== Filter（Java 方法判别，替代 SpEL instanceof） ====================

    /**
     * 仅当攻击者是 Player 时才处理此事件。
     * <p>
     * 使用 {@code @EventRoute(filter = ...)} 而非 SpEL {@code condition}，
     * 因为 SpEL 的 {@code instanceof} 无法直接引用简单类名 {@code Player}。
     */
    public boolean isPlayerAttack(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent
                && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player;
    }

    // ==================== 事件处理（实例方法） ====================

    /**
     * 大跳：玩家起跳时按比例放大水平运动速度，垂直速度设为固定值。
     * <p>
     * 必须使用 {@link Player#setMotion} 而非 {@code addMotion}：
     * {@code addMotion} 只发包不修改服务端 {@code motionX/Y/Z} 字段，
     * 下一 tick 会被覆盖；{@code setMotion} 同时更新字段 + 广播包。
     */
    /**
     * 追踪水平移动速度：通过 PlayerMoveEvent 的位移差计算每 tick 水平速度。
     * <p>
     * Bedrock 客户端权威移动下，{@link Player#getMotion()} 的 motionX/Z 始终为 0，
     * 无法直接读取行走/奔跑速度，因此需自行追踪。
     */
    @EventHandler
    @EventRoute
    public void onTrackMove(PlayerMoveEvent event) {
        trackedSpeedX = event.getTo().x - event.getFrom().x;
        trackedSpeedZ = event.getTo().z - event.getFrom().z;
    }

    @EventHandler
    @EventRoute
    public void onJump(PlayerJumpEvent event) {
        // 水平方向按比例放大追踪到的移动速度，垂直方向设为固定值
        player.setMotion(new Vector3(
                trackedSpeedX * HORIZONTAL_SCALE,
                JUMP_Y_VELOCITY,
                trackedSpeedZ * HORIZONTAL_SCALE
        ));
    }

    /**
     * 强化击退：水平 ×2、垂直 ×3。
     * <p>
     * Nukkit 击退流程：{@link EntityDamageByEntityEvent} 结束后，
     * {@code EntityLiving.knockBack()} 读取事件的 {@code knockBack} 参数计算位移。
     * <p>
     * 实现策略：
     * <ol>
     *   <li>将 {@code knockBack} 设为原值 ×2 → 水平 ×2、垂直 ×2</li>
     *   <li>延迟 1 tick（等 {@code knockBack()} 执行完毕）再用 {@code setMotion}
     *       追加原值大小的垂直动量 → 垂直 ×3</li>
     * </ol>
     */
    @EventHandler
    @EventRoute(filter = "isPlayerAttack")
    public void onDamaged(EntityDamageEvent event) {
        // filter 已确保是 Player 发起的 ByEntity 攻击，转型安全
        EntityDamageByEntityEvent byEntity = (EntityDamageByEntityEvent) event;
        float baseKnockBack = byEntity.getKnockBack();

        // ① 水平击退 ×2（knockBack 参数同时作用于水平和垂直，此时垂直也变为 ×2）
        byEntity.setKnockBack(baseKnockBack * 2);

        // ② 垂直击退额外 +1 倍（总计 ×3），需在 knockBack() 执行后的下一 tick 追加
        //    用 setMotion 而非 addMotion：addMotion 不累加当前 motion，会覆盖
        Entity victim = event.getEntity();
        player.getServer().getScheduler().scheduleDelayedTask(
                () -> victim.setMotion(new Vector3(
                        victim.motionX,
                        victim.motionY + baseKnockBack,
                        victim.motionZ
                )),
                1
        );
    }
}
