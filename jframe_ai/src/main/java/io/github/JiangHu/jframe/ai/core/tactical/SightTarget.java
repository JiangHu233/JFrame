package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 观察目标:每次解析时扫描一个既能看到 {@code target}、又保持理想距离的射击/观察位。
 * <p>
 * 战术 Target 适配器——链式配置对象 + 计算委托:构造只绑定自身实体,
 * 战术参数(对象、半径、理想距离)全部链式设置、可变、带默认值;{@link #get()} 内委托
 * {@link TacticalScanner#findSightPosition(Entity, Entity, double, double)} 实时计算。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * SightTarget sight = new SightTarget(skeleton).target(playerA).idealDistance(8);
 * PlannedPath plan = ai.walk(skeleton).to(sight).compute();  // 此刻解析
 * sight.target(playerB);   // 中途改主意:观察对象 A → B,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class SightTarget implements Target {

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile Entity target;
    private volatile double radius = TacticalScanner.DEFAULT_RADIUS;
    private volatile double idealDistance = TacticalScanner.DEFAULT_IDEAL_SIGHT_DISTANCE;

    /**
     * 创建观察目标。
     *
     * @param self 需要占据观察位的实体(构造绑定,不可变)
     */
    public SightTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建观察目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    需要占据观察位的实体
     */
    public SightTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:观察对象。
     *
     * @param target 观察对象实体;{@code null} 表示目标失效({@link #get()} 返回 null)
     * @return this
     */
    public SightTarget target(Entity target) {
        this.target = target;
        return this;
    }

    /**
     * 链式设置/中途修改:搜索半径。
     *
     * @param radius 搜索半径(方块)
     * @return this
     */
    public SightTarget radius(double radius) {
        this.radius = radius;
        return this;
    }

    /**
     * 链式设置/中途修改:理想观察距离。
     *
     * @param idealDistance 与观察对象的理想距离(方块,评分最优点)
     * @return this
     */
    public SightTarget idealDistance(double idealDistance) {
        this.idealDistance = idealDistance;
        return this;
    }

    /**
     * @return 观察位快照;未设置对象或扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Entity t = target;
        if (t == null) {
            return null;
        }
        TacticalPosition pos = scanner.findSightPosition(self, t, radius, idealDistance);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
