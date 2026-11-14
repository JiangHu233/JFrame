package io.github.JiangHu.jframe.example.ecs.system;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno;
import io.github.JiangHu.jframe.example.ecs.component.GhostMarkComponent;
import io.github.JiangHu.jframe.example.ecs.component.WaveStateComponent;

/**
 * 演示系统二 —— 幽灵波次结算（每 2 秒）。
 *
 * <p>覆盖特性：纯虚拟 gameobject 上的状态组件 + 双 query（幽灵计数 / 管理器状态）。
 * 波次逻辑完全消费引擎语义：幽灵被杀 → 引擎世界 diff 自动卸载 ghost-mark →
 * 计数递减 → 归零即结算下一波，系统自身不维护任何实体状态表。
 *
 * <p>实机预期：{@code /ecs wave} 后每只幽灵死亡都会让广播数字递减；杀光后全服广播
 * 波次 +1。剩余数变化才广播（lastAnnounced 去重），避免刷屏。
 */
@EcsSystemAnno
public class WaveSystem implements EcsSystem {

    @Override
    public int interval() {
        return 40;
    }

    @Override
    public void tick(EcsEngine engine) {
        // 多管理器防御：只取首个（/ecs wave 自动补建，正常只有一个）
        var managers = engine.query(WaveStateComponent.class);
        if (managers.isEmpty()) {
            return;
        }
        EcsEntity manager = managers.get(0);
        WaveStateComponent state = manager.get(WaveStateComponent.class);

        int alive = engine.query(GhostMarkComponent.class).size();
        state.setMobsLeft(alive);

        if (alive > 0) {
            if (alive != state.lastAnnounced()) {
                state.setLastAnnounced(alive);
                Server.getInstance().broadcastMessage("§7[波次] §f剩余幽灵: §e" + alive);
            }
            return;
        }
        if (state.lastAnnounced() > 0) {
            // 刚刚清空 → 结算
            state.setLastAnnounced(0);
            int finished = state.wave();
            state.nextWave();
            Server.getInstance().broadcastMessage("§7[波次] §a第 " + finished
                    + " 波清空！下一波序号 §e" + state.wave() + "§a，输入 §f/ecs wave §a开始");
        }
    }
}
