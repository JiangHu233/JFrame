package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 包抄目标:每次解析时扫描一个绕到 {@code target} 侧后方的进攻位置。
 * <p>
 * 战术 Target 适配器——链式配置对象 + 计算委托:构造只绑定自身实体,
 * 战术参数(目标、半径)全部链式设置、可变、带默认值;{@link #get()} 内委托
 * {@link TacticalScanner#findFlankPosition(Entity, Entity, double)} 实时计算。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * FlankTarget flank = new FlankTarget(zombie).target(playerA).radius(6);
 * PlannedPath plan = ai.walk(zombie).to(flank).compute();  // 此刻解析
 * flank.target(playerB);   // 中途改主意:包抄对象 A → B,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class FlankTarget implements Target {

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile Entity target;
    private volatile double radius = TacticalScanner.DEFAULT_RADIUS;

    /**
     * 创建包抄目标。
     *
     * @param self 执行包抄的实体(构造绑定,不可变)
     */
    public FlankTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建包抄目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    执行包抄的实体
     */
    public FlankTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:包抄对象。
     *
     * @param target 包抄对象实体;{@code null} 表示目标失效({@link #get()} 返回 null)
     * @return this
     */
    public FlankTarget target(Entity target) {
        this.target = target;
        return this;
    }

    /**
     * 链式设置/中途修改:包抄距离。
     *
     * @param radius 包抄点距目标的距离(方块)
     * @return this
     */
    public FlankTarget radius(double radius) {
        this.radius = radius;
        return this;
    }

    /**
     * @return 包抄位置快照;未设置对象或扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Entity t = target;
        if (t == null) {
            return null;
        }
        TacticalPosition pos = scanner.findFlankPosition(self, t, radius);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
