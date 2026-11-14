package io.github.JiangHu.jframe.example.ecs.component;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;

/**
 * 演示组件 D —— 幽灵标记（挂到 night-ghosts 生成的实体上）。
 *
 * <p>无任何行为逻辑，仅作为 {@code WaveSystem} 的<b>倒排索引查询标记</b>：
 * 系统每 2 秒 query(GhostMarkComponent) 统计存活幽灵数。
 * 幽灵被杀 → 引擎世界 diff 自动卸载本组件 → 计数递减 → 波次结算。
 * （波次逻辑完全消费引擎语义，不自建任何状态表。）
 */
@EcsComponent("ghost-mark")
public class GhostMarkComponent extends ComponentBase {

    @Override
    public void onMount() {
        Server.getInstance().getLogger().info("[ECS示例] 幽灵入队 #" + host().id());
    }

    @Override
    public void onUnmount() {
        Server.getInstance().getLogger().info("[ECS示例] 幽灵阵亡 #" + host().id());
    }
}
