package io.github.JiangHu.jframe.example.ecs.system;

import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerJoinEvent;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.system.EcsEngineAware;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;
import io.github.JiangHu.jframe.example.ecs.component.EliteComponent;
import io.github.JiangHu.jframe.example.ecs.component.MagicChestComponent;

/**
 * 演示系统三 —— 进服欢迎播报（<b>系统事件</b>：全局处理器，无 tick、无解析器）。
 *
 * <p>覆盖特性：
 * <ul>
 *   <li>{@code @OnEcsEvent} 标在<b>系统类</b>上 → 全局事件处理器：事件触发即调用一次，
 *       不需要宿主解析器（PlayerJoinEvent 没有任何解析器也能进 ECS）</li>
 *   <li>{@link EcsEngineAware} 注册时绑定引擎 → 处理器内 {@code query()} 做全局统计</li>
 *   <li><b>纯事件系统示范</b>：不 override {@code tick} —— 引擎注册期检测后不进入
 *       tick 执行路径（零轮询），只存在于事件注册表</li>
 * </ul>
 *
 * <p>实机预期：玩家进服全服播报"欢迎 XX！服务器当前跟踪实体 N / 魔法箱 M / 精英 K"。
 */
@EcsSystemAnno
public class JoinEchoSystem implements EcsSystem, EcsEngineAware {

    /** 注册时由引擎注入（供事件处理器内做全局查询） */
    private EcsEngine engine;

    @Override
    public void bindEngine(EcsEngine engine) {
        this.engine = engine;
    }

    /**
     * 进服事件 —— 系统级全局处理器（无需解析器）。
     */
    @OnEcsEvent
    public void onJoin(PlayerJoinEvent event) {
        if (engine == null) {
            return;
        }
        int entities = engine.entities().size();
        int chests = engine.query(MagicChestComponent.class).size();
        int elites = engine.query(EliteComponent.class).size();
        Server.getInstance().broadcastMessage("§a欢迎 §e" + event.getPlayer().getName()
                + " §a进服！当前跟踪实体 §e" + entities
                + " §7/ §d魔法箱 §e" + chests
                + " §7/ §c精英 §e" + elites);
    }
}
