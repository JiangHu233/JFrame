package io.github.JiangHu.jframe.ai.core.combat;

import cn.nukkit.entity.Entity;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗行为执行器：封装游戏 AI 常见的战斗动作。
 * <p>
 * 提供四种核心战斗能力：
 * <ul>
 *   <li>{@link #meleeAttack} —— 近战攻击：对目标施加 {@link EntityDamageEvent} 伤害</li>
 *   <li>{@link #shootArrow} —— 射箭：创建箭抛射物并赋予朝向目标的速度（含重力补偿与散布）</li>
 *   <li>{@link #throwProjectile} —— 投掷抛射物：通用版，支持雪球/药水/经验瓶等任意已注册抛射物</li>
 *   <li>{@link #useItemOn} / {@link #useItemOnSelf} —— 使用物品：对目标或自身使用物品（药水、命名牌等）</li>
 * </ul>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>所有方法均为<b>无状态</b>，仅依赖传入实体与世界状态，线程安全（随机数使用
 *       {@link ThreadLocalRandom}）。</li>
 *   <li>攻击/射击前会自动令执行者面向目标（设置 {@code yaw}），使动作表现自然。</li>
 *   <li>射箭与投掷采用<b>经验性重力补偿</b>：根据距离给方向向量一个向上分量，使远距离
 *       也能大致命中；{@code inaccuracy} 参数控制散布程度，模拟 AI 的不精确瞄准。</li>
 *   <li>抛射物通过 {@link Entity#createEntity(String, cn.nukkit.level.Position, Object...)}
 *       创建，自动处理区块与 NBT 初始化，兼容所有已注册的抛射物类型。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 近战
 * combat.meleeAttack(zombie, player, 4.0f);
 * // 射箭（速度 1.5，散布 0.3）
 * combat.shootArrow(skeleton, player, 1.5, 0.3);
 * // 投掷药水
 * combat.throwProjectile(witch, "SplashPotion", player, 1.2, 0.2);
 * // 对友军使用治疗物品
 * combat.useItemOn(healer, ally, healingItem);
 * }</pre>
 */
public class CombatActions {

    /** 近似眼部高度（相对脚部，用于发射点与视线计算） */
    private static final double EYE_HEIGHT = 1.5;
    /** 默认箭速度 */
    public static final double DEFAULT_ARROW_SPEED = 1.5;
    /** 默认散布（不精确度） */
    public static final double DEFAULT_INACCURACY = 0.2;
    /** 箭实体注册名 */
    public static final String ARROW_TYPE = "Arrow";
    /** 抛射物重力（Nukkit 箭/雪球约 0.05/tick），用于物理弹道补偿 */
    static final double PROJECTILE_GRAVITY = 0.05;
    /** 瞄准点相对目标脚部的高度（躯干中心，避免箭飞过目标头顶） */
    private static final double TARGET_AIM_HEIGHT = 1.0;

    /**
     * 近战攻击：令 {@code attacker} 面向 {@code target} 并对其施加近战伤害。
     *
     * @param attacker 攻击者
     * @param target   目标
     * @param damage   伤害值（半心）
     * @return true 表示伤害成功施加（未被事件取消）
     */
    public boolean meleeAttack(Entity attacker, Entity target, float damage) {
        if (attacker == null || target == null || target.closed || damage <= 0) {
            return false;
        }
        faceTo(attacker, target);
        EntityDamageEvent event = new EntityDamageEvent(
                attacker, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage);
        return target.attack(event);
    }

    /**
     * 射箭：以默认速度与散布向 {@code target} 射出一支箭。
     *
     * @param shooter 射手
     * @param target  目标
     * @return 生成的箭实体；失败返回 {@code null}
     */
    public Entity shootArrow(Entity shooter, Entity target) {
        return shootArrow(shooter, target, DEFAULT_ARROW_SPEED, DEFAULT_INACCURACY);
    }

    /**
     * 射箭：向 {@code target} 射出一支箭，可指定速度与散布。
     *
     * @param shooter    射手
     * @param target     目标
     * @param speed      初速度（方块/tick）
     * @param inaccuracy 散布程度（0=精准，越大越偏）
     * @return 生成的箭实体；失败返回 {@code null}
     */
    public Entity shootArrow(Entity shooter, Entity target, double speed, double inaccuracy) {
        return throwProjectile(shooter, ARROW_TYPE, target, speed, inaccuracy);
    }

    /**
     * 投掷抛射物：向 {@code target} 发射指定类型的抛射物。
     *
     * @param shooter        投掷者
     * @param projectileType 抛射物注册名（如 {@code "Arrow"}、{@code "Snowball"}、
     *                       {@code "SplashPotion"}、{@code "ThrownExpBottle"} 等）
     * @param target         目标
     * @param speed          初速度（方块/tick）
     * @param inaccuracy     散布程度（0=精准，越大越偏）
     * @return 生成的抛射物实体；失败返回 {@code null}
     */
    public Entity throwProjectile(Entity shooter, String projectileType, Entity target,
                                  double speed, double inaccuracy) {
        if (shooter == null || target == null || shooter.closed || target.closed) {
            return null;
        }
        if (projectileType == null || projectileType.isEmpty() || speed <= 0) {
            return null;
        }
        faceTo(shooter, target);
        // 发射点：射手眼部位置
        cn.nukkit.level.Position launchPos = new cn.nukkit.level.Position(
                shooter.x, shooter.y + EYE_HEIGHT, shooter.z, shooter.getLevel());
        Vector3 motion = computeAimMotion(shooter, target, speed, inaccuracy);
        // 创建抛射物，将射手作为额外参数传入（用于归属/击杀归属）
        Entity projectile = Entity.createEntity(projectileType, launchPos, shooter);
        if (projectile == null) {
            return null;
        }
        projectile.setMotion(motion);
        // 同步抛射物朝向为飞行方向，避免箭头初始指向错误（"朝向不自然"）。
        // Nukkit：yaw=0 朝 +Z（顺时针为正），pitch 上仰为负。
        alignProjectileRotation(projectile, motion);
        projectile.spawnToAll();
        return projectile;
    }

    /**
     * 对目标实体使用物品（如对敌人泼洒药水、对生物使用命名牌）。
     *
     * @param user   使用者
     * @param target 目标实体
     * @param item   物品（会被消耗）
     * @return true 表示使用成功
     */
    public boolean useItemOn(Entity user, Entity target, Item item) {
        if (user == null || target == null || item == null) {
            return false;
        }
        faceTo(user, target);
        return item.useOn(target);
    }

    /**
     * 对自身使用物品（如食用食物恢复、对自己施加药水效果）。
     *
     * @param user 使用者
     * @param item 物品（会被消耗）
     * @return true 表示使用成功
     */
    public boolean useItemOnSelf(Entity user, Item item) {
        if (user == null || item == null) {
            return false;
        }
        return item.useOn(user);
    }

    /**
     * 计算朝向目标的发射速度向量（含物理重力补偿与散布）。
     *
     * @param shooter    发射者
     * @param target     目标
     * @param speed      初速度
     * @param inaccuracy 散布
     * @return 速度向量
     */
    private Vector3 computeAimMotion(Entity shooter, Entity target, double speed, double inaccuracy) {
        double sx = shooter.x;
        double sy = shooter.y + EYE_HEIGHT;
        double sz = shooter.z;
        Vector3 motion = computeBallisticMotion(sx, sy, sz,
                target.x, target.y + TARGET_AIM_HEIGHT, target.z, speed);
        // 散布：随机扰动
        if (inaccuracy > 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            motion.x += (r.nextDouble() - 0.5) * inaccuracy;
            motion.y += (r.nextDouble() - 0.5) * inaccuracy;
            motion.z += (r.nextDouble() - 0.5) * inaccuracy;
        }
        return motion;
    }

    /**
     * 基于物理抛物线计算抛射物初速度向量，使其在水平飞行至目标水平距离后恰好到达目标高度。
     * <p>
     * 取飞行时间 {@code t = 水平距离 / 速度}，由 {@code Δy = vy·t − ½·g·t²} 反解垂直分量：
     * {@code vy = (Δy + ½·g·t²) / t}。相比旧的"线性 0.03×距离"补偿，该模型：
     * <ul>
     *   <li>近距离弹道平直（vy 接近 Δy/t，箭不会无谓上扬）</li>
     *   <li>远距离按物理所需抬升，命中目标高度而非飞过头顶</li>
     * </ul>
     * <p>
     * 本方法为纯函数（仅依赖入参坐标），便于单元测试验证弹道不再"高于玩家头顶"。
     *
     * @param sx    发射点 X
     * @param sy    发射点 Y
     * @param sz    发射点 Z
     * @param tx    目标 X
     * @param ty    目标 Y
     * @param tz    目标 Z
     * @param speed 初速度大小（方块/tick）
     * @return 初速度向量
     */
    static Vector3 computeBallisticMotion(double sx, double sy, double sz,
                                          double tx, double ty, double tz,
                                          double speed) {
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-4) {
            // 近乎垂直：直接朝目标高度发射
            return new Vector3(0, dy >= 0 ? speed : -speed, 0);
        }
        double t = horiz / speed;
        double vy = (dy + 0.5 * PROJECTILE_GRAVITY * t * t) / t;
        double hx = dx / horiz;
        double hz = dz / horiz;
        return new Vector3(hx * speed, vy, hz * speed);
    }

    /**
     * 将抛射物的旋转（yaw/pitch）对齐到其运动方向，使箭头朝向自然。
     *
     * @param projectile 抛射物
     * @param motion     运动向量
     */
    private static void alignProjectileRotation(Entity projectile, Vector3 motion) {
        double horizMag = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double yaw = Math.toDegrees(Math.atan2(-motion.x, motion.z));
        double pitch = Math.toDegrees(-Math.atan2(motion.y, horizMag));
        projectile.setRotation(yaw, pitch);
    }

    /**
     * 令 {@code entity} 面向 {@code target}（仅水平 yaw）。
     *
     * @param entity 旋转实体
     * @param target 面向目标
     */
    private void faceTo(Entity entity, Entity target) {
        double dx = target.x - entity.x;
        double dz = target.z - entity.z;
        // Nukkit yaw：0=面向 +Z，顺时针为正；atan2(dx, dz) 给出面向 (dx,dz) 方向的角度
        entity.yaw = Math.toDegrees(Math.atan2(dx, dz));
    }
}
