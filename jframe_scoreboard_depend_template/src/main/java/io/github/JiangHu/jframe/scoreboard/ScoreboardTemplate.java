package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.network.protocol.types.SortOrder;
import io.github.JiangHu.jframe.content_template.Template;
import java.util.Objects;

/**
 * 计分板模板——将 {@link Template}（内容模板）与计分板显示配置打包在一起。
 *
 * <p>类比前端开发：{@link Template} 是 HTML 模板，{@code ScoreboardTemplate} 则是
 * 「模板 + 渲染参数」（显示在哪个槽位、排序方向、objectiveName 等）。
 *
 * <h3>核心字段</h3>
 * <ul>
 *   <li>{@code name} —— 模板唯一名称，用于 {@code ScoreboardAPI.show(player, name)} 查找</li>
 *   <li>{@code template} —— 编译后的 {@link Template}，定义标题和行的内容结构</li>
 *   <li>{@code displaySlot} —— 显示槽位（默认 {@link DisplaySlot#SIDEBAR}）</li>
 *   <li>{@code sortOrder} —— 排序方式（默认 {@link SortOrder#DESCENDING}，第一行在顶部）</li>
 *   <li>{@code criteriaName} —— Nukkit 计分板判定准则（默认 {@code dummy}）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 方式一：通过 TemplateEngine 加载模板文件，再包装为 ScoreboardTemplate
 * Template template = engine.getTemplate("main");
 * ScoreboardTemplate sbTemplate = ScoreboardTemplate.builder("main", template)
 *         .displaySlot(DisplaySlot.SIDEBAR)
 *         .sortOrder(SortOrder.DESCENDING)
 *         .build();
 *
 * // 方式二：直接编译源码
 * Template template = engine.compile("<template>...</template>");
 * ScoreboardTemplate sbTemplate = ScoreboardTemplate.of("main", template);
 * }</pre>
 *
 * @see Template
 * @see ScoreboardConstants
 */
public class ScoreboardTemplate {

    private final String name;
    private final Template template;
    private final DisplaySlot displaySlot;
    private final SortOrder sortOrder;
    private final String criteriaName;

    private ScoreboardTemplate(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name 不能为空");
        this.template = Objects.requireNonNull(builder.template, "template 不能为空");
        this.displaySlot = builder.displaySlot != null ? builder.displaySlot : ScoreboardConstants.DEFAULT_DISPLAY_SLOT;
        this.sortOrder = builder.sortOrder != null ? builder.sortOrder : ScoreboardConstants.DEFAULT_SORT_ORDER;
        this.criteriaName = builder.criteriaName != null ? builder.criteriaName : ScoreboardConstants.DEFAULT_CRITERIA;
    }

    // ===== 工厂方法 =====

    /**
     * 快捷工厂——使用默认显示配置创建计分板模板。
     *
     * @param name     模板名称
     * @param template 编译后的模板
     */
    public static ScoreboardTemplate of(String name, Template template) {
        return new Builder(name, template).build();
    }

    /**
     * Builder 工厂。
     *
     * @param name     模板名称
     * @param template 编译后的模板
     */
    public static Builder builder(String name, Template template) {
        return new Builder(name, template);
    }

    // ===== Getter =====

    /** 模板唯一名称 */
    public String getName() {
        return name;
    }

    /** 编译后的内容模板 */
    public Template getTemplate() {
        return template;
    }

    /** 显示槽位 */
    public DisplaySlot getDisplaySlot() {
        return displaySlot;
    }

    /** 排序方式 */
    public SortOrder getSortOrder() {
        return sortOrder;
    }

    /** Nukkit 计分板判定准则 */
    public String getCriteriaName() {
        return criteriaName;
    }

    // ===== Builder =====

    public static class Builder {
        private final String name;
        private final Template template;
        private DisplaySlot displaySlot;
        private SortOrder sortOrder;
        private String criteriaName;

        private Builder(String name, Template template) {
            this.name = name;
            this.template = template;
        }

        /** 设置显示槽位（默认 {@link DisplaySlot#SIDEBAR}） */
        public Builder displaySlot(DisplaySlot slot) {
            this.displaySlot = slot;
            return this;
        }

        /** 设置排序方式（默认 {@link SortOrder#DESCENDING}） */
        public Builder sortOrder(SortOrder order) {
            this.sortOrder = order;
            return this;
        }

        /** 设置 Nukkit 计分板判定准则（默认 {@code dummy}） */
        public Builder criteriaName(String criteria) {
            this.criteriaName = criteria;
            return this;
        }

        public ScoreboardTemplate build() {
            return new ScoreboardTemplate(this);
        }
    }
}
