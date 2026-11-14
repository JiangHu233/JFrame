package io.github.JiangHu.jframe.example.ecs.component;

import cn.nukkit.Server;
import cn.nukkit.event.entity.EntityDamageEvent;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;
import io.github.JiangHu.jframe.engine.ecs.annotation.OnEcsEvent;

/**
 * 演示组件 B —— 精英（挂到所有 {@code EntityZombie} 上，见 elite-zombies 定义）。
 *
 * <p>覆盖特性：
 * <ul>
 *   <li>事件桥内置解析器 entity-events（EntityEvent → 宿主实体）</li>
 *   <li><b>方法内首行守卫</b> —— 伤害超过阈值才触发（直接读 this 字段 hitThreshold，无外部机制）</li>
 * </ul>
 *
 * <p>实机预期：打一下僵尸（伤害 &gt; 阈值 0，含空手）→ 全服看到"精英受到重击"；
 * 名牌显血由 {@code EliteTagSystem} 每秒刷新（系统 query 演示）。
 * 挂载/卸载有控制台日志（与其余演示组件一致）——否则"僵尸挂没挂上"无法从日志判断，
 * 而 selector 不命中（如 MOT 用尸壳/溺尸蛋，见下）恰恰是本场景最常见的事故源。
 */
@EcsComponent("elite")
public class EliteComponent extends ComponentBase {

    /** 重击判定阈值（params.hitThreshold 注入；0 = 任何一击含空手即触发） */
    private double hitThreshold = 0;

    /** 上次广播的血量百分比（运行时字段，非 YAML 参数 → 恒保留初始值；EliteTagSystem 判"变化才刷新"用） */
    private int lastPercent;

    @Override
    public void onMount() {
        Object binding = host().binding();
        String type = binding == null ? "null" : binding.getClass().getName();
        Server.getInstance().getLogger().info("[ECS示例] 精英挂载 #" + host().id() + "（type=" + type + "）");
    }

    @Override
    public void onUnmount() {
        Server.getInstance().getLogger().info("[ECS示例] 精英卸载 #" + host().id());
    }

    /**
     * 取重击阈值。
     *
     * @return 阈值
     */
    public double hitThreshold() {
        return hitThreshold;
    }

    /**
     * 受击事件 —— 方法内首行守卫：伤害超过阈值才继续。
     */
    @OnEcsEvent
    public void onHit(EntityDamageEvent event) {
        if (event.getDamage() <= hitThreshold) {
            return; // 守卫：直接读 this 字段
        }
        Server.getInstance().broadcastMessage("§c精英 §e#" + host().id()
                + " §c受到重击（§e" + event.getDamage() + " §c伤害）");
    }

    /**
     * 上次广播的血量百分比。
     *
     * @return 百分比（0 表示尚未广播过）
     */
    public int lastPercent() {
        return lastPercent;
    }

    /**
     * 记录已广播的百分比。
     *
     * @param percent 百分比
     */
    public void setLastPercent(int percent) {
        this.lastPercent = percent;
    }
}
