package io.github.JiangHu.jframe.title.channel;

import io.github.JiangHu.jframe.content_template.RenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.title.TitleTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TitleChannelMapper} / {@link TitleChannels} 纯逻辑测试：
 * 默认映射、覆盖映射、多行副标题、动作栏禁用、越界裁剪与通道级 diff 判定
 */
@DisplayName("TitleChannelMapper 通道切分")
class TitleChannelMapperLogicTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    private TitleTemplate templateOf(TitleTemplate.Builder builder) {
        return builder.build();
    }

    private RenderResult result(String title, String... lines) {
        return new RenderResult(title, List.of(lines));
    }

    @Nested
    @DisplayName("默认映射")
    class DefaultMapping {

        @Test
        @DisplayName("<title> 渲染产物→主标题，lines[0]→副标题，lines[1]→动作栏")
        void testDefaultChannels() {
            Template raw = engine.compile("<template><line>行甲</line><line>行乙</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.of("默认映射", raw);

            TitleChannels channels = TitleChannelMapper.map(
                    result("主标题", "行甲", "行乙"), titleTemplate);

            assertEquals("主标题", channels.getTitle());
            assertEquals("行甲", channels.getSubtitle());
            assertEquals("行乙", channels.getActionbar());
        }

        @Test
        @DisplayName("title 为 null 时归一化为空串")
        void testNullTitleNormalized() {
            Template raw = engine.compile("<template><line>行甲</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.builder("空主标题", raw)
                    .actionbarLine(-1)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result(null, "行甲"), titleTemplate);

            assertEquals("", channels.getTitle());
            assertEquals("行甲", channels.getSubtitle());
        }
    }

    @Nested
    @DisplayName("Builder 覆盖映射")
    class OverrideMapping {

        @Test
        @DisplayName("subtitleLines 覆盖默认区间")
        void testSubtitleOverride() {
            Template raw = engine.compile("<template><line>甲</line><line>乙</line><line>丙</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.builder("覆盖区间", raw)
                    .subtitleLines(1, 2)
                    .actionbarLine(2)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲", "乙", "丙"), titleTemplate);

            assertEquals("乙", channels.getSubtitle());
            assertEquals("丙", channels.getActionbar());
        }

        @Test
        @DisplayName("元数据声明的映射同样生效")
        void testMetaOverride() {
            Template raw = engine.compile(
                    "<template>"
                            + "<meta key=\"title.subtitle.from\" value=\"1\"/>"
                            + "<meta key=\"title.subtitle.to\" value=\"2\"/>"
                            + "<meta key=\"title.actionbar.line\" value=\"2\"/>"
                            + "<line>甲</line><line>乙</line><line>丙</line>"
                            + "</template>");
            TitleTemplate titleTemplate = TitleTemplate.of("元数据映射", raw);

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲", "乙", "丙"), titleTemplate);

            assertEquals("乙", channels.getSubtitle());
            assertEquals("丙", channels.getActionbar());
        }
    }

    @Nested
    @DisplayName("多行副标题")
    class MultiLineSubtitle {

        @Test
        @DisplayName("区间内多行以 \\n 拼接")
        void testMultiLineJoin() {
            Template raw = engine.compile(
                    "<template><line>甲</line><line>乙</line><line>丙</line><line>丁</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.builder("多行副标题", raw)
                    .subtitleLines(0, 3)
                    .actionbarLine(-1)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲", "乙", "丙", "丁"), titleTemplate);

            assertEquals("甲\n乙\n丙", channels.getSubtitle());
            assertNull(channels.getActionbar());
        }

        @Test
        @DisplayName("区间越界自动裁剪不抛异常")
        void testOutOfRangeClamped() {
            Template raw = engine.compile("<template><line>甲</line><line>乙</line></template>");
            // 场景一：副标题区间 [1, 99) 超出行数（动作栏禁用避免与互斥校验冲突）
            TitleTemplate subtitleOverflow = TitleTemplate.builder("越界区间", raw)
                    .subtitleLines(1, 99)
                    .actionbarLine(-1)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲", "乙"), subtitleOverflow);

            assertEquals("乙", channels.getSubtitle());
            assertNull(channels.getActionbar());

            // 场景二：动作栏行号 5 超出行数（副标题区间不越界）
            TitleTemplate actionbarOverflow = TitleTemplate.builder("越界动作栏", raw)
                    .subtitleLines(0, 1)
                    .actionbarLine(5)
                    .build();

            TitleChannels barChannels = TitleChannelMapper.map(result("T", "甲", "乙"), actionbarOverflow);

            assertEquals("甲", barChannels.getSubtitle());
            assertNull(barChannels.getActionbar(), "行号越界时动作栏通道为 null");
        }

        @Test
        @DisplayName("空区间（from == to）副标题为空串")
        void testEmptyRange() {
            Template raw = engine.compile("<template><line>甲</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.builder("空区间", raw)
                    .subtitleLines(0, 0)
                    .actionbarLine(0)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲"), titleTemplate);

            assertEquals("", channels.getSubtitle());
            assertEquals("甲", channels.getActionbar());
        }
    }

    @Nested
    @DisplayName("动作栏禁用")
    class ActionBarDisabled {

        @Test
        @DisplayName("actionbarLine = -1 时动作栏通道为 null（不发包）")
        void testDisabled() {
            Template raw = engine.compile("<template><line>甲</line><line>乙</line></template>");
            TitleTemplate titleTemplate = TitleTemplate.builder("禁用动作栏", raw)
                    .actionbarLine(-1)
                    .build();

            TitleChannels channels = TitleChannelMapper.map(result("T", "甲", "乙"), titleTemplate);

            assertNull(channels.getActionbar());
        }
    }

    @Nested
    @DisplayName("通道级 diff 判定")
    class ChannelDiff {

        private final TitleChannels frameA = new TitleChannels("主标题", "副标题", "动作栏");

        @Test
        @DisplayName("previous 为 null 时视为全部变化")
        void testFirstFrame() {
            assertTrue(frameA.titleOrSubtitleChanged(null));
            assertTrue(frameA.actionbarChanged(null));
        }

        @Test
        @DisplayName("仅主标题变化→titleOrSubtitleChanged")
        void testTitleOnlyChanged() {
            TitleChannels frameB = new TitleChannels("新主标题", "副标题", "动作栏");

            assertTrue(frameB.titleOrSubtitleChanged(frameA));
            assertFalse(frameB.actionbarChanged(frameA));
        }

        @Test
        @DisplayName("仅副标题变化→titleOrSubtitleChanged")
        void testSubtitleOnlyChanged() {
            TitleChannels frameB = new TitleChannels("主标题", "新副标题", "动作栏");

            assertTrue(frameB.titleOrSubtitleChanged(frameA));
            assertFalse(frameB.actionbarChanged(frameA));
        }

        @Test
        @DisplayName("仅动作栏变化→actionbarChanged")
        void testActionbarOnlyChanged() {
            TitleChannels frameB = new TitleChannels("主标题", "副标题", "新动作栏");

            assertFalse(frameB.titleOrSubtitleChanged(frameA));
            assertTrue(frameB.actionbarChanged(frameA));
        }

        @Test
        @DisplayName("完全相同→两通道均无变化")
        void testNoChange() {
            TitleChannels frameB = new TitleChannels("主标题", "副标题", "动作栏");

            assertFalse(frameB.titleOrSubtitleChanged(frameA));
            assertFalse(frameB.actionbarChanged(frameA));
            assertEquals(frameA, frameB);
        }

        @Test
        @DisplayName("动作栏从 null 变为有值 / 从有值变为 null 均视为变化")
        void testActionbarNullTransition() {
            TitleChannels noBar = new TitleChannels("主标题", "副标题", null);

            assertTrue(frameA.actionbarChanged(noBar));
            assertTrue(noBar.actionbarChanged(frameA));
            // 首帧无动作栏内容时不算变化
            assertFalse(noBar.actionbarChanged(null));
        }
    }

    @Nested
    @DisplayName("值对象契约")
    class ValueContract {

        @Test
        @DisplayName("equals / hashCode / toString")
        void testValueObject() {
            TitleChannels a = new TitleChannels("T", "S", "A");
            TitleChannels b = new TitleChannels("T", "S", "A");
            TitleChannels c = new TitleChannels("T", "S", null);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertFalse(a.equals(c));
            assertTrue(a.toString().contains("T"));
        }
    }
}
