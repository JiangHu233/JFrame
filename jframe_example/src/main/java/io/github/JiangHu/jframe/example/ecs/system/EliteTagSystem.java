package io.github.JiangHu.jframe.example.ecs.system;

import cn.nukkit.entity.Entity;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.system.EcsSystem;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSystemAnno;
import io.github.JiangHu.jframe.example.ecs.component.EliteComponent;

/**
 * 演示系统一 —— 精英名牌显血（每秒）。
 *
 * <p>覆盖特性：{@code @EcsSystemAnno} + interval（20 tick = 1 秒）+
 * 倒排索引 query（只遍历挂了 elite 组件的实体，不扫全服）。
 *
 * <h3>⚠ Nukkit 名牌坑（本系统踩过）</h3>
 * <p>
 * {@code Entity.setNameTag(String)} <b>只赋字段、不向已看到该实体的玩家广播</b>——
 * 名牌内容随生成包（AddEntityPacket）下发，改字段不重发。因此每次更新后必须
 * {@code spawnToAll()} 重发生成包，否则血量永远"看不见变化"。
 * 为避免每秒重发闪烁/开销，仅在百分比<b>变化时</b>更新（lastPercent 判重）。
 *
 * <p>实机预期：任意方式刷一只僵尸 → 名牌 1 秒内出现"§c精英 §7HP 100%"；
 * 打它 → 下一次刷新显示新百分比。
 */
@EcsSystemAnno
public class EliteTagSystem implements EcsSystem {

    @Override
    public int interval() {
        return 20;
    }

    @Override
    public void tick(EcsEngine engine) {
        for (EcsEntity entity : engine.query(EliteComponent.class)) {
            if (!(entity.binding() instanceof Entity nkEntity) || !nkEntity.isAlive()) {
                continue;
            }
            EliteComponent elite = entity.get(EliteComponent.class);
            int percent = (int) Math.ceil(nkEntity.getHealth() * 100.0 / nkEntity.getMaxHealth());
            if (percent == elite.lastPercent()) {
                continue; // 变化才更新，避免每秒重发
            }
            elite.setLastPercent(percent);
            nkEntity.setNameTag("§c精英 §7HP " + percent + "%");
            nkEntity.setNameTagVisible(true);
            nkEntity.spawnToAll();   // 重发生成包（setNameTag 不广播——见类 javadoc）
        }
    }
}
