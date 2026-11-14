package io.github.JiangHu.jframe.example.ecs.despawner;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.engine.ecs.despawner.DespawnContext;
import io.github.JiangHu.jframe.engine.ecs.despawner.Despawner;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsDespawner;
import io.github.JiangHu.jframe.example.ecs.spawner.ArenaRef;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 演示回收器 —— 竞技场回收（<b>回收伴随文件 IO</b>，模拟卸载 level + 清理目录）。
 *
 * <p>YAML 引用（arena 定义）：
 * <pre>{@code
 * despawn: { use: arena-recycle, params: { broadcast: true } }
 * }</pre>
 *
 * <p>触发时机（销毁通知驱动，回调在全部组件 onUnmount 之后）：
 * <ul>
 *   <li>{@code /ecs arenaoff} 显式 destroy</li>
 *   <li>游戏侧销毁通知（本示例的 POJO 产物无 tracker 通知，真实 Level 卸载同理——
 *       引擎未覆盖的销毁事件可在 component 里加 {@code @OnEcsEvent} 监听后手动 destroy）</li>
 * </ul>
 * 引擎 {@code stop()} 不触发（产物存续）；首行 instanceof 守卫过滤非本产物来源的误调。
 */
@EcsDespawner("arena-recycle")
public class ArenaRecycleDespawner implements Despawner {

    /** 是否全服广播回收（params.broadcast 注入） */
    private boolean broadcast = true;

    @Override
    public void despawn(DespawnContext context) {
        if (!(context.product() instanceof ArenaRef arena)) {
            return; // 首行守卫：非本定义产物直接忽略
        }
        try {
            Files.deleteIfExists(arena.markerPath());
        } catch (IOException e) {
            Server.getInstance().getLogger().error("[ECS示例] 竞技场 marker 删除失败: "
                    + arena.markerPath(), e);
        }
        if (broadcast) {
            Server.getInstance().broadcastMessage("§7竞技场 §e" + arena.name() + " §7已回收（定义 '"
                    + context.definitionName() + "'）");
        }
    }
}
