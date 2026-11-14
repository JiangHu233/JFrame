package io.github.JiangHu.jframe.engine.ecs.world;

import cn.nukkit.Server;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsLog;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 世界扫描器（<b>低频对账</b>）— 事件驱动跟踪（{@link NukkitWorldTracker}）的正确性兜底。
 *
 * <h3>职责演变</h3>
 * <p>
 * 主跟踪路径已事件化（实体生成/移除、区块加载/卸载、方块破坏即时卸载、未触碰方块实体由
 * {@code EcsEngine.resolveCandidate} 惰性收养）。本类从"每 tick 全量 diff"降级为
 * 每 {@link #RECONCILE_INTERVAL_TICKS} tick 一次的<b>对账</b>：纠正绕过事件的路径
 * （如插件直接 setBlock 放置方块实体）与可能的事件遗漏，100 tick（约 5 秒）内自愈。
 * {@code start()} 时的首帧全量快照（存量基线）职责不变。
 */
public class NukkitWorldScanner {

    /** 对账间隔（tick）——事件遗漏/绕事件路径的自愈周期 */
    public static final int RECONCILE_INTERVAL_TICKS = 100;

    /** 引擎（enter/remove 管线入口） */
    private final EcsEngine engine;

    /** 上一轮快照（Entity + BlockEntity，引用相等） */
    private Set<Object> snapshot = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * 构造扫描器。
     *
     * @param engine 引擎
     */
    public NukkitWorldScanner(EcsEngine engine) {
        this.engine = engine;
    }

    /**
     * 执行一轮扫描（无 Server 环境下为空操作，便于单测）。
     */
    public void scanAll() {
        Server server = Server.getInstance();
        if (server == null) {
            return;
        }
        scan(server.getLevels().values());
    }

    /**
     * 对指定 level 集合做快照 diff 并驱动 enter/remove 管线。
     *
     * @param levels level 集合
     */
    public void scan(Iterable<Level> levels) {
        Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Level level : levels) {
            for (Entity entity : level.getEntities()) {
                if (!entity.isClosed()) {
                    current.add(entity);
                }
            }
            for (BlockEntity blockEntity : level.getBlockEntities().values()) {
                if (!blockEntity.closed) {
                    current.add(blockEntity);
                }
            }
        }
        applyDiff(snapshot, current);
        snapshot = current;
    }

    /**
     * 应用 diff：新增进 enter 管线、消失进 remove 管线。
     *
     * @param previous 上一轮快照
     * @param current  本轮快照
     */
    private void applyDiff(Set<Object> previous, Set<Object> current) {
        for (Object added : current) {
            if (!previous.contains(added)) {
                enter(added);
            }
        }
        for (Object removed : previous) {
            if (!current.contains(removed)) {
                engine.removeByBinding(removed);
            }
        }
    }

    /**
     * 按 Nukkit 对象类型走对应 enter 管线。
     */
    private void enter(Object nukkitObject) {
        try {
            if (nukkitObject instanceof Entity entity) {
                engine.enterNukkitEntity(entity);
            } else if (nukkitObject instanceof BlockEntity blockEntity) {
                engine.enterBlockEntity(blockEntity);
            }
        } catch (Exception e) {
            EcsLog.error("实体进入管线异常: " + nukkitObject, e);
        }
    }

    /**
     * 上一轮快照（测试观测用）。
     *
     * @return 快照集合
     */
    Set<Object> snapshot() {
        return snapshot;
    }
}
