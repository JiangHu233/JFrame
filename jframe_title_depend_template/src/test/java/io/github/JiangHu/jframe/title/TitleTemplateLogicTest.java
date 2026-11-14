package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleTemplate} 纯逻辑测试：Builder fail-fast 校验、三级融合优先级（Builder > 元数据 > 默认）、槽位判定
 */
@DisplayName("TitleTemplate 构建与融合")
class TitleTemplateLogicTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    private Template compile(String... metas) {
        StringBuilder sb = new StringBuilder("<template>");
        for (String meta : metas) {
            sb.append(meta);
        }
        sb.append("<line>内容</line></template>");
        return engine.compile(sb.toString());
    }

    @Nested
    @DisplayName("Builder fail-fast 校验")
    class BuilderValidation {

        @Test
        @DisplayName("副标题区间起点为负抛 IAE")
        void testNegativeFrom() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("负起点", compile())
                            .subtitleLines(-1, 2)
                            .build());
            assertTrue(ex.getMessage().contains("负起点"));
        }

        @Test
        @DisplayName("副标题区间终点小于起点抛 IAE")
        void testToLessThanFrom() {
            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("倒序区间", compile())
                            .subtitleLines(3, 2)
                            .build());
        }

        @Test
        @DisplayName("动作栏行号小于 -1 抛 IAE")
        void testActionbarBelowDisabled() {
            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("非法动作栏", compile())
                            .actionbarLine(-2)
                            .build());
        }

        @Test
        @DisplayName("动作栏行号落入副标题区间抛 IAE（通道互斥）")
        void testActionbarOverlapsSubtitle() {
            // 边界：actionbar == from
            IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("重叠下界", compile())
                            .subtitleLines(2, 5)
                            .actionbarLine(2)
                            .build());
            assertTrue(ex1.getMessage().contains("重叠"));

            // 区间中部
            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("重叠中部", compile())
                            .subtitleLines(2, 5)
                            .actionbarLine(3)
                            .build());

            // 边界：actionbar == to - 1（半开区间，仍属重叠）
            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("重叠上界", compile())
                            .subtitleLines(2, 5)
                            .actionbarLine(4)
                            .build());
        }

        @Test
        @DisplayName("动作栏行号等于区间终点不重叠（半开区间）")
        void testActionbarAtToIsLegal() {
            TitleTemplate titleTemplate = TitleTemplate.builder("紧邻", compile())
                    .subtitleLines(2, 5)
                    .actionbarLine(5)
                    .build();

            assertEquals(5, titleTemplate.getActionbarLine());
        }

        @Test
        @DisplayName("负数续期间隔抛 IAE")
        void testNegativeRefresh() {
            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.builder("负续期", compile())
                            .actionBarRefresh(-1)
                            .build());
        }

        @Test
        @DisplayName("模板名与引擎模板不得为 null")
        void testNullArguments() {
            assertThrows(NullPointerException.class, () -> TitleTemplate.builder(null, compile()));
            assertThrows(NullPointerException.class, () -> TitleTemplate.builder("名字", null));
        }

        @Test
        @DisplayName("元数据非法值在 build 时同样 fail-fast")
        void testIllegalMetaFailsAtBuild() {
            Template template = compile("<meta key=\"title.timing.stay\" value=\"oops\"/>");

            assertThrows(IllegalArgumentException.class,
                    () -> TitleTemplate.of("坏元数据", template));
        }
    }

    @Nested
    @DisplayName("三级融合优先级：Builder 显式 > 模板元数据 > 内置默认")
    class FusionPriority {

        @Test
        @DisplayName("Builder 显式设置覆盖元数据与默认")
        void testBuilderWins() {
            Template template = compile(
                    "<meta key=\"title.timing.fadeIn\" value=\"1\"/>",
                    "<meta key=\"title.timing.stay\" value=\"2\"/>",
                    "<meta key=\"title.timing.fadeOut\" value=\"3\"/>",
                    "<meta key=\"title.mode\" value=\"transient\"/>",
                    "<meta key=\"title.subtitle.from\" value=\"0\"/>",
                    "<meta key=\"title.subtitle.to\" value=\"2\"/>",
                    "<meta key=\"title.actionbar.line\" value=\"5\"/>",
                    "<meta key=\"title.autoclear\" value=\"false\"/>",
                    "<meta key=\"title.actionbar.refresh\" value=\"30\"/>");

            TitleTemplate titleTemplate = TitleTemplate.builder("最高优先", template)
                    .timing(TitleTiming.of(9, 8, 7))
                    .mode(TitleMode.PERSISTENT)
                    .subtitleLines(1, 3)
                    .actionbarLine(6)
                    .autoClear(true)
                    .actionBarRefresh(50)
                    .build();

            assertEquals(9, titleTemplate.getTiming().getFadeIn());
            assertEquals(8, titleTemplate.getTiming().getStay());
            assertEquals(7, titleTemplate.getTiming().getFadeOut());
            assertEquals(TitleMode.PERSISTENT, titleTemplate.getMode());
            assertEquals(1, titleTemplate.getSubtitleFrom());
            assertEquals(3, titleTemplate.getSubtitleTo());
            assertEquals(6, titleTemplate.getActionbarLine());
            assertTrue(titleTemplate.isAutoClear());
            assertEquals(50, titleTemplate.getActionBarRefreshInterval());
        }

        @Test
        @DisplayName("Builder 未设置项回落到元数据声明值")
        void testMetaFillsUnset() {
            Template template = compile(
                    "<meta key=\"title.subtitle.from\" value=\"1\"/>",
                    "<meta key=\"title.subtitle.to\" value=\"3\"/>",
                    "<meta key=\"title.actionbar.line\" value=\"4\"/>",
                    "<meta key=\"title.mode\" value=\"transient\"/>",
                    "<meta key=\"title.autoclear\" value=\"false\"/>",
                    "<meta key=\"title.actionbar.refresh\" value=\"20\"/>");

            // 仅显式设置时序，其余回落元数据
            TitleTemplate titleTemplate = TitleTemplate.builder("回落元数据", template)
                    .timing(TitleTiming.of(5, 50, 5))
                    .build();

            assertEquals(5, titleTemplate.getTiming().getFadeIn());
            assertEquals(TitleMode.TRANSIENT, titleTemplate.getMode());
            assertEquals(1, titleTemplate.getSubtitleFrom());
            assertEquals(3, titleTemplate.getSubtitleTo());
            assertEquals(4, titleTemplate.getActionbarLine());
            assertFalse(titleTemplate.isAutoClear());
            assertEquals(20, titleTemplate.getActionBarRefreshInterval());
        }

        @Test
        @DisplayName("Builder 与元数据均未声明时使用内置默认")
        void testDefaultsWhenAbsent() {
            TitleTemplate titleTemplate = TitleTemplate.of("纯默认", compile());

            assertEquals(TitleTiming.DEFAULT, titleTemplate.getTiming());
            assertEquals(TitleMode.PERSISTENT, titleTemplate.getMode());
            assertEquals(TitleConstants.DEFAULT_SUBTITLE_FROM, titleTemplate.getSubtitleFrom());
            assertEquals(TitleConstants.DEFAULT_SUBTITLE_TO, titleTemplate.getSubtitleTo());
            assertEquals(TitleConstants.DEFAULT_ACTIONBAR_LINE, titleTemplate.getActionbarLine());
            assertTrue(titleTemplate.isAutoClear());
            assertEquals(0, titleTemplate.getActionBarRefreshInterval());
        }

        @Test
        @DisplayName("Builder timing 整体覆盖元数据的部分时序声明")
        void testBuilderTimingOverridesMetaTiming() {
            Template template = compile("<meta key=\"title.timing.stay\" value=\"99\"/>");

            TitleTemplate titleTemplate = TitleTemplate.builder("整体覆盖", template)
                    .timing(TitleTiming.of(4, 5, 6))
                    .build();

            assertEquals(4, titleTemplate.getTiming().getFadeIn());
            assertEquals(5, titleTemplate.getTiming().getStay());
            assertEquals(6, titleTemplate.getTiming().getFadeOut());
        }
    }

    @Nested
    @DisplayName("槽位判定")
    class SlotOccupation {

        @Test
        @DisplayName("默认映射：副标题 [0,1) 占 TITLE 槽，动作栏 lines[1] 占 ACTIONBAR 槽")
        void testDefaultOccupation() {
            TitleTemplate titleTemplate = TitleTemplate.of("默认槽位", compile());

            assertTrue(titleTemplate.occupiesTitleSlot());
            assertTrue(titleTemplate.occupiesActionBarSlot());
        }

        @Test
        @DisplayName("空副标题区间（from == to）不占 TITLE 槽")
        void testEmptySubtitleNoTitleSlot() {
            TitleTemplate titleTemplate = TitleTemplate.builder("仅动作栏", compile())
                    .subtitleLines(0, 0)
                    .actionbarLine(0)
                    .build();

            assertFalse(titleTemplate.occupiesTitleSlot());
            assertTrue(titleTemplate.occupiesActionBarSlot());
        }

        @Test
        @DisplayName("actionbarLine = -1 不占 ACTIONBAR 槽")
        void testDisabledActionbarNoActionBarSlot() {
            TitleTemplate titleTemplate = TitleTemplate.builder("仅标题", compile())
                    .actionbarLine(-1)
                    .build();

            assertTrue(titleTemplate.occupiesTitleSlot());
            assertFalse(titleTemplate.occupiesActionBarSlot());
        }

        @Test
        @DisplayName("双禁用模板不占任何槽位（合法但无显示效果）")
        void testNoSlots() {
            TitleTemplate titleTemplate = TitleTemplate.builder("空通道", compile())
                    .subtitleLines(0, 0)
                    .actionbarLine(-1)
                    .build();

            assertFalse(titleTemplate.occupiesTitleSlot());
            assertFalse(titleTemplate.occupiesActionBarSlot());
        }
    }

    @Nested
    @DisplayName("基础契约")
    class Contract {

        @Test
        @DisplayName("equals / hashCode 以模板名判定")
        void testEqualsByName() {
            TitleTemplate a = TitleTemplate.of("同名", compile());
            TitleTemplate b = TitleTemplate.of("同名", compile());
            TitleTemplate c = TitleTemplate.of("异名", compile());

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("toString 包含关键融合结果")
        void testToString() {
            TitleTemplate titleTemplate = TitleTemplate.builder("可读", compile())
                    .mode(TitleMode.TRANSIENT)
                    .build();

            String text = titleTemplate.toString();
            assertNotNull(text);
            assertTrue(text.contains("可读"));
            assertTrue(text.contains("TRANSIENT"));
        }
    }
}
