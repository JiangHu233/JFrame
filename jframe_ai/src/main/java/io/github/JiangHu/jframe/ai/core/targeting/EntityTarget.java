package io.github.JiangHu.jframe.ai.core.targeting;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;

import java.util.Objects;

/**
 * 实体目标:目标位置 = 绑定实体的当前位置(支持移动目标)。
 * <p>
 * 每次解析({@link #get()})读取实体<b>此刻</b>的坐标并返回快照——
 * 持续行为(chase 等)每轮重搜都会拿到最新位置,天然追踪移动目标。
 *
 * <h3>链式可变语义</h3>
 * <p>构造绑定初始实体,{@link #entity(Entity)} 可中途换目标(活引用语义:下次解析生效):
 *
 * <pre>{@code
 * EntityTarget quarry = new EntityTarget(playerA);
 * ai.chase(zombie).target(quarry).interval(10).start();
 * quarry.entity(playerB);   // 中途换追击目标,无需重启 chase
 * }</pre>
 */
public final class EntityTarget implements Target {

    private volatile Entity entity;

    /**
     * 创建实体目标。
     *
     * @param entity 目标实体;{@code null} 表示暂无目标({@link #get()} 返回 null)
     */
    public EntityTarget(Entity entity) {
        this.entity = entity;
    }

    /**
     * 链式设置/中途更换目标实体。
     *
     * @param entity 新目标实体;{@code null} 表示目标失效
     * @return this
     */
    public EntityTarget entity(Entity entity) {
        this.entity = entity;
        return this;
    }

    /**
     * @return 实体当前位置快照(脚部坐标);实体已死亡/未设置时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Entity e = entity;
        if (e == null || e.closed || e.getLevel() == null) {
            return null;
        }
        return new Vector3(e.x, e.y, e.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntityTarget that)) {
            return false;
        }
        return Objects.equals(entity, that.entity);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(entity);
    }

    @Override
    public String toString() {
        return "EntityTarget{" + entity + '}';
    }
}
