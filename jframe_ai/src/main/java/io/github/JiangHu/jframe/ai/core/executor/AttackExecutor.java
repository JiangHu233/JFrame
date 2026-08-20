package io.github.JiangHu.jframe.ai.core.executor;

import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.ai.core.combat.CombatActions;

/**
 * 战斗动作执行器:链式配置一次战斗动作,{@link #fire()} 在主线程执行。
 * <p>
 * 本类是 {@link io.github.JiangHu.jframe.ai.core.combat.CombatActions} 的链式门面,
 * 动作语义(伤害事件、弹道补偿、散布、朝向对齐)全部委托后者,本类只负责
 * <b>配置收集 + 主线程校验</b>。
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>配置态对象</b>:一次构造对应一个实体,动作方法返回 {@code this} 支持链式,
 *       {@code fire()} 后可重新配置复用(常作为行为循环内的攻击手段)。</li>
 *   <li><b>主线程约束</b>:战斗动作直接操作实体与事件(Nukkit 非线程安全),
 *       {@code fire()} 在有 Server 且非主线程时抛出 {@link IllegalStateException}。
 *       无 Server 环境(单元测试)跳过校验。</li>
 *   <li>动作参数在 {@code fire()} 时读取,配置阶段不校验(委托 CombatActions 的
 *       null/越界防御,失败统一返回 {@code false})。</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 近战
 * ai.attack(zombie).melee(player, 4.0f).fire();
 * // 射箭(速度 1.5,散布 0.3)
 * ai.attack(skeleton).arrow(player, 1.5, 0.3).fire();
 * // 投掷雪球
 * ai.attack(witch).projectile("Snowball", player, 1.2, 0.2).fire();
 * // 对自己使用药水
 * ai.attack(alchemist).useItemOnSelf(potion).fire();
 * }</pre>
 *
 * @see CombatActions
 */
public final class AttackExecutor {

    private final Entity self;
    private final CombatActions combat;

    /** 待执行动作类型 */
    private Kind kind = Kind.NONE;
    private Entity target;
    private float damage;
    private String projectileType;
    private double speed;
    private double inaccuracy;
    private Item item;

    private enum Kind {
        NONE, MELEE, ARROW, PROJECTILE, USE_ITEM_ON, USE_ITEM_SELF
    }

    /**
     * 构造执行器。
     *
     * @param self   动作执行者
     * @param combat 战斗动作实现(委托目标)
     */
    public AttackExecutor(Entity self, CombatActions combat) {
        this.self = self;
        this.combat = combat;
    }

    /**
     * 配置近战攻击:面向目标并施加伤害事件。
     *
     * @param target 攻击目标
     * @param damage 伤害值(半心)
     * @return this
     */
    public AttackExecutor melee(Entity target, float damage) {
        this.kind = Kind.MELEE;
        this.target = target;
        this.damage = damage;
        return this;
    }

    /**
     * 配置射箭:默认速度 {@link io.github.JiangHu.jframe.ai.core.combat.CombatActions#DEFAULT_ARROW_SPEED}
     * 与散布 {@link io.github.JiangHu.jframe.ai.core.combat.CombatActions#DEFAULT_INACCURACY}。
     *
     * @param target 射击目标
     * @return this
     */
    public AttackExecutor arrow(Entity target) {
        return arrow(target,
                CombatActions.DEFAULT_ARROW_SPEED,
                CombatActions.DEFAULT_INACCURACY);
    }

    /**
     * 配置射箭:指定初速度与散布。
     *
     * @param target      射击目标
     * @param speed       初速度(方块/tick)
     * @param inaccuracy  散布程度(0=精准)
     * @return this
     */
    public AttackExecutor arrow(Entity target, double speed, double inaccuracy) {
        this.kind = Kind.ARROW;
        this.target = target;
        this.speed = speed;
        this.inaccuracy = inaccuracy;
        return this;
    }

    /**
     * 配置投掷任意已注册抛射物(雪球/药水/经验瓶等)。
     *
     * @param projectileType 抛射物注册名(如 {@code "Snowball"}、{@code "SplashPotion"})
     * @param target         目标
     * @param speed          初速度(方块/tick)
     * @param inaccuracy     散布程度
     * @return this
     */
    public AttackExecutor projectile(String projectileType, Entity target, double speed, double inaccuracy) {
        this.kind = Kind.PROJECTILE;
        this.projectileType = projectileType;
        this.target = target;
        this.speed = speed;
        this.inaccuracy = inaccuracy;
        return this;
    }

    /**
     * 配置对目标实体使用物品(如对敌人泼药水)。
     *
     * @param target 目标实体
     * @param item   物品(会被消耗)
     * @return this
     */
    public AttackExecutor useItemOn(Entity target, Item item) {
        this.kind = Kind.USE_ITEM_ON;
        this.target = target;
        this.item = item;
        return this;
    }

    /**
     * 配置对自身使用物品(如进食、自我治疗)。
     *
     * @param item 物品(会被消耗)
     * @return this
     */
    public AttackExecutor useItemOnSelf(Item item) {
        this.kind = Kind.USE_ITEM_SELF;
        this.item = item;
        return this;
    }

    /**
     * 执行已配置的动作。<b>仅限主线程调用</b>(无 Server 的测试环境除外)。
     *
     * @return true 表示动作成功(伤害未被取消 / 抛射物已生成 / 物品使用成功)
     * @throws IllegalStateException 有 Server 且当前非主线程
     * @throws IllegalStateException 尚未配置任何动作
     */
    public boolean fire() {
        Server server = Server.getInstance();
        if (server != null && !server.isPrimaryThread()) {
            throw new IllegalStateException("AttackExecutor.fire() 必须在主线程调用");
        }
        if (kind == Kind.NONE) {
            throw new IllegalStateException("未配置动作:请先调用 melee/arrow/projectile/useItemOn/useItemOnSelf");
        }
        switch (kind) {
            case MELEE:
                return combat.meleeAttack(self, target, damage);
            case ARROW:
                return combat.shootArrow(self, target, speed, inaccuracy) != null;
            case PROJECTILE:
                return combat.throwProjectile(self, projectileType, target, speed, inaccuracy) != null;
            case USE_ITEM_ON:
                return combat.useItemOn(self, target, item);
            case USE_ITEM_SELF:
                return combat.useItemOnSelf(self, item);
            default:
                return false;
        }
    }
}
