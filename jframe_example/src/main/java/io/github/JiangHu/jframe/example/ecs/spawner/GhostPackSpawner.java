package io.github.JiangHu.jframe.example.ecs.spawner;

import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.spawner.SpawnContext;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner;

/**
 * 演示生成器 —— 幽灵（自定义注册类 + 参数表，<b>一次调用只生成其对应的一个实体</b>）。
 *
 * <p>YAML 引用（night-ghosts 定义）：
 * <pre>{@code
 * spawn: { use: ghost-pack, params: { type: Skeleton } }
 * }</pre>
 *
 * <p>生成一只"幽灵"（Skeleton 换皮 —— 不能用 Zombie，否则会被 elite-zombies
 * 选择器同时命中，名牌互相覆盖、重击消息串台），引擎将其注册为 EcsEntity 并走进入
 * 管线挂载 ghost-mark。<b>多数量生成由调用方多次调用 {@code spawn} 完成</b>
 * （主流引擎语义：一次调用 = 一个实体），见 {@code /ecs wave} 命令。
 */
@EcsSpawner("ghost-pack")
public class GhostPackSpawner implements Spawner {

    /** 实体类型名（params.type 注入；原版实体默认注册名，如 Skeleton） */
    private String type = "Skeleton";

    @Override
    public Object spawn(SpawnContext context) {
        Position center = context.requirePosition();
        Entity ghost = Entity.createEntity(type, center);
        if (ghost == null) {
            Server.getInstance().getLogger().warning(
                    "[ECS示例] 幽灵生成失败：实体类型未注册 type=" + type + "（定义 '" + context.definitionName() + "'）");
            return null;
        }
        ghost.setNameTag("§5幽灵");
        ghost.setNameTagVisible(true);
        ghost.spawnToAll();
        return ghost;
    }
}
