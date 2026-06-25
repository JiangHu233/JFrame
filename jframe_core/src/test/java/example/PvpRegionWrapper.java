package example;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import io.github.JiangHu.jframe.core.event.routing.ObjectEventRouter;
import io.github.JiangHu.jframe.core.event.routing.RoutingSpec;

/**
 * 示例 4：PvP 区域 Wrapper（自定义 Key 提取器 + 坐标判断）。
 * <p>
 * 场景：一个由两个坐标点定义的矩形区域（如出生点 PvP 竞技场），
 * 监听该区域内玩家之间的攻击行为。
 * <p>
 * <b>核心难点</b>：{@code EntityDamageByEntityEvent} 的全局提取器按 {@code Entity} 路由，
 * 但我们需要按<b>区域 ID</b> 路由——只有发生在该区域内的攻击才触发。
 * <p>
 * <b>解决方案</b>：使用 {@link RoutingSpec} 指定自定义提取器，
 * 从事件中提取受害者坐标，判断是否在区域内，在区域内则返回区域 ID 作为 Key。
 *
 * <h3>注册方式</h3>
 * <pre>{@code
 * // 定义区域：出生点 PvP 竞技场 (10,60,10) ~ (30,80,30)
 * PvpRegionWrapper arena = PvpRegionWrapper.create(
 *     "spawn_arena", 10, 60, 10, 30, 80, 30, router);
 *
 * // 内部调用：
 * // RoutingSpec spec = RoutingSpec.create()
 * //     .extract(EntityDamageByEntityEvent.class, event -> {
 * //         Position pos = event.getEntity().getPosition();
 * //         return arena.isInRegion(pos) ? "spawn_arena" : null;
 * //     });
 * // router.register(arena, "spawn_arena", spec);
 * }</pre>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 实体被攻击 → EntityDamageByEntityEvent 触发
 *     ↓
 * ObjectEventRouter.dispatch()
 *     ↓
 * ③ 自定义提取器：
 *     1. 取受害者坐标
 *     2. 判断是否在区域内
 *     3. 在区域内 → 返回区域 ID "spawn_arena"
 *        不在区域内 → 返回 null（不路由）
 *     ↓
 * 比对 Key：提取的区域 ID == 注册时的 "spawn_arena"？
 *     ↓ 匹配
 * filter 检查：攻击者和受害者都是玩家？（确保是 PvP）
 *     ↓ 通过
 * 调用 onPvpAttack() → 记录 / 广播 / 处理 PvP 逻辑
 * </pre>
 *
 * @see RoutingSpec
 * @see ObjectEventRouter#register(Object, Object, RoutingSpec)
 */
public class PvpRegionWrapper {

    private final String regionId;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final ObjectEventRouter router;

    public PvpRegionWrapper(String regionId, int x1, int y1, int z1,
                            int x2, int y2, int z2, ObjectEventRouter router) {
        this.regionId = regionId;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.router = router;
    }

    /**
     * 创建并注册 PvP 区域 Wrapper。
     * <p>
     * 使用 {@link RoutingSpec} 将 {@code EntityDamageByEntityEvent} 的 Key 提取器
     * 从默认的 {@code getEntity()}（受害者实体）改为<b>区域 ID 字符串</b>。
     * 提取器内部判断受害者坐标是否在区域内。
     *
     * @param regionId 区域唯一标识（如 "spawn_arena"）
     * @param x1,y1,z1 区域对角点 1
     * @param x2,y2,z2 区域对角点 2
     * @param router   事件路由器
     * @return 已注册的 Wrapper 实例
     */
    public static PvpRegionWrapper create(String regionId, int x1, int y1, int z1,
                                          int x2, int y2, int z2, ObjectEventRouter router) {
        PvpRegionWrapper wrapper = new PvpRegionWrapper(regionId, x1, y1, z1, x2, y2, z2, router);

        // ★ 核心：自定义提取器，按区域 ID 路由而非 Entity
        // lambda 捕获 wrapper 实例，调用其 isInRegion() 判断坐标
        RoutingSpec spec = RoutingSpec.create()
                .extract(EntityDamageByEntityEvent.class, event -> {
                    Position pos = event.getEntity().getPosition();
                    return wrapper.isInRegion(pos) ? wrapper.regionId : null;
                });

        // Key = regionId（字符串），提取器返回的区域 ID 必须与此匹配
        router.register(wrapper, regionId, spec);
        return wrapper;
    }

    /**
     * 注销 Wrapper，停止监听。
     */
    public void destroy() {
        router.unregister(this);
    }

    /**
     * 判断坐标是否在区域内。
     *
     * @param pos 任意位置
     * @return 在区域内返回 true
     */
    public boolean isInRegion(Position pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    // ==================== 事件处理方法 ====================

    /**
     * 当该区域内发生攻击时触发。
     * <p>
     * 由于使用了自定义提取器按区域 ID 路由，<b>只有</b>发生在本区域内的
     * {@code EntityDamageByEntityEvent} 才会到达这里。
     * <p>
     * filter 进一步过滤：确保攻击者和受害者都是玩家（真正的 PvP）。
     */
    @NukkitEvent(filter = "isPlayerVsPlayer")
    public void onPvpAttack(EntityDamageByEntityEvent event) {
        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        System.out.println("[PvP区域 " + regionId + "] "
                + attacker.getName() + " 攻击了 " + victim.getName()
                + "，伤害: " + event.getDamage());

        // 示例逻辑：广播 PvP 信息
        broadcastPvp(attacker.getName(), victim.getName(), event.getDamage());
    }

    /**
     * 筛选方法：判断是否是玩家攻击玩家（而非怪物攻击等）。
     */
    private boolean isPlayerVsPlayer(EntityDamageByEntityEvent event) {
        return event.getDamager() instanceof Player
                && event.getEntity() instanceof Player;
    }

    // ==================== 业务逻辑（示意） ====================

    private void broadcastPvp(String attacker, String victim, double damage) {
        // 向区域内所有玩家广播（示意）
        System.out.println("[PvP区域 " + regionId + "] §c" + attacker
                + " §f→ §c" + victim + " §7(-" + damage + " HP)");
    }
}
