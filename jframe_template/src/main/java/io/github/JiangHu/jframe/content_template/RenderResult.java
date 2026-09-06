package io.github.JiangHu.jframe.content_template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通用渲染结果——模板引擎产出的<b>与游戏内容无关</b>的数据结构。
 *
 * <p>各功能模块（scoreboard / title / floatingtext）按自身特性消费这份 raw 数据：
 * <ul>
 *   <li><b>计分板</b>：title → 计分板标题；lines → 计分板行（15 行限制、从下往上排序）</li>
 *   <li><b>标题（未来）</b>：title → 主标题；lines[0] → 副标题</li>
 *   <li><b>浮空字（未来）</b>：lines → 多行浮空文本；title → 第一行大字</li>
 * </ul>
 *
 * <p><b>设计原则</b>：模板引擎层完全通用，不理解 scoreboard / title 等概念；
 * 各模块只负责将 {@code RenderResult} 映射到具体的游戏 API。
 */
public class RenderResult {

    private final String title;
    private final List<String> lines;

    public RenderResult(String title, List<String> lines) {
        this.title = title;
        this.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
    }

    /** 标题渲染结果（可能为 {@code null}） */
    public String getTitle() {
        return title;
    }

    /** 行渲染结果（块级 for 已展开为多行），不可变列表 */
    public List<String> getLines() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public String toString() {
        return "RenderResult{title='" + title + "', lines=" + lines + "}";
    }
}
