package io.github.JiangHu.jframe.ai.core.executor;

import cn.nukkit.entity.Entity;
import cn.nukkit.network.protocol.AnimatePacket;
import io.github.JiangHu.jframe.ai.core.combat.CombatActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttackExecutor} 纯逻辑测试:链式配置语义与 fire() 防御路径。
 * <p>
 * 测试环境无运行中的 Nukkit 服务器({@code Server.getInstance() == null}),
 * fire() 按设计跳过主线程校验,直接进入 Kind 分派;动作语义委托
 * {@link CombatActions} 的 null/越界防御,失败统一返回 {@code false},
 * 因此全部用 null 参数覆盖防御分支,不依赖真实实体。
 * <p>
 * 主线程校验(有 Server 且非主线程抛 IllegalStateException)与真实伤害/
 * 抛射物生成路径需真实服务器环境集成验证,此处不覆盖。
 */
@DisplayName("AttackExecutor:战斗动作链式门面")
class AttackExecutorLogicTest {

    /** 无服务器环境下构造:self 为 null、combat 为真实实例(委托目标) */
    private AttackExecutor executor() {
        return new AttackExecutor(null, new CombatActions());
    }

    @Nested
    @DisplayName("链式配置")
    class Chaining {

        @Test
        @DisplayName("五个动作配置方法均返回 this 支持链式调用")
        void allConfiguratorsReturnThis() {
            AttackExecutor executor = executor();

            assertSame(executor, executor.melee(null, 4.0f), "melee 应返回 this");
            assertSame(executor, executor.arrow(null), "arrow(默认参数) 应返回 this");
            assertSame(executor, executor.arrow(null, 1.5, 0.2), "arrow(完整参数) 应返回 this");
            assertSame(executor, executor.projectile("Snowball", null, 1.2, 0.2),
                    "projectile 应返回 this");
            assertSame(executor, executor.useItemOn(null, null), "useItemOn 应返回 this");
            assertSame(executor, executor.useItemOnSelf(null), "useItemOnSelf 应返回 this");
        }

        @Test
        @DisplayName("fire() 后可重新配置复用(配置态对象)")
        void reconfigurableAfterFire() {
            AttackExecutor executor = executor();

            // 第一次 fire:走 MELEE 防御返回 false
            assertFalse(executor.melee(null, 4.0f).fire());
            // 重新配置为近战后再 fire:不再抛"未配置动作"(kind 已重新设置)
            assertFalse(executor.melee(null, 4.0f).fire(),
                    "重新配置后 fire 应走动作分派而非未配置异常");
        }
    }

    @Nested
    @DisplayName("fire() 防御路径")
    class FireDefenses {

        @Test
        @DisplayName("未配置任何动作时 fire() 抛 IllegalStateException")
        void fireWithoutActionThrows() {
            AttackExecutor executor = executor();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    executor::fire,
                    "Kind.NONE 时 fire 应抛未配置异常");
            assertTrue(ex.getMessage().contains("未配置动作"),
                    "异常消息应指明未配置动作,实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("melee:目标为 null 时委托 CombatActions 返回 false")
        void meleeWithNullTargetReturnsFalse() {
            assertFalse(executor().melee(null, 4.0f).fire(),
                    "null 目标应被 CombatActions 防御拦截");
        }

        @Test
        @DisplayName("melee:伤害 ≤ 0 时委托 CombatActions 返回 false")
        void meleeWithNonPositiveDamageReturnsFalse() {
            assertFalse(executor().melee(null, 0.0f).fire(), "damage=0 应返回 false");
            assertFalse(executor().melee(null, -1.0f).fire(), "damage<0 应返回 false");
        }

        @Test
        @DisplayName("arrow:射手/目标为 null 时 shootArrow 返回 null → fire 返回 false")
        void arrowWithNullEntitiesReturnsFalse() {
            assertFalse(executor().arrow(null).fire(), "默认参数箭应防御失败");
            assertFalse(executor().arrow(null, 1.5, 0.2).fire(), "完整参数箭应防御失败");
        }

        @Test
        @DisplayName("projectile:目标为 null 时 throwProjectile 返回 null → fire 返回 false")
        void projectileWithNullTargetReturnsFalse() {
            assertFalse(executor().projectile("Snowball", null, 1.2, 0.2).fire(),
                    "null 目标应防御失败");
        }

        @Test
        @DisplayName("useItemOn:user/target/item 为 null 时返回 false")
        void useItemOnWithNullsReturnsFalse() {
            assertFalse(executor().useItemOn(null, null).fire(),
                    "null 参数应被 CombatActions 防御拦截");
        }

        @Test
        @DisplayName("useItemOnSelf:user/item 为 null 时返回 false")
        void useItemOnSelfWithNullsReturnsFalse() {
            assertFalse(executor().useItemOnSelf(null).fire(),
                    "null 参数应被 CombatActions 防御拦截");
        }
    }

    @Nested
    @DisplayName("animation() 动画配置")
    class AnimationConfig {

        @Test
        @DisplayName("animation() 返回 this 支持链式调用")
        void animationReturnsThis() {
            AttackExecutor executor = executor();

            assertSame(executor, executor.animation(AnimatePacket.Action.SWING_ARM),
                    "animation 应返回 this");
            assertSame(executor, executor.animation(null),
                    "animation(null) 也应返回 this");
        }

        @Test
        @DisplayName("未配置 animation 时 fire() 走各动作默认动画路径(无 action 重载)")
        void unconfiguredAnimationUsesDefaultPath() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            assertTrue(executor.melee(null, 4.0f).fire());
            assertTrue(combat.plainMeleeCalled, "未配置动画应走无 action 重载(默认动画)");
            assertFalse(combat.actionMeleeCalled, "不应走带 action 重载");
        }

        @Test
        @DisplayName("animation(X) 后 fire() 透传 X 到带 action 重载")
        void explicitAnimationIsPassedThrough() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            assertTrue(executor.melee(null, 4.0f)
                    .animation(AnimatePacket.Action.CRITICAL_HIT).fire());
            assertTrue(combat.actionMeleeCalled, "显式配置动画应走带 action 重载");
            assertSame(AnimatePacket.Action.CRITICAL_HIT, combat.lastMeleeAction,
                    "动画动作应原样透传");
        }

        @Test
        @DisplayName("animation(null) 表示显式关闭动画,仍走带 action 重载")
        void explicitNullAnimationDisablesAnimation() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            assertTrue(executor.melee(null, 4.0f).animation(null).fire());
            assertTrue(combat.actionMeleeCalled, "animation(null) 是显式配置,应走带 action 重载");
            assertNull(combat.lastMeleeAction, "透传的 action 应为 null(关闭动画)");
        }

        @Test
        @DisplayName("配置新动作时动画配置重置(回到默认动画路径)")
        void animationResetsOnNewActionConfig() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            // 第一次:显式配置动画
            assertTrue(executor.melee(null, 4.0f)
                    .animation(AnimatePacket.Action.CRITICAL_HIT).fire());
            assertTrue(combat.actionMeleeCalled);

            // 重新配置动作(不调 animation):动画配置应被重置
            combat.reset();
            assertTrue(executor.melee(null, 4.0f).fire());
            assertTrue(combat.plainMeleeCalled, "重新配置动作后应回到默认动画路径");
            assertFalse(combat.actionMeleeCalled, "上一次的动画配置不应残留");
        }
    }

    @Nested
    @DisplayName("arrow() 自定义箭类型与 customizer")
    class ArrowTypeConfig {

        @Test
        @DisplayName("自定义类型箭配置返回 this 支持链式调用")
        void typedArrowReturnsThis() {
            AttackExecutor executor = executor();

            assertSame(executor, executor.arrow(null, 1.5, 0.2, "GameArrow"),
                    "arrow(类型) 应返回 this");
            assertSame(executor, executor.arrow(null, 1.5, 0.2, "GameArrow", e -> { }),
                    "arrow(类型+定制) 应返回 this");
        }

        @Test
        @DisplayName("自定义类型与 customizer 原样透传到 CombatActions 最全参 shootArrow")
        void typedArrowPassesTypeAndCustomizerThrough() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);
            Consumer<Entity> customizer = e -> { };

            executor.arrow(null, 2.0, 0.3, "GameArrow", customizer).fire();

            assertTrue(combat.shootArrowCalled, "fire 应分派到最全参 shootArrow");
            assertSame("GameArrow", combat.lastArrowType, "箭类型应原样透传");
            assertSame(customizer, combat.lastArrowCustomizer, "customizer 应原样透传");
        }

        @Test
        @DisplayName("默认 arrow 路径回退原版 ARROW_TYPE 且无 customizer")
        void defaultArrowFallsBackToVanillaType() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            executor.arrow(null, 1.5, 0.2).fire();

            assertTrue(combat.shootArrowCalled);
            assertSame(CombatActions.ARROW_TYPE, combat.lastArrowType,
                    "未指定类型应回退原版 Arrow");
            assertNull(combat.lastArrowCustomizer, "默认箭不应携带 customizer");
        }

        @Test
        @DisplayName("null/空箭类型回退原版 ARROW_TYPE(customizer 保留)")
        void blankTypeFallsBackToVanillaType() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);
            Consumer<Entity> customizer = e -> { };

            executor.arrow(null, 1.5, 0.2, null, customizer).fire();

            assertSame(CombatActions.ARROW_TYPE, combat.lastArrowType, "null 类型应回退 Arrow");
            assertSame(customizer, combat.lastArrowCustomizer, "类型回退不影响 customizer");
        }

        @Test
        @DisplayName("复用语义:自定义类型箭后再配默认箭,类型不残留")
        void typedArrowDoesNotLeakIntoDefaultArrow() {
            RecordingCombatActions combat = new RecordingCombatActions();
            AttackExecutor executor = new AttackExecutor(null, combat);

            executor.arrow(null, 2.0, 0.3, "GameArrow", e -> { }).fire();
            combat.reset();
            executor.arrow(null, 1.5, 0.2).fire();

            assertSame(CombatActions.ARROW_TYPE, combat.lastArrowType,
                    "重新配置默认箭后不应残留上次的自定义类型");
            assertNull(combat.lastArrowCustomizer, "customizer 同步重置");
        }
    }

    /**
     * 记录型 CombatActions 子类:覆写近战两个重载记录 fire() 的分派目标,
     * 用于离线验证 animation() 三态语义(未配置/显式指定/显式 null)。
     * 带参重载直接返回 true(不触达真实实体),无参重载委托超类(走 null 防御)。
     */
    private static class RecordingCombatActions extends CombatActions {

        boolean plainMeleeCalled;
        boolean actionMeleeCalled;
        AnimatePacket.Action lastMeleeAction;

        boolean shootArrowCalled;
        String lastArrowType;
        Consumer<Entity> lastArrowCustomizer;
        AnimatePacket.Action lastArrowAction;

        void reset() {
            plainMeleeCalled = false;
            actionMeleeCalled = false;
            lastMeleeAction = null;
            shootArrowCalled = false;
            lastArrowType = null;
            lastArrowCustomizer = null;
            lastArrowAction = null;
        }

        @Override
        public boolean meleeAttack(Entity attacker, Entity target, float damage) {
            plainMeleeCalled = true;
            return true;
        }

        @Override
        public boolean meleeAttack(Entity attacker, Entity target, float damage,
                                   AnimatePacket.Action action) {
            actionMeleeCalled = true;
            lastMeleeAction = action;
            return true;
        }

        @Override
        public Entity shootArrow(Entity shooter, Entity target, double speed, double inaccuracy,
                                 AnimatePacket.Action action, String arrowType,
                                 Consumer<Entity> customizer) {
            shootArrowCalled = true;
            lastArrowType = arrowType;
            lastArrowCustomizer = customizer;
            lastArrowAction = action;
            return null;
        }
    }
}
