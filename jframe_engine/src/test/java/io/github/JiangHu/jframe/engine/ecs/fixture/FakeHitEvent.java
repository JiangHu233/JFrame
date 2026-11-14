package io.github.JiangHu.jframe.engine.ecs.fixture;

/**
 * 测试用打击事件（damage 字段供 when SpEL / filter 读取）。
 */
public class FakeHitEvent extends FakeEvent {

    /** 伤害值 */
    public final int damage;

    /**
     * 构造事件。
     *
     * @param damage 伤害值
     */
    public FakeHitEvent(int damage) {
        this.damage = damage;
    }
}
