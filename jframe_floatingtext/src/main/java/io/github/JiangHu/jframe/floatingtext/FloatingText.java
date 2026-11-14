package io.github.JiangHu.jframe.floatingtext;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.floatingtext.animation.FloatingTextAnimation;
import io.github.JiangHu.jframe.floatingtext.viewers.Viewers;

/**
 * 浮空字句柄 — 一条世界空间文字的逻辑对象（Builder 构造，{@code FloatingTextAPI.show} 注册后生效）。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>瞬态（{@code lifetimeTicks > 0}）：寿命倒数 + 动画驱动，到期自动回收；</li>
 *   <li>常驻（{@code lifetimeTicks <= 0}）：直到显移除；玩家重连/重生/换世界自动补发。</li>
 * </ul>
 *
 * <h3>中途操作（下游组合业务的支点）</h3>
 * {@link #setText(String)} / {@link #move(Position)} / {@link #remove()} ——
 * 合并伤害、排行榜刷新、倒计时牌等业务全部由这三个操作组合（模块不内置业务语义）。
 *
 * <h3>动画写回面</h3>
 * {@link #offset(double, double, double)} / {@link #setText(String)} —— 由动画实现调用，
 * 管理器按 {@code moveIntervalTicks} 节流统一提交。
 */
public final class FloatingText {

    private final String text;
    private Position position;
    private PositionSource positionSource;
    private final FloatingTextAnimation animation;
    private final int lifetimeTicks;
    private final Viewers viewers;
    private final String tag;
    private final int moveIntervalTicks;
    private final String levelNameOverride;

    // ---- 注册后运行时状态（仅管理器写） ----
    private FloatingTextManager manager;
    private long id = -1;
    private long handle = -1;
    private boolean registered;
    private boolean valid = true;
    private int age;
    private boolean animationDone;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private String currentText;
    private boolean textDirty;
    private String lastSentText;
    private double lastSentX;
    private double lastSentY;
    private double lastSentZ;
    private boolean everSentPosition;
    private String resolvedLevelName;

    private FloatingText(Builder builder) {
        this.text = builder.text;
        this.position = builder.position;
        this.positionSource = builder.positionSource;
        this.animation = builder.animation;
        this.lifetimeTicks = builder.lifetimeTicks;
        this.viewers = builder.viewers;
        this.tag = builder.tag;
        this.moveIntervalTicks = builder.moveIntervalTicks;
        this.levelNameOverride = builder.levelName;
        this.currentText = builder.text;
    }

    /**
     * 新建 Builder（text 必填，position / positionSource 至少其一）。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 只读属性 ====================

    /**
     * 初始文本。
     *
     * @return 文本
     */
    public String text() {
        return text;
    }

    /**
     * 当前文本（含动画/中途 setText 修改）。
     *
     * @return 当前文本
     */
    public String currentText() {
        return currentText;
    }

    /**
     * 是否常驻（瞬态 false）。
     *
     * @return 常驻 true
     */
    public boolean persistent() {
        return lifetimeTicks <= 0;
    }

    /**
     * 寿命（tick；&le;0 = 常驻）。
     *
     * @return 寿命
     */
    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    /**
     * 通用标签（批量管理用）。
     *
     * @return 标签（可空）
     */
    public String tag() {
        return tag;
    }

    /**
     * 是否已注册且存活。
     *
     * @return 存活 true
     */
    public boolean valid() {
        return registered && valid;
    }

    /**
     * 注册 id（未注册 -1）。
     *
     * @return id
     */
    public long id() {
        return id;
    }

    /**
     * 渲染句柄（未注册 -1）。
     *
     * @return 句柄
     */
    public long handle() {
        return handle;
    }

    /**
     * 已存活拍数。
     *
     * @return 拍数
     */
    public int age() {
        return age;
    }

    /**
     * 动画实例（可空 = 静止）。
     *
     * @return 动画
     */
    public FloatingTextAnimation animation() {
        return animation;
    }

    /**
     * 观众策略。
     *
     * @return 策略（永不 null）
     */
    public Viewers viewers() {
        return viewers;
    }

    // ==================== 中途操作（业务 API + 动画写回共用） ====================

    /**
     * 修改文本（业务中途改字 / 动画颜色阶共用；按节流提交 SetEntityData）。
     *
     * @param newText 新文本
     */
    public void setText(String newText) {
        if (newText == null || newText.equals(currentText)) {
            return;
        }
        this.currentText = newText;
        this.textDirty = true;
    }

    /**
     * 写回动画位置偏移（<b>绝对累计值</b>，非增量；与位置源/静态位置相加）。
     *
     * @param dx X 偏移
     * @param dy Y 偏移
     * @param dz Z 偏移
     */
    public void offset(double dx, double dy, double dz) {
        this.offsetX = dx;
        this.offsetY = dy;
        this.offsetZ = dz;
    }

    /**
     * 中途移动（替换静态位置并清除动态源；下一节流周期提交 Move）。
     *
     * @param newPosition 新位置
     */
    public void move(Position newPosition) {
        if (newPosition == null) {
            return;
        }
        this.position = newPosition;
        this.positionSource = null;
    }

    /**
     * 主动移除（幂等）。
     */
    public void remove() {
        if (manager != null && valid) {
            manager.removeInternal(this);
        }
    }

    // ==================== 位置解析（管理器用） ====================

    /**
     * 解析当前基准位置（动态源优先，其次静态位置；可空——注册时校验过）。
     *
     * @return 基准位置
     */
    Position basePosition() {
        return positionSource != null ? positionSource.position() : position;
    }

    /**
     * X 偏移。
     *
     * @return 偏移
     */
    double offsetX() {
        return offsetX;
    }

    /**
     * Y 偏移。
     *
     * @return 偏移
     */
    double offsetY() {
        return offsetY;
    }

    /**
     * Z 偏移。
     *
     * @return 偏移
     */
    double offsetZ() {
        return offsetZ;
    }

    /**
     * 动态源（可空）。
     *
     * @return 位置源
     */
    PositionSource positionSource() {
        return positionSource;
    }

    /**
     * 逐条节流间隔覆盖（&le;0 = 用管理器默认）。
     *
     * @return 间隔
     */
    int moveIntervalOverride() {
        return moveIntervalTicks;
    }

    /**
     * 显式 level 名覆盖（可空 = 注册时从位置解析）。
     *
     * @return 覆盖名
     */
    String levelNameOverride() {
        return levelNameOverride;
    }

    /**
     * 注册时解析的 level 名（null = 不限制；对账过滤用）。
     *
     * @return level 名
     */
    String resolvedLevelName() {
        return resolvedLevelName;
    }

    // ==================== 状态推进（仅管理器调用） ====================

    void attach(FloatingTextManager owner, long newId) {
        this.manager = owner;
        this.id = newId;
        this.registered = true;
        this.lastSentText = currentText;
        this.textDirty = false;
    }

    void markCreated(long renderHandle, double sentX, double sentY, double sentZ, String levelName) {
        this.handle = renderHandle;
        this.lastSentX = sentX;
        this.lastSentY = sentY;
        this.lastSentZ = sentZ;
        this.everSentPosition = true;
        this.resolvedLevelName = levelName;
    }

    void advanceAge() {
        age++;
    }

    void markAnimationDone() {
        animationDone = true;
    }

    boolean animationDone() {
        return animationDone;
    }

    boolean consumeTextDirty() {
        boolean dirty = textDirty && !currentText.equals(lastSentText);
        if (textDirty && currentText.equals(lastSentText)) {
            textDirty = false;
        }
        return dirty;
    }

    void recordSentText() {
        this.lastSentText = currentText;
        this.textDirty = false;
    }

    boolean positionChanged(double x, double y, double z) {
        return !everSentPosition || x != lastSentX || y != lastSentY || z != lastSentZ;
    }

    void recordSentPosition(double x, double y, double z) {
        this.lastSentX = x;
        this.lastSentY = y;
        this.lastSentZ = z;
        this.everSentPosition = true;
    }

    void invalidate() {
        this.valid = false;
    }

    /**
     * 构造器。
     */
    public static final class Builder {
        private String text = "";
        private Position position;
        private PositionSource positionSource;
        private FloatingTextAnimation animation;
        private int lifetimeTicks = 0;
        private Viewers viewers = Viewers.all();
        private String tag;
        private int moveIntervalTicks;
        private String levelName;

        private Builder() {
        }

        /**
         * 文本（Bedrock 颜色码直出；多行用 \n）。
         *
         * @param text 文本
         * @return self
         */
        public Builder text(String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        /**
         * 静态位置。
         *
         * @param position 位置
         * @return self
         */
        public Builder position(Position position) {
            this.position = position;
            return this;
        }

        /**
         * 动态位置源（每节流周期重新解算；设置后静态位置仅作初值参考）。
         *
         * @param source 位置源
         * @return self
         */
        public Builder positionSource(PositionSource source) {
            this.positionSource = source;
            return this;
        }

        /**
         * 动画（null = none）。
         *
         * @param animation 动画
         * @return self
         */
        public Builder animation(FloatingTextAnimation animation) {
            this.animation = animation;
            return this;
        }

        /**
         * 寿命（tick；<b>省略 / &le;0 = 常驻</b>，直到显式移除；瞬态必填正寿命）。
         *
         * @param ticks 拍数
         * @return self
         */
        public Builder lifetimeTicks(int ticks) {
            this.lifetimeTicks = ticks;
            return this;
        }

        /**
         * 观众策略（默认全体）。
         *
         * @param viewers 策略
         * @return self
         */
        public Builder viewers(Viewers viewers) {
            this.viewers = viewers == null ? Viewers.all() : viewers;
            return this;
        }

        /**
         * 通用标签（批量移除用）。
         *
         * @param tag 标签
         * @return self
         */
        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        /**
         * 逐条覆盖位置节流间隔（&le;0 = 用管理器全局默认 2；爆炸碎片类设 1 = 每 tick 更新）。
         *
         * @param ticks 间隔
         * @return self
         */
        public Builder moveIntervalTicks(int ticks) {
            this.moveIntervalTicks = ticks;
            return this;
        }

        /**
         * 显式 level 名（可空 = 注册时从位置的 level 解析；null = 不限制对账过滤）。
         * <p>位置源返回无 level 的 Position 时，用本参数固定条目所属世界。
         *
         * @param name level 名
         * @return self
         */
        public Builder levelName(String name) {
            this.levelName = name;
            return this;
        }

        /**
         * 构建（注册由 {@code FloatingTextAPI.show} 完成）。
         *
         * @return 浮空字
         */
        public FloatingText build() {
            return new FloatingText(this);
        }
    }

    /**
     * 位置源 — 静态坐标与动态位置（跟随实体、下游解算弹道）的统一抽象。
     */
    @FunctionalInterface
    public interface PositionSource {

        /**
         * 解析当前位置。
         *
         * @return 位置
         */
        Position position();
    }
}
