package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量渲染与列格式协作的单元测试——验证 {@link IncrementalRenderer} 在列化启用时：
 *
 * <ul>
 *   <li>renderFull 输出已对齐行，并记录列宽快照</li>
 *   <li>列宽不变 → 仅输出直接变化行（最小变更集）</li>
 *   <li>列宽变化 → changedLineIndices 扩为全集（对齐整体移位需全量重发）</li>
 *   <li>结构变更 → 仍回退全量渲染</li>
 *   <li>列化禁用 → 与扩展前语义恒等（向后兼容回归）</li>
 * </ul>
 *
 * <p>对应设计文档 3.2.6（增量渲染协作）与 10.1 测试矩阵“增量协作”部分。</p>
 */
@DisplayName("增量渲染 × 列格式协作")
class IncrementalRendererColumnTest {

    private TemplateEngine engine;

    /** 两行固定结构的列化模板：第 0 行含变量 {{name}}，第 1 行固定。 */
    private static final String COLUMN_TEMPLATE = """
            <template columns="|">
                <title>{{serverName}}</title>
                <line>{{name}}|{{score}}</line>
                <line>固定|尾行</line>
            </template>
            """;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    @Nested
    @DisplayName("renderFull：输出已对齐")
    class FullRenderTests {

        @Test
        @DisplayName("全量渲染：输出为列化对齐后的行")
        void testFullRenderAligned() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            IncrementalRenderer renderer = new IncrementalRenderer();
            IncrementalRenderResult result = renderer.renderFull(template, data);

            // 原始行 ["AB|1000", "固定|尾行"]，列宽 [max(2,4)=4, max(4,4)=4]
            // "AB"(宽2) 右补 2 空格 + joiner " | "（两侧各一空格）
            assertEquals(List.of("AB   | 1000", "固定 | 尾行"), result.getAllLines(),
                    "renderFull 应输出对齐后的行");
        }

        @Test
        @DisplayName("标题不参与列化：含分隔符的标题原样输出")
        void testTitleNotColumnized() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "左|右");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            IncrementalRenderer renderer = new IncrementalRenderer();
            IncrementalRenderResult result = renderer.renderFull(template, data);

            assertEquals("左|右", result.getTitle(), "标题不参与列化");
        }
    }

    @Nested
    @DisplayName("列宽不变：最小变更集")
    class StableWidthTests {

        @Test
        @DisplayName("变更同行宽变量 → 仅直接变化行")
        void testStableWidthMinimalChange() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // "AB" → "CD"：显示宽均为 2，列宽不变
            data.put("name", "CD");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.hasChanges());
            assertFalse(result.isStructureChanged());
            assertEquals(1, result.getChangedLineCount(), "列宽不变 → 仅第 0 行变化");
            assertTrue(result.getChangedLineIndices().contains(0), "变化行应含第 0 行");
            assertEquals(List.of("CD   | 1000", "固定 | 尾行"), result.getAllLines());
        }

        @Test
        @DisplayName("空变更集 → 无变化，输出保持对齐行")
        void testEmptyChangeSetKeepsAligned() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            IncrementalRenderResult result = renderer.renderIncremental(data, null);

            assertFalse(result.hasChanges(), "null ChangeSet 不应产生变化");
            assertEquals(List.of("AB   | 1000", "固定 | 尾行"), result.getAllLines(),
                    "输出应保持对齐后的行");
        }
    }

    @Nested
    @DisplayName("列宽变化：扩为全集")
    class WidthChangeTests {

        @Test
        @DisplayName("变量变宽 → changedLineIndices 扩为全集")
        void testWidthChangeExpandsToAll() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // "AB"(宽2) → "ABCDEFG"(宽7)：第 0 列宽 4 → 7，第 1 行对齐整体移位
            data.put("name", "ABCDEFG");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.hasChanges());
            assertEquals(2, result.getChangedLineCount(), "列宽变化 → 全部行重发");
            assertTrue(result.getChangedLineIndices().contains(0));
            assertTrue(result.getChangedLineIndices().contains(1));
            // 新列宽 [7,4]："固定"(宽4) 右补 3 空格 + joiner " | "（两侧各一空格）
            assertEquals(List.of("ABCDEFG | 1000", "固定    | 尾行"), result.getAllLines());
        }

        @Test
        @DisplayName("变量变窄 → 同样扩为全集")
        void testWidthShrinkExpandsToAll() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "ABCDEFG");
            data.putSilently("score", 1000);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // "ABCDEFG"(宽7) → "AB"(宽2)：第 0 列宽 7 → 4
            data.put("name", "AB");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertEquals(2, result.getChangedLineCount(), "列宽变窄同样全量重发");
            assertEquals(List.of("AB   | 1000", "固定 | 尾行"), result.getAllLines());
        }
    }

    @Nested
    @DisplayName("结构变更：回退全量")
    class StructureChangeTests {

        @Test
        @DisplayName("for 块列表增长 → 回退全量并输出对齐行")
        void testStructureChangeFallsBackToFull() {
            Template template = engine.compile("""
                    <template columns="|">
                        <for items="rows" max="10"><line>{{this.a}}|{{this.b}}</line></for>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("rows", List.of(
                    Map.of("a", "x", "b", "1"),
                    Map.of("a", "yy", "b", "2")
            ));

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 增长列表 → 结构变更
            data.put("rows", List.of(
                    Map.of("a", "x", "b", "1"),
                    Map.of("a", "yy", "b", "2"),
                    Map.of("a", "zzz", "b", "3")
            ));

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.isStructureChanged(), "列表长度变化应触发结构变更");
            // 新列宽 [3,1]；joiner " | "（两侧各一空格）
            assertEquals(List.of("x   | 1", "yy  | 2", "zzz | 3"), result.getAllLines(),
                    "结构变更回退全量渲染，输出对齐行");
        }
    }

    @Nested
    @DisplayName("禁用列化：向后兼容回归")
    class DisabledTests {

        @Test
        @DisplayName("未启用列化 → 增量语义与扩展前一致")
        void testDisabledIdenticalToLegacy() {
            Template template = engine.compile("""
                    <template>
                        <title>{{serverName}}</title>
                        <line>{{name}}|{{score}}</line>
                        <line>固定|尾行</line>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            data.put("name", "ABCDEFG");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            // 禁用列化：不拆列、不扩全集，仅第 0 行直接变化
            assertEquals(1, result.getChangedLineCount(), "禁用列化 → 仅直接变化行");
            assertEquals(List.of("ABCDEFG|1000", "固定|尾行"), result.getAllLines(),
                    "输出为原始行，不做列化");
        }

        @Test
        @DisplayName("reset 后增量渲染：未绑定模板 → IllegalStateException")
        void testResetThenIncrementalRejected() {
            Template template = engine.compile(COLUMN_TEMPLATE);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "AB");
            data.putSilently("score", 1000);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);
            renderer.reset();

            data.put("name", "ABCDEFG");

            // reset 清空模板绑定 → 增量渲染拒绝执行（需重新 renderFull）
            assertThrows(IllegalStateException.class,
                    () -> renderer.renderIncremental(data, captured[0]));
        }
    }
}
