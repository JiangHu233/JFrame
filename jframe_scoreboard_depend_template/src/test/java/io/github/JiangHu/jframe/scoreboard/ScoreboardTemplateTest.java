package io.github.JiangHu.jframe.scoreboard;

import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.network.protocol.types.SortOrder;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ScoreboardTemplate} 单元测试——验证 Builder 模式、工厂方法、默认值和 null 校验。
 *
 * <p>注：{@link ScoreboardView} / {@link ScoreboardManager} / {@link ScoreboardAPI} 依赖
 * 运行时 Nukkit {@code Server} 实例，无法在纯单元测试环境中测试，需集成测试或 Mock。
 */
@DisplayName("ScoreboardTemplate 单元测试")
class ScoreboardTemplateTest {

    private Template template;

    @BeforeEach
    void setUp() {
        TemplateEngine engine = new TemplateEngine();
        template = engine.compile("""
            <template>
                <title>测试标题</title>
                <line>第1行: {{value}}</line>
            </template>
            """);
    }

    // ==================== 工厂方法 of() ====================

    @Nested
    @DisplayName("工厂方法 of()")
    class FactoryOf {

        @Test
        @DisplayName("of() 使用默认显示配置")
        void testOfWithDefaults() {
            ScoreboardTemplate sb = ScoreboardTemplate.of("main", template);

            assertEquals("main", sb.getName());
            assertEquals(template, sb.getTemplate());
            assertEquals(ScoreboardConstants.DEFAULT_DISPLAY_SLOT, sb.getDisplaySlot());
            assertEquals(ScoreboardConstants.DEFAULT_SORT_ORDER, sb.getSortOrder());
            assertEquals(ScoreboardConstants.DEFAULT_CRITERIA, sb.getCriteriaName());
        }

        @Test
        @DisplayName("of() name 为 null 时抛出 NullPointerException")
        void testOfNullName() {
            NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> ScoreboardTemplate.of(null, template)
            );
            assertEquals("name 不能为空", ex.getMessage());
        }

        @Test
        @DisplayName("of() template 为 null 时抛出 NullPointerException")
        void testOfNullTemplate() {
            NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> ScoreboardTemplate.of("main", null)
            );
            assertEquals("template 不能为空", ex.getMessage());
        }
    }

    // ==================== Builder 模式 ====================

    @Nested
    @DisplayName("Builder 模式")
    class BuilderPattern {

        @Test
        @DisplayName("Builder 不设置任何可选参数时使用默认值")
        void testBuilderDefaults() {
            ScoreboardTemplate sb = ScoreboardTemplate.builder("main", template).build();

            assertEquals(ScoreboardConstants.DEFAULT_DISPLAY_SLOT, sb.getDisplaySlot());
            assertEquals(ScoreboardConstants.DEFAULT_SORT_ORDER, sb.getSortOrder());
            assertEquals(ScoreboardConstants.DEFAULT_CRITERIA, sb.getCriteriaName());
        }

        @Test
        @DisplayName("Builder 自定义 displaySlot")
        void testBuilderCustomDisplaySlot() {
            ScoreboardTemplate sb = ScoreboardTemplate.builder("main", template)
                .displaySlot(DisplaySlot.BELOW_NAME)
                .build();

            assertEquals(DisplaySlot.BELOW_NAME, sb.getDisplaySlot());
        }

        @Test
        @DisplayName("Builder 自定义 sortOrder")
        void testBuilderCustomSortOrder() {
            ScoreboardTemplate sb = ScoreboardTemplate.builder("main", template)
                .sortOrder(SortOrder.ASCENDING)
                .build();

            assertEquals(SortOrder.ASCENDING, sb.getSortOrder());
        }

        @Test
        @DisplayName("Builder 自定义 criteriaName")
        void testBuilderCustomCriteria() {
            ScoreboardTemplate sb = ScoreboardTemplate.builder("main", template)
                .criteriaName("health")
                .build();

            assertEquals("health", sb.getCriteriaName());
        }

        @Test
        @DisplayName("Builder 链式调用设置全部参数")
        void testBuilderFullChain() {
            ScoreboardTemplate sb = ScoreboardTemplate.builder("combat", template)
                .displaySlot(DisplaySlot.SIDEBAR)
                .sortOrder(SortOrder.DESCENDING)
                .criteriaName("dummy")
                .build();

            assertEquals("combat", sb.getName());
            assertEquals(DisplaySlot.SIDEBAR, sb.getDisplaySlot());
            assertEquals(SortOrder.DESCENDING, sb.getSortOrder());
            assertEquals("dummy", sb.getCriteriaName());
            assertNotNull(sb.getTemplate());
        }
    }

    // ==================== ScoreboardConstants 验证 ====================

    @Test
    @DisplayName("ScoreboardConstants 默认值正确")
    void testConstantsDefaults() {
        assertEquals("jframe_sb_", ScoreboardConstants.OBJECTIVE_NAME_PREFIX);
        assertEquals("dummy", ScoreboardConstants.DEFAULT_CRITERIA);
        assertEquals(DisplaySlot.SIDEBAR, ScoreboardConstants.DEFAULT_DISPLAY_SLOT);
        assertEquals(SortOrder.DESCENDING, ScoreboardConstants.DEFAULT_SORT_ORDER);
        assertEquals(15, ScoreboardConstants.MAX_LINES);
        assertEquals(30, ScoreboardConstants.MAX_LINE_LENGTH);
    }
}
