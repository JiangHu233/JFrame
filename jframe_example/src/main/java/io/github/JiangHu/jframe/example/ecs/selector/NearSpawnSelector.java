package io.github.JiangHu.jframe.example.ecs.selector;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.selector.EntitySelector;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSelector;

/**
 * 演示选择器 —— 出生点距离筛选（自定义注册类 + 参数表）。
 *
 * <p>YAML 引用（guardian-chests 定义）：
 * <pre>{@code
 * selector: { use: near-spawn, params: { distance: 64.0, tag: chest } }
 * }</pre>
 *
 * <p>matches 语义：可选先按 tag 过滤（tag 参数依赖 all-chests 定义先挂上 chest 标签，
 * 因此 YAML 中 guardian-chests 必须声明在 all-chests 之后）；再判定实体位置距
 * 所在世界出生点 ≤ distance。null 防护：虚拟实体 / 无位置的实体不命中。
 */
@EcsSelector("near-spawn")
public class NearSpawnSelector implements EntitySelector {

    /** 距离阈值（params.distance 注入） */
    private double distance = 32;

    /** 可选标签过滤（params.tag 注入；未配置保留 null = 不过滤） */
    private String tag;

    @Override
    public boolean matches(EcsEntity entity) {
        if (tag != null && !entity.hasTag(tag)) {
            return false;
        }
        // 代理引用判型：BlockEntity 自身即 Position，Nukkit 实体取其当前位置
        Position position;
        if (entity.binding() instanceof cn.nukkit.blockentity.BlockEntity blockEntity) {
            position = blockEntity;
        } else if (entity.binding() instanceof cn.nukkit.entity.Entity nukkitEntity) {
            position = nukkitEntity.getPosition();
        } else {
            return false; // 虚拟实体 / 其他代理类型无位置
        }
        return position.getLevel() != null
                && position.getLevel().getSpawnLocation().distance(position) <= distance;
    }
}
