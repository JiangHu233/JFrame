package io.github.JiangHu.jframe.ai.core.combat;

import cn.nukkit.network.protocol.AnimatePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 近战攻击与动画广播纯逻辑单元测试。
 * <p>
 * 验证挥刀动画修复的三个可离线验证面：
 * <ul>
 *   <li>动画包构造：{@code buildAnimationPacket} 正确设置 {@code eid} 与 {@code action}</li>
 *   <li>默认动画常量：近战/射箭/投掷/对目标用物品默认 {@code SWING_ARM}（挥臂）</li>
 *   <li>防御分支：null/非法参数统一返回 false，不抛异常（含动画关闭 action=null）</li>
 * </ul>
 * 注：真实伤害结算（EntityDamageByEntityEvent 击退）与区块广播路径需真实服务器环境
 * 集成验证，此处不覆盖；事件构造的正确性由四参构造器签名（damager 在前）静态保证。
 *
 * @see CombatActions
 */
@DisplayName("近战攻击:挥刀动画与防御")
class CombatActionsMeleeTest {

    private final CombatActions combat = new CombatActions();

    @Nested
    @DisplayName("动画包构造")
    class AnimationPacketBuild {

        @Test
        @DisplayName("buildAnimationPacket:正确设置 eid 与 action")
        void buildsPacketWithEntityIdAndAction() {
            AnimatePacket packet = CombatActions.buildAnimationPacket(12345L,
                    AnimatePacket.Action.SWING_ARM);
            assertEquals(12345L, packet.eid, "eid 应为传入的实体 ID");
            assertSame(AnimatePacket.Action.SWING_ARM, packet.action, "action 应为传入动作");
        }

        @Test
        @DisplayName("buildAnimationPacket:支持任意动作(暴击/唤醒等)")
        void buildsPacketWithArbitraryAction() {
            AnimatePacket packet = CombatActions.buildAnimationPacket(-1L,
                    AnimatePacket.Action.CRITICAL_HIT);
            assertEquals(-1L, packet.eid);
            assertSame(AnimatePacket.Action.CRITICAL_HIT, packet.action);
        }
    }

    @Nested
    @DisplayName("默认动画常量")
    class DefaultAnimation {

        @Test
        @DisplayName("战斗动作默认动画为挥臂(SWING_ARM)")
        void defaultCombatAnimationIsSwingArm() {
            assertSame(AnimatePacket.Action.SWING_ARM, CombatActions.DEFAULT_COMBAT_ANIMATION,
                    "近战/射箭/投掷/对目标用物品的默认动画应为 SWING_ARM");
        }
    }

    @Nested
    @DisplayName("meleeAttack 防御分支")
    class MeleeDefenses {

        @Test
        @DisplayName("attacker/target 为 null 或 damage ≤ 0 时返回 false(默认动画重载)")
        void meleeDefensesDefaultOverload() {
            assertFalse(combat.meleeAttack(null, null, 4.0f), "null attacker 应返回 false");
            assertFalse(combat.meleeAttack(null, null, 0.0f), "damage=0 应返回 false");
            assertFalse(combat.meleeAttack(null, null, -1.0f), "damage<0 应返回 false");
        }

        @Test
        @DisplayName("attacker/target 为 null 或 damage ≤ 0 时返回 false(自定义动画重载)")
        void meleeDefensesActionOverload() {
            assertFalse(combat.meleeAttack(null, null, 4.0f, AnimatePacket.Action.SWING_ARM),
                    "null 参数应返回 false");
            assertFalse(combat.meleeAttack(null, null, 4.0f, null),
                    "action=null(关闭动画)不影响参数防御");
        }
    }

    @Nested
    @DisplayName("playAnimation 防御分支")
    class PlayAnimationDefenses {

        @Test
        @DisplayName("entity/action 为 null 时返回 false,不抛异常")
        void playAnimationDefenses() {
            assertFalse(combat.playAnimation(null, AnimatePacket.Action.SWING_ARM),
                    "null entity 应返回 false");
            assertFalse(combat.playAnimation(null, null), "null action 应返回 false");
        }
    }
}
