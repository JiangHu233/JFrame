package io.github.JiangHu.jframe.example.ecs.component;

import cn.nukkit.Server;
import io.github.JiangHu.jframe.engine.ecs.entity.ComponentBase;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsComponent;

/**
 * 演示组件 E —— 波次状态（挂在纯虚拟 gameobject wave-manager 上）。
 *
 * <p>覆盖特性：纯虚拟实体（spawn: virtual，无世界对象）+ 参数注入（wave/mobsLeft）。
 * 宿主实体由 {@code /ecs wave} 自动补建，{@code WaveSystem} 读写本组件驱动波次结算。
 *
 * <p>lastAnnounced 为<b>运行时字段</b>（不在 YAML params 中 → 永远保留初始值 0），
 * 用于去重广播，同时演示"未出现的字段保留代码初始值"。
 */
@EcsComponent("wave-state")
public class WaveStateComponent extends ComponentBase {

    /** 当前波次（params.wave 注入） */
    private int wave = 1;

    /** 剩余幽灵数（params.mobsLeft 注入；运行时由 WaveSystem 维护） */
    private int mobsLeft;

    /** 上次广播的剩余数（运行时字段，去重广播用） */
    private int lastAnnounced;

    @Override
    public void onMount() {
        Server.getInstance().getLogger().info("[ECS示例] 波次管理器就绪（wave=" + wave + "）");
    }

    /**
     * 当前波次。
     *
     * @return 波次号
     */
    public int wave() {
        return wave;
    }

    /**
     * 剩余幽灵数。
     *
     * @return 数量
     */
    public int mobsLeft() {
        return mobsLeft;
    }

    /**
     * 更新剩余幽灵数。
     *
     * @param alive 存活幽灵数
     */
    public void setMobsLeft(int alive) {
        this.mobsLeft = alive;
    }

    /**
     * 进入下一波。
     */
    public void nextWave() {
        wave++;
    }

    /**
     * 上次广播的剩余数。
     *
     * @return 数量
     */
    public int lastAnnounced() {
        return lastAnnounced;
    }

    /**
     * 记录广播过的剩余数。
     *
     * @param value 数量
     */
    public void setLastAnnounced(int value) {
        this.lastAnnounced = value;
    }
}
