package io.github.JiangHu.jframe.content_template.parser;

import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.content_template.column.ColumnAlign;
import io.github.JiangHu.jframe.content_template.column.ColumnLayout;
import io.github.JiangHu.jframe.content_template.column.ColumnWidth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器扩展（列格式 + 元数据）的单元测试——验证 {@link TemplateParser} 对
 * 根属性 {@code columns}、{@code <columns>/<column>} 配置块、{@code <meta>} 标签的解析，
 * 以及旧语法模板的向后兼容（列格式禁用、元数据为空）。
 *
 * <h3>测试矩阵（设计文档 10.1 引擎侧）</h3>
 * <ul>
 *   <li>列格式：根属性解析 / 空串异常 / 配置块隐式启用（默认分隔符）/ 重复块异常 /
 *       非法 align、width 异常 / 根属性与配置块组合</li>
 *   <li>元数据：收集 / 多个 / 重复 key 后者覆盖 / key 缺失或空异常 / value 缺省空串</li>
 *   <li>兼容性：旧语法模板列格式禁用且元数据为空；compileText 元数据恒为空</li>
 * </ul>
 */
@DisplayName("解析器扩展：列格式与元数据标签")
class TemplateParserColumnMetaTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    @Nested
    @DisplayName("列格式：根属性 columns")
    class RootColumnsAttrTests {

        @Test
        @DisplayName("columns=\"|\"：启用列格式，分隔符为 |")
        void testRootAttrEnables() {
            Template template = engine.compile("""
                    <template columns="|">
                        <line>a|b</line>
                    </template>
                    """);
            assertTrue(template.isColumnLayoutEnabled(), "根属性应启用列格式");
            assertEquals("|", template.getColumnLayout().getSeparator());
            assertTrue(template.getColumnLayout().getColumns().isEmpty(), "无配置块 → 无列策略");
        }

        @Test
        @DisplayName("多字符分隔符：columns=\";;\"")
        void testMultiCharSeparator() {
            Template template = engine.compile("""
                    <template columns=";;">
                        <line>a;;b</line>
                    </template>
                    """);
            assertEquals(";;", template.getColumnLayout().getSeparator());
        }

        @Test
        @DisplayName("columns=\"\"（空串）：解析异常")
        void testEmptyAttrRejected() {
            TemplateParseException ex = assertThrows(TemplateParseException.class,
                    () -> engine.compile("""
                            <template columns="">
                                <line>a|b</line>
                            </template>
                            """));
            assertTrue(ex.getMessage().contains("columns"), "错误信息应指出 columns 属性问题");
        }

        @Test
        @DisplayName("无 columns 属性：列格式禁用（向后兼容）")
        void testNoAttrDisabled() {
            Template template = engine.compile("""
                    <template>
                        <line>a|b</line>
                    </template>
                    """);
            assertFalse(template.isColumnLayoutEnabled());
            assertEquals(ColumnLayout.DISABLED, template.getColumnLayout());
        }
    }

    @Nested
    @DisplayName("列格式：<columns> 配置块")
    class ColumnsBlockTests {

        @Test
        @DisplayName("<columns> 单独出现：隐式启用，默认分隔符 |")
        void testBlockImplicitEnable() {
            Template template = engine.compile("""
                    <template>
                        <columns>
                            <column align="left" width="auto"/>
                            <column align="right" width="10"/>
                        </columns>
                        <line>a|b</line>
                    </template>
                    """);
            assertTrue(template.isColumnLayoutEnabled(), "配置块应隐式启用列格式");
            assertEquals("|", template.getColumnLayout().getSeparator(), "默认分隔符为 |");
            assertEquals(2, template.getColumnLayout().getColumns().size());
        }

        @Test
        @DisplayName("根属性 + 配置块：分隔符取根属性，策略取配置块")
        void testAttrPlusBlock() {
            Template template = engine.compile("""
                    <template columns=";;">
                        <columns>
                            <column align="right"/>
                        </columns>
                        <line>a;;b</line>
                    </template>
                    """);
            assertEquals(";;", template.getColumnLayout().getSeparator());
            assertEquals(1, template.getColumnLayout().getColumns().size());
            assertEquals(ColumnAlign.RIGHT, template.getColumnLayout().getColumns().get(0).getAlign());
        }

        @Test
        @DisplayName("column 属性解析：align 与 width")
        void testColumnSpecParsing() {
            Template template = engine.compile("""
                    <template columns="|">
                        <columns>
                            <column align="center" width="8"/>
                            <column/>
                        </columns>
                        <line>a|b</line>
                    </template>
                    """);
            var specs = template.getColumnLayout().getColumns();
            assertEquals(2, specs.size());
            assertEquals(ColumnAlign.CENTER, specs.get(0).getAlign());
            assertFalse(specs.get(0).getWidth().isAuto());
            assertEquals(8, specs.get(0).getWidth().getFixedWidth());
            // 缺省属性：left + auto
            assertEquals(ColumnAlign.LEFT, specs.get(1).getAlign());
            assertTrue(specs.get(1).getWidth().isAuto());
        }

        @Test
        @DisplayName("width=\"auto\"：AUTO 宽")
        void testAutoWidth() {
            Template template = engine.compile("""
                    <template columns="|">
                        <columns>
                            <column width="auto"/>
                        </columns>
                        <line>a|b</line>
                    </template>
                    """);
            assertTrue(template.getColumnLayout().getColumns().get(0).getWidth().isAuto());
        }

        @Test
        @DisplayName("重复 <columns> 块：解析异常")
        void testDuplicateBlockRejected() {
            assertThrows(TemplateParseException.class, () -> engine.compile("""
                    <template columns="|">
                        <columns><column/></columns>
                        <columns><column/></columns>
                        <line>a|b</line>
                    </template>
                    """));
        }

        @Test
        @DisplayName("非法 align：解析异常")
        void testInvalidAlignRejected() {
            TemplateParseException ex = assertThrows(TemplateParseException.class,
                    () -> engine.compile("""
                            <template columns="|">
                                <columns>
                                    <column align="middle"/>
                                </columns>
                                <line>a|b</line>
                            </template>
                            """));
            assertTrue(ex.getMessage().contains("align"), "错误信息应指出 align 非法");
        }

        @Test
        @DisplayName("非法 width（非数字非 auto）：解析异常")
        void testInvalidWidthRejected() {
            assertThrows(TemplateParseException.class, () -> engine.compile("""
                    <template columns="|">
                        <columns>
                            <column width="abc"/>
                        </columns>
                        <line>a|b</line>
                    </template>
                    """));
        }

        @Test
        @DisplayName("width=\"0\"（非正整数）：解析异常")
        void testZeroWidthRejected() {
            assertThrows(TemplateParseException.class, () -> engine.compile("""
                    <template columns="|">
                        <columns>
                            <column width="0"/>
                        </columns>
                        <line>a|b</line>
                    </template>
                    """));
        }

        @Test
        @DisplayName("空 <columns> 块：启用但无策略（全列默认）")
        void testEmptyBlock() {
            Template template = engine.compile("""
                    <template>
                        <columns/>
                        <line>a|b</line>
                    </template>
                    """);
            assertTrue(template.isColumnLayoutEnabled());
            assertEquals("|", template.getColumnLayout().getSeparator());
            assertTrue(template.getColumnLayout().getColumns().isEmpty());
        }
    }

    @Nested
    @DisplayName("元数据：<meta> 标签")
    class MetaTests {

        @Test
        @DisplayName("单个 meta：key/value 收集")
        void testSingleMeta() {
            Template template = engine.compile("""
                    <template>
                        <meta key="module" value="scoreboard"/>
                        <line>内容</line>
                    </template>
                    """);
            assertEquals(1, template.getMetadata().size());
            assertEquals("scoreboard", template.getMetadata().get("module"));
            assertEquals("scoreboard", template.getMetadata("module").orElse(null));
        }

        @Test
        @DisplayName("多个 meta：全部收集")
        void testMultipleMeta() {
            Template template = engine.compile("""
                    <template>
                        <meta key="module" value="title"/>
                        <meta key="refresh" value="20"/>
                        <meta key="author" value="JiangHu"/>
                        <line>内容</line>
                    </template>
                    """);
            assertEquals(3, template.getMetadata().size());
            assertEquals("title", template.getMetadata().get("module"));
            assertEquals("20", template.getMetadata().get("refresh"));
            assertEquals("JiangHu", template.getMetadata().get("author"));
        }

        @Test
        @DisplayName("重复 key：后者覆盖（不报错）")
        void testDuplicateKeyOverridden() {
            Template template = engine.compile("""
                    <template>
                        <meta key="refresh" value="20"/>
                        <meta key="refresh" value="10"/>
                        <line>内容</line>
                    </template>
                    """);
            assertEquals(1, template.getMetadata().size());
            assertEquals("10", template.getMetadata().get("refresh"), "后者应覆盖前者");
        }

        @Test
        @DisplayName("key 缺失：解析异常")
        void testMissingKeyRejected() {
            assertThrows(TemplateParseException.class, () -> engine.compile("""
                    <template>
                        <meta value="no-key"/>
                        <line>内容</line>
                    </template>
                    """));
        }

        @Test
        @DisplayName("key 为空串：解析异常")
        void testEmptyKeyRejected() {
            assertThrows(TemplateParseException.class, () -> engine.compile("""
                    <template>
                        <meta key="" value="empty"/>
                        <line>内容</line>
                    </template>
                    """));
        }

        @Test
        @DisplayName("value 缺省：空串")
        void testMissingValueDefaultsEmpty() {
            Template template = engine.compile("""
                    <template>
                        <meta key="flag"/>
                        <line>内容</line>
                    </template>
                    """);
            assertEquals("", template.getMetadata().get("flag"));
        }

        @Test
        @DisplayName("不存在的 key：Optional.empty")
        void testAbsentKey() {
            Template template = engine.compile("""
                    <template>
                        <meta key="a" value="1"/>
                        <line>内容</line>
                    </template>
                    """);
            assertTrue(template.getMetadata("nope").isEmpty());
        }

        @Test
        @DisplayName("无 meta 标签：元数据为空 Map")
        void testNoMetaEmpty() {
            Template template = engine.compile("""
                    <template>
                        <line>内容</line>
                    </template>
                    """);
            assertTrue(template.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("元数据 Map 不可变")
        void testMetadataImmutable() {
            Template template = engine.compile("""
                    <template>
                        <meta key="a" value="1"/>
                        <line>内容</line>
                    </template>
                    """);
            assertThrows(UnsupportedOperationException.class,
                    () -> template.getMetadata().put("b", "2"));
        }
    }

    @Nested
    @DisplayName("向后兼容与未知标签")
    class CompatibilityTests {

        @Test
        @DisplayName("基础模板：列格式禁用 + 元数据为空 + 行为不变")
        void testLegacyTemplateUnchanged() {
            Template template = engine.compile("""
                    <template>
                        <title>{{serverName}} 计分板</title>
                        <line>玩家: {{name}}</line>
                        <if cond="vip"><line>§6⭐ VIP</line></if>
                        <for items="players" max="10"><line>{{index}}. {{this.name}}</line></for>
                    </template>
                    """);
            assertFalse(template.isColumnLayoutEnabled(), "基础模板列格式应禁用");
            assertTrue(template.getMetadata().isEmpty(), "基础模板元数据应为空");
            assertEquals(3, template.getLineEntries().size(), "行条目数不受影响");
        }

        @Test
        @DisplayName("未知标签：仍被静默忽略（不报错）")
        void testUnknownTagIgnored() {
            Template template = engine.compile("""
                    <template>
                        <foo bar="1">未知标签</foo>
                        <line>内容</line>
                    </template>
                    """);
            assertEquals(1, template.getLineEntries().size());
        }

        @Test
        @DisplayName("compileText 纯文本模板：元数据恒为空、列格式禁用")
        void testCompileTextEmptyMetadata() {
            Template template = engine.compileText("你好 {{name}}");
            assertTrue(template.getMetadata().isEmpty(), "纯文本模板元数据恒为空");
            assertFalse(template.isColumnLayoutEnabled());
            assertFalse(template.isXmlTemplate());
        }
    }
}
