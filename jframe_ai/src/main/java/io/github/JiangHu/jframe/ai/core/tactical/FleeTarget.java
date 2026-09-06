package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 逃离目标:每次解析时扫描一个远离 {@code threat} 的安全位置。
 * <p>
 * 战术 Target 适配器——链式配置对象 + 计算委托:构造只绑定自身实体,
 * 战术参数(威胁、半径)全部链式设置、可变、带默认值;{@link #get()} 内委托
 * {@link TacticalScanner#findFleePosition(Entity, Entity, double)} 实时计算。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * FleeTarget flee = new FleeTarget(zombie).threat(enemyA).radius(10);
 * PlannedPath plan = ai.walk(zombie).to(flee).compute();  // 此刻解析
 * flee.threat(enemyB);   // 中途改主意:逃离的威胁 A → B,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class FleeTarget implements Target {

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile Entity threat;
    private volatile double radius = TacticalScanner.DEFAULT_RADIUS;

    /**
     * 创建逃离目标。
     *
     * @param self 需要逃离的实体(构造绑定,不可变)
     */
    public FleeTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建逃离目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    需要逃离的实体
     */
    public FleeTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:要远离的威胁。
     *
     * @param threat 威胁实体;{@code null} 表示目标失效({@link #get()} 返回 null)
     * @return this
     */
    public FleeTarget threat(Entity threat) {
        this.threat = threat;
        return this;
    }

    /**
     * 链式设置/中途修改:逃离搜索半径。
     *
     * @param radius 搜索半径(方块)
     * @return this
     */
    public FleeTarget radius(double radius) {
        this.radius = radius;
        return this;
    }

    /**
     * @return 逃离位置快照;未设置威胁或扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Entity t = threat;
        if (t == null) {
            return null;
        }
        TacticalPosition pos = scanner.findFleePosition(self, t, radius);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
