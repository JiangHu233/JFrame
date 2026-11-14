package io.github.JiangHu.jframe.title.channel;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.title.TitleConstants;
import io.github.JiangHu.jframe.title.TitleMode;
import io.github.JiangHu.jframe.title.TitleTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleMetaResolver} 纯逻辑测试：合法 / 非法元数据解析、三级融合优先级
 */
@DisplayName("TitleMetaResolver 元数据解析")
class TitleMetaResolverLogicTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    /** 编译带若干 meta 声明的模板 */
    private Template compile(String... metas) {
        StringBuilder sb = new StringBuilder("<template>");
        for (String meta : metas) {
            sb.append(meta);
        }
        sb.append("<line>内容</line></template>");
        return engine.compile(sb.toString());
    }

    @Nested
    @DisplayName("合法元数据")
    class LegalMeta {

        @Test
        @DisplayName("全部 9 个 key 均正确解析")
        void testAllKeysResolved() {
            Template template = compile(
                    "<meta key=\"title.subtitle.from\" value=\"0\"/>",
                    "<meta key=\"title.subtitle.to\" value=\"3\"/>",
                    "<meta key=\"title.actionbar.line\" value=\"5\"/>",
                    "<meta key=\"title.timing.fadeIn\" value=\"5\"/>",
                    "<meta key=\"title.timing.stay\" value=\"60\"/>",
                    "<meta key=\"title.timing.fadeOut\" value=\"15\"/>",
                    "<meta key=\"title.mode\" value=\"transient\"/>",
                    "<meta key=\"title.autoclear\" value=\"false\"/>",
                    "<meta key=\"title.actionbar.refresh\" value=\"30\"/>");

            TitleMetaConfig config = TitleMetaResolver.resolve(template, "全量模板");

            assertEquals(0, config.subtitleFrom().getAsInt());
            assertEquals(3, config.subtitleTo().getAsInt());
            assertEquals(5, config.actionbarLine().getAsInt());
            assertEquals(5, config.timingFadeIn().getAsInt());
            assertEquals(60, config.timingStay().getAsInt());
            assertEquals(15, config.timingFadeOut().getAsInt());
            assertEquals(TitleMode.TRANSIENT, config.mode().orElseThrow());
            assertEquals(Boolean.FALSE, config.autoClear().orElseThrow());
            assertEquals(30, config.actionBarRefreshInterval().getAsInt());
            assertTrue(config.hasAnyTiming());
        }

        @Test
        @DisplayName("actionbar_line 允许 -1 表示禁用")
        void testActionbarDisabled() {
            Template template = compile("<meta key=\"title.actionbar.line\" value=\"-1\"/>");

            TitleMetaConfig config = TitleMetaResolver.resolve(template);

            assertEquals(-1, config.actionbarLine().getAsInt());
        }

        @Test
        @DisplayName("mode 大小写不敏感")
        void testModeCaseInsensitive() {
            Template template = compile("<meta key=\"title.mode\" value=\"PERSISTENT\"/>");

            assertEquals(TitleMode.PERSISTENT, TitleMetaResolver.resolve(template).mode().orElseThrow());
        }

        @Test
        @DisplayName("整数值允许首尾空白")
        void testTrimWhitespace() {
            Template template = compile("<meta key=\"title.timing.stay\" value=\" 42 \"/>");

            assertEquals(42, TitleMetaResolver.resolve(template).timingStay().getAsInt());
        }
    }

    @Nested
    @DisplayName("非法元数据（fail-fast）")
    class IllegalMeta {

        @Test
        @DisplayName("非整数时序值抛 IAE，消息含模板名 / key / 原值")
        void testNonInteger() {
            Template template = compile("<meta key=\"title.timing.stay\" value=\"abc\"/>");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> TitleMetaResolver.resolve(template, "坏模板"));
            assertTrue(ex.getMessage().contains("坏模板"));
            assertTrue(ex.getMessage().contains("title.timing.stay"));
            assertTrue(ex.getMessage().contains("abc"));
        }

        @Test
        @DisplayName("负数时序值抛 IAE")
        void testNegativeTiming() {
            Template template = compile("<meta key=\"title.timing.fadeIn\" value=\"-1\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("副标题区间起点为负抛 IAE")
        void testNegativeSubtitleFrom() {
            Template template = compile("<meta key=\"title.subtitle.from\" value=\"-1\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("actionbar_line 小于 -1 抛 IAE")
        void testActionbarBelowDisabled() {
            Template template = compile("<meta key=\"title.actionbar.line\" value=\"-2\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("非法 mode 枚举值抛 IAE")
        void testIllegalMode() {
            Template template = compile("<meta key=\"title.mode\" value=\"flash\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("非法 autoclear 布尔值抛 IAE")
        void testIllegalBoolean() {
            Template template = compile("<meta key=\"title.autoclear\" value=\"yes\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("负数续期间隔抛 IAE")
        void testNegativeRefresh() {
            Template template = compile("<meta key=\"title.actionbar.refresh\" value=\"-5\"/>");

            assertThrows(IllegalArgumentException.class, () -> TitleMetaResolver.resolve(template));
        }
    }

    @Nested
    @DisplayName("命名空间与未知 key")
    class NamespaceHandling {

        @Test
        @DisplayName("无元数据时返回 EMPTY")
        void testEmptyMetadata() {
            Template template = compile();

            assertEquals(TitleMetaConfig.EMPTY, TitleMetaResolver.resolve(template));
            assertFalse(TitleMetaResolver.resolve(template).hasAnyTiming());
        }

        @Test
        @DisplayName("非 title.* 前缀的 key 属于其他模块命名空间，静默忽略")
        void testForeignNamespaceIgnored() {
            Template template = compile(
                    "<meta key=\"module\" value=\"scoreboard\"/>",
                    "<meta key=\"refresh\" value=\"20\"/>");

            assertEquals(TitleMetaConfig.EMPTY, TitleMetaResolver.resolve(template));
        }

        @Test
        @DisplayName("未知 title.* key 仅告警不抛异常，已知 key 仍正常解析")
        void testUnknownTitleKeyWarns() {
            Template template = compile(
                    "<meta key=\"title.unknown_key\" value=\"whatever\"/>",
                    "<meta key=\"title.timing.stay\" value=\"33\"/>");

            TitleMetaConfig config = TitleMetaResolver.resolve(template, "未知key模板");

            assertEquals(33, config.timingStay().getAsInt());
        }
    }

    @Nested
    @DisplayName("三级融合优先级（元数据层 > 内置默认）")
    class MetaOverDefaults {

        @Test
        @DisplayName("元数据声明的时序覆盖默认 10/70/20")
        void testTimingMetaOverDefault() {
            Template template = compile(
                    "<meta key=\"title.timing.fadeIn\" value=\"1\"/>",
                    "<meta key=\"title.timing.stay\" value=\"2\"/>",
                    "<meta key=\"title.timing.fadeOut\" value=\"3\"/>");

            TitleTemplate titleTemplate = TitleTemplate.of("融合模板", template);

            assertEquals(1, titleTemplate.getTiming().getFadeIn());
            assertEquals(2, titleTemplate.getTiming().getStay());
            assertEquals(3, titleTemplate.getTiming().getFadeOut());
        }

        @Test
        @DisplayName("元数据部分声明时序时，未声明项回落默认值")
        void testPartialTimingFallsBack() {
            Template template = compile("<meta key=\"title.timing.stay\" value=\"99\"/>");

            TitleTemplate titleTemplate = TitleTemplate.of("部分时序", template);

            assertEquals(TitleConstants.DEFAULT_FADE_IN, titleTemplate.getTiming().getFadeIn());
            assertEquals(99, titleTemplate.getTiming().getStay());
            assertEquals(TitleConstants.DEFAULT_FADE_OUT, titleTemplate.getTiming().getFadeOut());
        }

        @Test
        @DisplayName("无任何元数据时全部使用内置默认")
        void testAllDefaults() {
            Template template = compile();

            TitleTemplate titleTemplate = TitleTemplate.of("默认模板", template);

            assertEquals(TitleConstants.DEFAULT_FADE_IN, titleTemplate.getTiming().getFadeIn());
            assertEquals(TitleConstants.DEFAULT_STAY, titleTemplate.getTiming().getStay());
            assertEquals(TitleConstants.DEFAULT_FADE_OUT, titleTemplate.getTiming().getFadeOut());
            assertEquals(TitleMode.PERSISTENT, titleTemplate.getMode());
            assertEquals(TitleConstants.DEFAULT_SUBTITLE_FROM, titleTemplate.getSubtitleFrom());
            assertEquals(TitleConstants.DEFAULT_SUBTITLE_TO, titleTemplate.getSubtitleTo());
            assertEquals(TitleConstants.DEFAULT_ACTIONBAR_LINE, titleTemplate.getActionbarLine());
            assertTrue(titleTemplate.isAutoClear());
            assertEquals(0, titleTemplate.getActionBarRefreshInterval());
        }
    }
}
