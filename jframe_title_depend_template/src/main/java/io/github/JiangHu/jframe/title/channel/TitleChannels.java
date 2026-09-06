package io.github.JiangHu.jframe.title.channel;

import java.util.Objects;

/**
 * 一次渲染结果在三个标题通道上的最终文本
 *
 * <ul>
 *   <li>{@code title}：主标题（{@code <title>} 标签渲染产物，null 归一化为空串）；</li>
 *   <li>{@code subtitle}：副标题（区间行以 {@code \n} 拼接，可为空串）；</li>
 *   <li>{@code actionbar}：动作栏文本；通道禁用或目标行不存在时为 null（不发包）。</li>
 * </ul>
 *
 * <p>title 与 subtitle 构成一个发送单元（一次 sendTitle 携带时序参数），
 * actionbar 独立发送；{@link TitleView} 以本对象做通道级 diff，
 * 无变化的通道不重复发包。</p>
 */
public final class TitleChannels {

    private final String title;
    private final String subtitle;
    private final String actionbar;

    /**
     * 构造通道文本
     *
     * @param title     主标题；null 归一化为空串
     * @param subtitle  副标题；null 归一化为空串
     * @param actionbar 动作栏；null 表示该通道本次无内容（禁用或行缺失）
     */
    public TitleChannels(String title, String subtitle, String actionbar) {
        this.title = title != null ? title : "";
        this.subtitle = subtitle != null ? subtitle : "";
        this.actionbar = actionbar;
    }

    /** 主标题（保证非 null） */
    public String getTitle() {
        return title;
    }

    /** 副标题（保证非 null，多行以 \n 分隔） */
    public String getSubtitle() {
        return subtitle;
    }

    /** 动作栏文本；null 表示本次无动作栏内容 */
    public String getActionbar() {
        return actionbar;
    }

    /** 与上一帧相比，title / subtitle 任一变化（需重发 sendTitle） */
    public boolean titleOrSubtitleChanged(TitleChannels previous) {
        if (previous == null) {
            return true;
        }
        return !title.equals(previous.title) || !subtitle.equals(previous.subtitle);
    }

    /** 与上一帧相比 actionbar 变化（需重发 sendActionBar） */
    public boolean actionbarChanged(TitleChannels previous) {
        if (previous == null) {
            return actionbar != null;
        }
        return !Objects.equals(actionbar, previous.actionbar);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TitleChannels that)) {
            return false;
        }
        return title.equals(that.title) && subtitle.equals(that.subtitle)
                && Objects.equals(actionbar, that.actionbar);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, subtitle, actionbar);
    }

    @Override
    public String toString() {
        return "TitleChannels{title='" + title + "', subtitle='" + subtitle
                + "', actionbar=" + (actionbar != null ? "'" + actionbar + "'" : "null") + '}';
    }
}
