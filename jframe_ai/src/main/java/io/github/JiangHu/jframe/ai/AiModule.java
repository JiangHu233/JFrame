package io.github.JiangHu.jframe.ai;

import io.github.JiangHu.jframe.ai.core.combat.CombatActions;
import io.github.JiangHu.jframe.ai.core.navigation.NavigatorManager;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalScanner;
import io.github.JiangHu.jframe.ai.core.tactical.TeamTactics;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarPathFinder;
import io.github.JiangHu.jframe.ai.pathfinding.greedy.GreedyPathFinder;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;

import java.util.Set;

/**
 * AI 模块工厂 — 一对一替代原 {@code AiSpringConfig} + {@code ai-spring.xml}。
 * <p>
 * 装配（与原 XML 等价，构造器参数顺序一致）：
 * A* 与贪心双寻路策略 + NavigatorManager(PluginAware 绑定) + 战术扫描器 +
 * 战斗动作 + 团队战术 → AiAPI 七工厂门面。行为骨架与执行器为无状态库件，
 * 由 AiAPI 按需创建，不进容器。
 */
public class AiModule implements JFrameModule {

    @Override
    public String id() {
        return "ai";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(AiAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        AStarPathFinder aStar = new AStarPathFinder();
        GreedyPathFinder greedy = new GreedyPathFinder();
        NavigatorManager navigatorManager = new NavigatorManager();
        ctx.bind(navigatorManager);
        TacticalScanner tacticalScanner = new TacticalScanner();
        CombatActions combatActions = new CombatActions();
        TeamTactics teamTactics = new TeamTactics();
        ctx.register(AiAPI.class, new AiAPI(aStar, greedy, navigatorManager,
                tacticalScanner, combatActions, teamTactics));
    }
}
