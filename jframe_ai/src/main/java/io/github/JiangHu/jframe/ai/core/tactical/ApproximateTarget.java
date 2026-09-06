package io.github.JiangHu.jframe.ai.core.tactical;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.core.targeting.Target;

/**
 * 模糊位置目标:每次解析时在"只知大致范围"的中心附近选取一个可到达的位置。
 * <p>
 * 适用于目标位置不确定(声音来源、最后目击点等)的场景——委托
 * {@link TacticalScanner#findApproximatePosition(Entity, Vector3, double)} 在
 * 不确定半径内找一个可站立的近似点。
 *
 * <h3>活引用语义</h3>
 * <pre>{@code
 * ApproximateTarget guess = new ApproximateTarget(zombie).center(lastSeen).uncertaintyRadius(8);
 * PlannedPath plan = ai.walk(zombie).to(guess).compute();  // 此刻解析
 * guess.center(newLastSeen);   // 情报更新,下次解析生效
 * }</pre>
 *
 * <h3>线程约定</h3>
 * <p>参数修改与 {@link #get()} 解析若跨线程并发,由调用方自行同步(建议主线程改参数);
 * {@code self} 绑定不可变,保证 Target 可安全跨线程传递。
 */
public final class ApproximateTarget implements Target {

    private final TacticalScanner scanner;
    private final Entity self;
    private volatile Vector3 center;
    private volatile double uncertaintyRadius = TacticalScanner.DEFAULT_UNCERTAINTY_RADIUS;

    /**
     * 创建模糊位置目标。
     *
     * @param self 需要前往模糊位置的实体(构造绑定,不可变)
     */
    public ApproximateTarget(Entity self) {
        this(new TacticalScanner(), self);
    }

    /**
     * 创建模糊位置目标(指定扫描引擎)。
     *
     * @param scanner 战术扫描引擎
     * @param self    需要前往模糊位置的实体
     */
    public ApproximateTarget(TacticalScanner scanner, Entity self) {
        this.scanner = scanner;
        this.self = self;
    }

    /**
     * 链式设置/中途修改:模糊中心(大致位置)。
     *
     * @param center 模糊中心;{@code null} 表示目标失效({@link #get()} 返回 null)
     * @return this
     */
    public ApproximateTarget center(Vector3 center) {
        this.center = center;
        return this;
    }

    /**
     * 链式设置/中途修改:不确定半径。
     *
     * @param uncertaintyRadius 真实位置可能偏离中心的半径(方块)
     * @return this
     */
    public ApproximateTarget uncertaintyRadius(double uncertaintyRadius) {
        this.uncertaintyRadius = uncertaintyRadius;
        return this;
    }

    /**
     * @return 近似位置快照;未设置中心或扫描失败时返回 {@code null}(无有效目标)
     */
    @Override
    public Vector3 get() {
        Vector3 c = center;
        if (c == null) {
            return null;
        }
        TacticalPosition pos = scanner.findApproximatePosition(self, c, uncertaintyRadius);
        return pos != null && pos.isPresent() ? pos.position() : null;
    }
}
