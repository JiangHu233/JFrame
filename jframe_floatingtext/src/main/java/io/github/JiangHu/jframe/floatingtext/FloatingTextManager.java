package io.github.JiangHu.jframe.floatingtext;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.floatingtext.render.FloatingTextRenderer;
import io.github.JiangHu.jframe.floatingtext.render.FloatingTextRendererStats;
import io.github.JiangHu.jframe.floatingtext.render.TextVisual;
import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;
import io.github.JiangHu.jframe.floatingtext.viewers.ViewerDirectory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 浮空字管理器 — 注册表 + tick 驱动 + 上限保护 + 观众对账。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@link #show(FloatingText)}：注册 → 瞬态上限检查 → 解析初始观众 → 渲染器创建；</li>
 *   <li>{@link #tick()}：逐条推进动画/寿命（异常隔离），文本与位置按
 *       {@code moveIntervalTicks} 节流提交（none + 静止 = 零 Move）；周期对账兜底；</li>
 *   <li>{@link #resync(TextViewer)}：重连/重生/换世界后的幂等对账（仅常驻，瞬态不补）；</li>
 *   <li>{@link #onViewerQuit(UUID)}：离线清账本（渲染器 forget）。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * 全部方法仅主线程调用（由 {@code FloatingTextTicker} 驱动）。
 */
public class FloatingTextManager {

    /** 瞬态溢出策略 */
    public enum OverflowPolicy {
        /** 丢弃新请求（默认） */
        DROP_NEWEST,
        /** 回收最旧瞬态后放行新请求 */
        REPLACE_OLDEST
    }

    /**
     * 管理器指标。
     *
     * @param activeTransient  活跃瞬态数
     * @param activePersistent 活跃常驻数
     * @param groups           常驻组数
     * @param renderer         渲染器指标
     */
    public record Stats(int activeTransient, int activePersistent, int groups,
                        FloatingTextRendererStats renderer) {
    }

    private final FloatingTextRenderer renderer;
    private final ViewerDirectory directory;

    /** 瞬态上限与溢出策略 */
    private int maxActiveTransient = 400;
    private OverflowPolicy overflowPolicy = OverflowPolicy.DROP_NEWEST;

    /** 常驻组行数上限 */
    private int maxGroupLines = 64;

    /** 位置/文本提交节流默认间隔（tick） */
    private int defaultMoveIntervalTicks = 2;

    /** 周期对账间隔（tick；&le;0 关闭） */
    private int reconcileIntervalTicks = 100;

    /** 注册表（插入序，REPLACE_OLDEST 用） */
    private final Map<Long, FloatingText> active = new LinkedHashMap<>();

    /** 命名常驻组 */
    private final Map<String, FloatingTextGroup> groups = new LinkedHashMap<>();

    /** 下一注册 id */
    private long nextId = 1;

    /** tick 计数（周期对账用） */
    private long tickCounter;

    /**
     * 构造管理器。
     *
     * @param renderer  渲染器
     * @param directory 在线玩家目录
     */
    public FloatingTextManager(FloatingTextRenderer renderer, ViewerDirectory directory) {
        this.renderer = renderer;
        this.directory = directory;
    }

    // ==================== 配置 ====================

    /**
     * 瞬态上限。
     *
     * @param max 上限
     */
    public void setMaxActiveTransient(int max) {
        this.maxActiveTransient = Math.max(1, max);
    }

    /**
     * 溢出策略。
     *
     * @param policy 策略
     */
    public void setOverflowPolicy(OverflowPolicy policy) {
        this.overflowPolicy = policy == null ? OverflowPolicy.DROP_NEWEST : policy;
    }

    /**
     * 位置节流默认间隔。
     *
     * @param ticks 间隔
     */
    public void setDefaultMoveIntervalTicks(int ticks) {
        this.defaultMoveIntervalTicks = Math.max(1, ticks);
    }

    /**
     * 周期对账间隔。
     *
     * @param ticks 间隔（&le;0 关闭）
     */
    public void setReconcileIntervalTicks(int ticks) {
        this.reconcileIntervalTicks = ticks;
    }

    // ==================== 注册 ====================

    /**
     * 注册一条浮空字（Builder 构造后调用）。
     *
     * @param spec 浮空字
     * @return 已注册句柄；瞬态超限且策略为 DROP_NEWEST 时返回 null
     */
    public FloatingText show(FloatingText spec) {
        if (spec == null || spec.valid()) {
            return null;
        }
        Position base = spec.basePosition();
        if (base == null) {
            FloatingTextLog.warning("show 拒绝：position 与 positionSource 均缺失");
            return null;
        }
        if (!spec.persistent()) {
            if (!admitTransient()) {
                return null;
            }
        }
        long id = nextId++;
        spec.attach(this, id);
        active.put(id, spec);

        double x = base.x + spec.offsetX();
        double y = base.y + spec.offsetY();
        double z = base.z + spec.offsetZ();
        String levelName = levelNameOf(spec, base);
        List<TextViewer> targets = resolveViewers(spec, levelName);
        long handle = renderer.create(new TextVisual(spec.currentText(), x, y, z, levelName, targets));
        spec.markCreated(handle, x, y, z, levelName);
        return spec;
    }

    /**
     * 瞬态准入（超限按策略处理）。
     *
     * @return 放行 true
     */
    private boolean admitTransient() {
        int count = 0;
        for (FloatingText text : active.values()) {
            if (!text.persistent()) {
                count++;
            }
        }
        if (count < maxActiveTransient) {
            return true;
        }
        if (overflowPolicy == OverflowPolicy.REPLACE_OLDEST) {
            for (FloatingText text : active.values()) {
                if (!text.persistent()) {
                    removeInternal(text);
                    FloatingTextLog.info("瞬态超限：回收最旧 " + text.id());
                    return true;
                }
            }
            return true;   // 移除过程已清空（理论不可达）
        }
        FloatingTextLog.warning("瞬态超限：丢弃新请求（maxActive=" + maxActiveTransient + "）");
        return false;
    }

    // ==================== 常驻组 ====================

    /**
     * 创建命名常驻组的 Builder（重复命名 = 拆旧建新）。
     *
     * @param name     组名
     * @param position 位置
     * @return 组 Builder
     */
    public FloatingTextGroup.Builder group(String name, Position position) {
        return new FloatingTextGroup.Builder(this, name, position);
    }

    /**
     * 注册组（Builder.build 内部调用；重名 = 旧组行移除、条目出册后新组接管）。
     */
    FloatingTextGroup registerGroup(String name, FloatingTextGroup group) {
        FloatingTextGroup old = groups.remove(name);
        if (old != null) {
            old.detach();   // 仅移除旧组的行（名字已出册，直接走 detach 防递归）
        }
        groups.put(name, group);
        return group;
    }

    /**
     * 移除命名组。
     *
     * @param name 组名
     */
    public void removeGroup(String name) {
        FloatingTextGroup group = groups.remove(name);
        if (group != null) {
            group.detach();
        }
    }

    /**
     * 按标签批量移除（含组）。
     *
     * @param tag 标签
     */
    public void removeByTag(String tag) {
        for (FloatingText text : List.copyOf(active.values())) {
            if (tag.equals(text.tag())) {
                removeInternal(text);
            }
        }
        groups.entrySet().removeIf(entry -> !entry.getValue().text().valid());
    }

    // ==================== tick 驱动 ====================

    /**
     * 推进一拍（Ticker 每 tick 调用；含周期对账）。
     */
    public void tick() {
        tickCounter++;
        for (FloatingText text : List.copyOf(active.values())) {
            try {
                tickOne(text);
            } catch (Exception e) {
                FloatingTextLog.error("tick 条目异常（id=" + text.id() + "）", e);
            }
        }
        if (reconcileIntervalTicks > 0 && tickCounter % reconcileIntervalTicks == 0) {
            reconcile();
        }
    }

    private void tickOne(FloatingText text) {
        if (!text.valid()) {
            return;
        }
        text.advanceAge();

        if (text.animation() != null && !text.animationDone()) {
            if (!text.animation().tick(text, text.age())) {
                text.markAnimationDone();
            }
        }

        // 文本提交（节流 + 去重：同值不重发）
        if (text.consumeTextDirty()) {
            renderer.setText(text.handle(), text.currentText());
            text.recordSentText();
        }

        // 位置提交（节流；变化才发——none + 静止路径零 Move）
        int interval = effectiveInterval(text);
        if (text.age() % interval == 0) {
            Position base = text.basePosition();
            if (base != null) {
                double x = base.x + text.offsetX();
                double y = base.y + text.offsetY();
                double z = base.z + text.offsetZ();
                if (text.positionChanged(x, y, z)) {
                    renderer.move(text.handle(), x, y, z);
                    text.recordSentPosition(x, y, z);
                }
            }
        }

        // 瞬态寿命
        if (!text.persistent() && text.age() >= text.lifetimeTicks()) {
            removeInternal(text);
        }
    }

    /**
     * 生效节流间隔（逐条覆盖 > 全局默认）。
     */
    private int effectiveInterval(FloatingText text) {
        int override = text.moveIntervalOverride();
        return override > 0 ? override : defaultMoveIntervalTicks;
    }

    // ==================== 移除 ====================

    /**
     * 移除单条（幂等）。
     */
    void removeInternal(FloatingText text) {
        if (!text.valid()) {
            return;
        }
        text.invalidate();
        active.remove(text.id());
        if (text.handle() >= 0) {
            renderer.despawn(text.handle());
        }
        groups.entrySet().removeIf(entry -> entry.getValue().text() == text);
    }

    /**
     * 清场：移除全部条目与组（向在场观众发移除包）。
     */
    public void removeAll() {
        for (FloatingText text : List.copyOf(active.values())) {
            removeInternal(text);
        }
        groups.clear();
    }

    // ==================== 观众协调 ====================

    /**
     * 玩家离线：清账本（不发包）。
     *
     * @param viewerId 玩家 UUID
     */
    public void onViewerQuit(UUID viewerId) {
        renderer.forget(viewerId);
    }

    /**
     * 对账单个观众（幂等，可安全重入）：应看而未记账 → 补发；不应看而已记账 → 摘除。
     * <p><b>仅常驻参与补发</b>——瞬态寿命几秒，重连漏看可接受（README 语义声明）。
     *
     * @param viewer 观众
     */
    public void resync(TextViewer viewer) {
        for (FloatingText text : List.copyOf(active.values())) {
            if (!text.valid() || text.handle() < 0) {
                continue;
            }
            boolean shouldSee = text.persistent()
                    && text.viewers().matches(viewer.uuid(), viewer.levelName())
                    && levelMatches(text, viewer.levelName());
            if (shouldSee) {
                renderer.showTo(text.handle(), viewer);
            } else {
                renderer.hideFrom(text.handle(), viewer);
            }
        }
    }

    /**
     * 周期对账：对全部在线观众重跑 resync（事件丢失自愈网）。
     */
    public void reconcile() {
        for (TextViewer viewer : directory.online()) {
            resync(viewer);
        }
    }

    // ==================== 查询 ====================

    /**
     * 指标。
     *
     * @return 统计
     */
    public Stats stats() {
        int transientCount = 0;
        int persistentCount = 0;
        for (FloatingText text : active.values()) {
            if (text.persistent()) {
                persistentCount++;
            } else {
                transientCount++;
            }
        }
        return new Stats(transientCount, persistentCount, groups.size(), renderer.stats());
    }

    // ==================== 内部工具 ====================

    /**
     * 解析初始观众（在线目录 ∩ Viewers 匹配 ∩ 同 level）。
     */
    private List<TextViewer> resolveViewers(FloatingText spec, String levelName) {
        List<TextViewer> targets = new ArrayList<>();
        for (TextViewer viewer : directory.online()) {
            if (spec.viewers().matches(viewer.uuid(), viewer.levelName())
                    && levelMatches(levelName, viewer.levelName())) {
                targets.add(viewer);
            }
        }
        return targets;
    }

    /**
     * level 是否匹配（条目无 level 限制 = 匹配任意）。
     */
    private boolean levelMatches(String entryLevel, String viewerLevel) {
        return entryLevel == null || entryLevel.equals(viewerLevel);
    }

    /**
     * 条目当前 level 是否对观众可见（resolvedLevelName 为注册时解析/覆盖值）。
     */
    private boolean levelMatches(FloatingText text, String viewerLevel) {
        return levelMatches(text.resolvedLevelName(), viewerLevel);
    }

    /**
     * 条目 level 名解析：显式覆盖 > 位置所属 level；无则 null（不限制）。
     */
    private String levelNameOf(FloatingText spec, Position base) {
        if (spec.levelNameOverride() != null) {
            return spec.levelNameOverride();
        }
        return base.getLevel() == null ? null : base.getLevel().getName();
    }

    /**
     * 常驻组行数上限。
     */
    int maxGroupLines() {
        return maxGroupLines;
    }
}
