package io.github.JiangHu.jframe.ai;

import io.github.JiangHu.jframe.ai.core.combat.CombatActions;
import io.github.JiangHu.jframe.ai.core.executor.AttackExecutor;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner;
import io.github.JiangHu.jframe.ai.core.tactical.TeamTactics;
import io.github.JiangHu.jframe.ai.core.vision.SeeQuery;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.greedy.GreedyPathFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AiAPI} 七工厂冒烟测试:构造器注入与各工厂对 null 实体参数的防御传导。
 * <p>
 * 测试环境无运行中的 Nukkit 服务器,Entity 无法构造,因此只覆盖:
 * <ol>
 *   <li>六参构造器以真实依赖注入可正常构建(与 ai-spring.xml 装配一致)</li>
 *   <li>{@code attack}/{@code see} 工厂不设防(配置态对象,null 延迟到动作/查询时防御)</li>
 *   <li>{@code path}/{@code walk} 工厂经 NavigationExecutor 构造防御抛 NPE</li>
 *   <li>{@code loop}/{@code chase}/{@code wander} 工厂经 LoopBehavior 构造防御抛 NPE</li>
 * </ol>
 * 真实实体的完整行为链(计算→执行→判断)需真实服务器环境集成验证,此处不覆盖。
 */
@DisplayName("AiAPI:七工厂门面")
class AiAPILogicTest {

    /** 与 ai-spring.xml 装配一致:六个依赖全部真实构造(均可脱离服务器实例化) */
    private AiAPI api() {
        return new AiAPI(
                new AStarPathFinder(),
                new GreedyPathFinder(),
                new NavigatorManager(),
                new TacticalScanner(),
                new CombatActions(),
                new TeamTactics());
    }

    @Nested
    @DisplayName("构造器注入")
    class Construction {

        @Test
        @DisplayName("六参构造器以真实依赖注入构建成功")
        void buildsWithRealDependencies() {
            assertNotNull(api(), "六依赖注入构造应成功");
        }
    }

    @Nested
    @DisplayName("不设防工厂(配置态对象)")
    class PermissiveFactories {

        @Test
        @DisplayName("attack(null) 返回执行器实例(null 延迟到 fire 时防御)")
        void attackFactoryReturnsInstance() {
            assertNotNull(api().attack(null), "attack 工厂应返回 AttackExecutor 实例");
        }

        @Test
        @DisplayName("see(null) 返回查询器实例(null 延迟到查询时防御)")
        void seeFactoryReturnsInstance() {
            assertNotNull(api().see(null), "see 工厂应返回 SeeQuery 实例");
        }
    }

    @Nested
    @DisplayName("防御传导工厂(底层构造器 requireNonNull)")
    class DefensiveFactories {

        @Test
        @DisplayName("path(null) 经 NavigationExecutor 构造防御抛 NPE")
        void pathFactoryRejectsNullSelf() {
            assertThrows(NullPointerException.class, () -> api().path(null),
                    "path 工厂应传导 NavigationExecutor 的 self 非空防御");
        }

        @Test
        @DisplayName("walk(null) 经 NavigationExecutor 构造防御抛 NPE")
        void walkFactoryRejectsNullSelf() {
            assertThrows(NullPointerException.class, () -> api().walk(null),
                    "walk 工厂应传导 NavigationExecutor 的 self 非空防御");
        }

        @Test
        @DisplayName("loop(null) 经 LoopBehavior 构造防御抛 NPE")
        void loopFactoryRejectsNullSelf() {
            assertThrows(NullPointerException.class, () -> api().loop(null),
                    "loop 工厂应传导 LoopBehavior 的 self 非空防御");
        }

        @Test
        @DisplayName("chase(null, null) 经 LoopBehavior 构造防御抛 NPE")
        void chaseFactoryRejectsNullSelf() {
            assertThrows(NullPointerException.class, () -> api().chase(null, null),
                    "chase 工厂应传导 LoopBehavior 的 self 非空防御");
        }

        @Test
        @DisplayName("wander(null, radius) 经 LoopBehavior 构造防御抛 NPE")
        void wanderFactoryRejectsNullSelf() {
            assertThrows(NullPointerException.class, () -> api().wander(null, 12),
                    "wander 工厂应传导 LoopBehavior 的 self 非空防御");
        }
    }
}
