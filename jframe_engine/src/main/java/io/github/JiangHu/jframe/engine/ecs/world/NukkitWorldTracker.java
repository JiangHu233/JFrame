package io.github.JiangHu.jframe.engine.ecs.world;

import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.entity.EntityDespawnEvent;
import cn.nukkit.event.entity.EntitySpawnEvent;
import cn.nukkit.event.level.ChunkLoadEvent;
import cn.nukkit.event.level.ChunkUnloadEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 世界跟踪器（事件驱动，零延迟）— 替代"每 tick 全量 diff"的主跟踪路径。
 *
 * <h3>分类型事件表（依据目标 Nukkit-MOT jar 核实）</h3>
 * <table border="1">
 *   <tr><th>对象</th><th>进入（enter）</th><th>离开（remove）</th></tr>
 *   <tr><td>Entity（玩家/生物/掉落物）</td>
 *       <td>{@code EntitySpawnEvent}（进服/自然生成/掉落/插件生成全覆盖）+
 *           {@code PlayerJoinEvent} 幂等补充</td>
 *       <td>{@code EntityDespawnEvent}</td></tr>
 *   <tr><td>BlockEntity（箱/炉）</td>
 *       <td><b>不监听放置事件</b>（{@code BlockPlaceEvent} 触发时方块实体尚不存在，
 *           同步挂载原理上不可行）——依赖 {@code ChunkLoadEvent} 批量、
 *           {@code EcsEngine.resolveCandidate} <b>惰性收养</b>（首触当 tick 挂载）、
 *           低频对账兜底</td>
 *       <td>{@code BlockBreakEvent} 事件时即卸载（ignoreCancelled=true，
 *           被取消的破坏由收养/对账自愈）</td></tr>
 *   <tr><td>区块</td><td>{@code ChunkLoadEvent} 批量 enter 该区块实体+方块实体</td>
 *       <td>{@code ChunkUnloadEvent} 批量 remove 该区块绑定</td></tr>
 * </table>
 *
 * <h3>协作分工</h3>
 * <ul>
 *   <li>{@link NukkitWorldScanner} 降级为<b>低频对账</b>（默认每 100 tick）：
 *       纠正绕过事件的路径（如插件直接 setBlock）与可能的事件遗漏；</li>
 *   <li>本类只做 enter/remove 的幂等调用，无自有状态表。</li>
 * </ul>
 *
 * <p>分发入口（{@code onXxx} 系列方法）为可直调的纯逻辑（单测/实机共用）。
 */
public class NukkitWorldTracker implements Listener {

    /** 引擎（幂等 enter/remove 入口） */
    private final EcsEngine engine;

    /** 已注册的事件类型（防重复注册） */
    private final Set<Class<? extends Event>> registered = new HashSet<>();

    /** 关联插件（Nukkit 注册用） */
    private Plugin plugin;

    /** 激活标志（stop 后回调为空操作） */
    private volatile boolean active;

    /**
     * 构造跟踪器。
     *
     * @param engine 引擎
     */
    public NukkitWorldTracker(EcsEngine engine) {
        this.engine = engine;
    }

    /**
     * 绑定插件（幂等；由 EcsAPI.bindPlugin 转发）。
     *
     * @param plugin 插件实例
     */
    public void bindPlugin(Plugin plugin) {
        if (this.plugin != null) {
            return;
        }
        this.plugin = plugin;
    }

    /**
     * 激活并注册全部 Nukkit 监听（引擎 start 时调用）。
     */
    public void activate() {
        this.active = true;
        syncRegistrations();
    }

    /**
     * 停用（引擎 stop 时调用；已注册监听变空操作）。
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 按事件类型注册 Nukkit 监听（NORMAL 优先级；同一类型只注册一次）。
     */
    private synchronized void syncRegistrations() {
        if (plugin == null) {
            return;
        }
        register(EntitySpawnEvent.class, false, e -> onEntitySpawn(((EntitySpawnEvent) e).getEntity()));
        register(EntityDespawnEvent.class, false, e -> onEntityRemove(((EntityDespawnEvent) e).getEntity()));
        register(PlayerJoinEvent.class, false, e -> onPlayerJoin(((PlayerJoinEvent) e).getPlayer()));
        // 破坏事件 ignoreCancelled=true：被取消的破坏不卸载（收养/对账负责自愈）
        register(BlockBreakEvent.class, true, e -> onBlockBreak(((BlockBreakEvent) e).getBlock()));
        register(ChunkLoadEvent.class, false, e -> onChunkLoad(((ChunkLoadEvent) e).getChunk()));
        register(ChunkUnloadEvent.class, false, e -> onChunkUnload(((ChunkUnloadEvent) e).getChunk()));
    }

    /**
     * 注册单事件类型（active 时转发到处理器）。
     */
    private void register(Class<? extends Event> type, boolean ignoreCancelled, Consumer<Event> handler) {
        if (registered.contains(type)) {
            return;
        }
        registered.add(type);
        Server.getInstance().getPluginManager().registerEvent(type, this, EventPriority.NORMAL,
                (listener, event) -> {
                    if (active) {
                        handler.accept(event);
                    }
                }, plugin, ignoreCancelled);
    }

    // ==================== 分发入口（可直调，单测/实机共用） ====================

    /**
     * 实体生成（进服/自然生成/掉落/插件生成）→ 进入管线。
     *
     * @param entity 生成的实体
     */
    public void onEntitySpawn(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            engine.enterNukkitEntity(entity);
        } catch (Exception e) {
            EcsLog.error("实体生成跟踪异常: " + entity, e);
        }
    }

    /**
     * 实体移除 → 卸载管线。
     *
     * @param entity 移除的实体
     */
    public void onEntityRemove(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            engine.removeByBinding(entity);
        } catch (Exception e) {
            EcsLog.error("实体移除跟踪异常: " + entity, e);
        }
    }

    /**
     * 玩家进服 → 幂等进入（EntitySpawnEvent 的补充兜底）。
     *
     * @param player 玩家
     */
    public void onPlayerJoin(Object player) {
        if (player instanceof Entity entity) {
            onEntitySpawn(entity);
        }
    }

    /**
     * 方块破坏 → 事件时即卸载该位置的方块实体绑定（零延迟；世界移除虽在其后，
     * "确定没了"语义成立，onUnmount 可读到尚存箱子做清理）。
     *
     * @param block 被破坏的方块
     */
    public void onBlockBreak(Block block) {
        if (block == null) {
            return;
        }
        try {
            BlockEntity blockEntity = block.getLevelBlockEntity();
            if (blockEntity != null) {
                engine.removeByBinding(blockEntity);
            }
        } catch (Exception e) {
            EcsLog.error("方块破坏跟踪异常: " + block, e);
        }
    }

    /**
     * 区块加载 → 批量进入该区块实体 + 方块实体（覆盖区块加载的存量箱；与 spawn 事件幂等重合无害）。
     *
     * @param chunk 加载的区块
     */
    public void onChunkLoad(FullChunk chunk) {
        if (chunk == null) {
            return;
        }
        for (Entity entity : chunk.getEntities().values()) {
            onEntitySpawn(entity);
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            try {
                engine.enterBlockEntity(blockEntity);
            } catch (Exception e) {
                EcsLog.error("方块实体跟踪异常: " + blockEntity, e);
            }
        }
    }

    /**
     * 区块卸载 → 批量移除该区块绑定（与 despawn 事件幂等重合无害）。
     *
     * @param chunk 卸载的区块
     */
    public void onChunkUnload(FullChunk chunk) {
        if (chunk == null) {
            return;
        }
        for (Entity entity : chunk.getEntities().values()) {
            onEntityRemove(entity);
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            try {
                engine.removeByBinding(blockEntity);
            } catch (Exception e) {
                EcsLog.error("方块实体卸载跟踪异常: " + blockEntity, e);
            }
        }
    }
}
