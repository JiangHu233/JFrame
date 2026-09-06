package io.github.JiangHu.jframe.title.channel;

import io.github.JiangHu.jframe.content_template.RenderResult;
import io.github.JiangHu.jframe.title.TitleTemplate;

import java.util.List;

/**
 * 通道映射器：渲染结果 → 三通道文本（纯切分，无渲染逻辑）
 *
 * <p>默认映射（引擎预留约定，可被模板元数据或 Builder 覆盖）：</p>
 * <ul>
 *   <li>主标题：{@code <title>} 标签渲染产物；</li>
 *   <li>副标题：lines[subtitleFrom, subtitleTo) 半开区间，多行以 {@code \n} 拼接；</li>
 *   <li>动作栏：lines[actionbarLine]；actionbarLine 为 -1（禁用）或行不存在时该通道无内容。</li>
 * </ul>
 *
 * <p>区间越界部分自动裁剪（不抛异常）；列对齐等格式化已由引擎渲染阶段完成，
 * 本类只做行到通道的纯切分。</p>
 */
public final class TitleChannelMapper {

    private TitleChannelMapper() {
    }

    /**
     * 执行通道切分
     *
     * @param result   渲染结果（title + lines）
     * @param template 已完成三级融合的标题模板（提供区间与动作栏行号）
     * @return 三通道文本
     */
    public static TitleChannels map(RenderResult result, TitleTemplate template) {
        String title = result.getTitle();

        List<String> lines = result.getLines();
        String subtitle = sliceSubtitle(lines, template);
        String actionbar = pickActionBar(lines, template);

        return new TitleChannels(title, subtitle, actionbar);
    }

    /** 切分副标题区间 [from, to)，越界自动裁剪，多行以 \n 拼接 */
    private static String sliceSubtitle(List<String> lines, TitleTemplate template) {
        int from = Math.max(0, template.getSubtitleFrom());
        int to = Math.min(lines.size(), Math.max(from, template.getSubtitleTo()));
        if (to <= from) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** 提取动作栏行；禁用（-1）或行不存在时返回 null */
    private static String pickActionBar(List<String> lines, TitleTemplate template) {
        int index = template.getActionbarLine();
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        return lines.get(index);
    }
}
