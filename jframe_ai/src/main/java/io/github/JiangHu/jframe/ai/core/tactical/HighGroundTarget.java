package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 高地目标:每次解析时扫描一个相对自身有高度优势的位置。
 * <p>
 * 战术 Target 适配器——链式配置对象 + 计算委托:构造只绑定自身实体,
 * 战术参数(半径、最小优势)全部链式设置、可变、带默认值;{@link #get()} 内委托
 * {@link TacticalScanner#findHighGround(Entity, double, int)} 实时计算。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * HighGroundTarget high = new HighGroundTarget(skeleton).radius(12).minAdvantage(2);
 * PlannedPath plan = ai.walk(skeleton).to(high).compute();  // 此刻解析
 * high.radius(6);   // 中途收紧搜索半径,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class HighGroundTarget implements Target {

    /** 默认最小高度优势(方块) */
    public static final int DEFAULT_MIN_ADVANTAGE = 1;

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile double radius = TacticalScanner.DEFAULT_RADIUS;
    private volatile int minAdvantage = DEFAULT_MIN_ADVANTAGE;

    /**
     * 创建高地目标。
     *
     * @param self 需要抢占高地的实体(构造绑定,不可变)
     */
    public HighGroundTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建高地目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    需要抢占高地的实体
     */
    public HighGroundTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:搜索半径。
     *
     * @param radius 搜索半径(方块)
     * @return this
     */
    public HighGroundTarget radius(double radius) {
        this.radius = radius;
        return this;
    }

    /**
     * 链式设置/中途修改:最小高度优势。
     *
     * @param minAdvantage 候选位置须高于当前位置的最小方块数
     * @return this
     */
    public HighGroundTarget minAdvantage(int minAdvantage) {
        this.minAdvantage = minAdvantage;
        return this;
    }

    /**
     * @return 高地位置快照;扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        TacticalPosition pos = scanner.findHighGround(self, radius, minAdvantage);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
