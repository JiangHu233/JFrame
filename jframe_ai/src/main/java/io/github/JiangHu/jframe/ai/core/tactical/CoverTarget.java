package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 掩体目标:每次解析时扫描一个能躲避 {@code threat} 视线的掩体位置。
 * <p>
 * 战术 Target 适配器——链式配置对象 + 计算委托:构造只绑定自身实体,
 * 战术参数(威胁、半径)全部链式设置、可变、带默认值;{@link #get()} 内委托
 * {@link TacticalScanner#findCover(Entity, Entity, double)} 实时计算。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * CoverTarget cover = new CoverTarget(zombie).threat(enemyA).radius(8);
 * PlannedPath plan = ai.walk(zombie).to(cover).compute();  // 此刻解析
 * cover.threat(enemyB);   // 中途改主意:围绕的敌人 A → B,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class CoverTarget implements Target {

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile Entity threat;
    private volatile double radius = TacticalScanner.DEFAULT_RADIUS;

    /**
     * 创建掩体目标。
     *
     * @param self 需要寻找掩体的实体(构造绑定,不可变)
     */
    public CoverTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建掩体目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    需要寻找掩体的实体
     */
    public CoverTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:要躲避的威胁。
     *
     * @param threat 威胁实体;{@code null} 表示目标失效({@link #get()} 返回 null)
     * @return this
     */
    public CoverTarget threat(Entity threat) {
        this.threat = threat;
        return this;
    }

    /**
     * 链式设置/中途修改:掩体搜索半径。
     *
     * @param radius 搜索半径(方块)
     * @return this
     */
    public CoverTarget radius(double radius) {
        this.radius = radius;
        return this;
    }

    /**
     * @return 掩体位置快照;未设置威胁或扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Entity t = threat;
        if (t == null) {
            return null;
        }
        TacticalPosition pos = scanner.findCover(self, t, radius);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
