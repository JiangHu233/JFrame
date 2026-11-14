package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.IncrementalRenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量渲染单元测试——覆盖三层增量更新架构的 Layer 1（依赖提取）和 Layer 2（增量渲染）。
 *
 * <h3>测试覆盖</h3>
 * <ul>
 *   <li><b>Layer 1 - DependencyExtractor</b>：变量依赖提取、依赖图构建、isAffected 匹配</li>
 *   <li><b>Layer 2 - IncrementalRenderer</b>：全量渲染、增量渲染、结构变更检测、标题增量</li>
 * </ul>
 *
 * <h3>测试策略</h3>
 * <p>通过 {@code DataContext.onChange} 捕获 {@link ChangeSet}，
 * 传递给 {@link IncrementalRenderer#renderIncremental} 验证增量行为。
 */
@DisplayName("增量渲染单元测试")
class IncrementalRendererTest {

    private TemplateEngine engine;
    private DependencyExtractor extractor;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        extractor = new DependencyExtractor();
    }

    // ==================== Layer 1: DependencyExtractor ====================

    @Nested
    @DisplayName("Layer 1 - DependencyExtractor：变量依赖提取")
    class DependencyExtractorTests {

        @Test
        @DisplayName("简单表达式：{{name}} → 依赖 {name}")
        void testSimpleExpression() {
            Template template = engine.compile("""
                <template>
                    <line>你好，{{name}}！</line>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.contains("name"), "应包含变量 name");
            assertEquals(1, deps.size(), "只依赖 name 一个变量");
        }

        @Test
        @DisplayName("嵌套属性：{{player.name}} → 依赖 {player, name}")
        void testNestedProperty() {
            Template template = engine.compile("""
                <template>
                    <line>玩家: {{player.name}}</line>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.contains("player"), "应包含根变量 player");
            assertTrue(deps.contains("name"), "应包含嵌套属性 name");
        }

        @Test
        @DisplayName("条件块：if cond=\"vip\" → 依赖 {vip}")
        void testConditionalLine() {
            Template template = engine.compile("""
                <template>
                    <if cond="vip"><line>§6⭐ VIP 会员</line></if>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.contains("vip"), "条件块应依赖条件变量 vip");
        }

        @Test
        @DisplayName("循环块：for items=\"rankings\" → 依赖 {rankings, name}")
        void testDynamicLine() {
            Template template = engine.compile("""
                <template>
                    <for items="rankings"><line>{{this.name}}</line></for>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.contains("rankings"), "循环块应依赖列表键 rankings");
            assertTrue(deps.contains("name"), "循环体应依赖属性 name");
        }

        @Test
        @DisplayName("纯文本行：无表达式 → 依赖为空集")
        void testTextOnlyLine() {
            Template template = engine.compile("""
                <template>
                    <line>§8═══════════════</line>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.isEmpty(), "纯文本行无变量依赖");
        }

        @Test
        @DisplayName("行内 if/else 条件：依赖条件变量 + 内容变量")
        void testInlineIfElse() {
            Template template = engine.compile("""
                <template>
                    <line><if cond="vip">身份: {{rank}}</if><else>身份: 普通</else></line>
                </template>
                """);

            Set<String> deps = extractor.extractFromLineEntry(template.getLineEntries().get(0));

            assertTrue(deps.contains("vip"), "应依赖条件变量 vip");
            assertTrue(deps.contains("rank"), "应依赖内容变量 rank");
        }

        @Test
        @DisplayName("isAffected：依赖匹配判断")
        void testIsAffected() {
            // 精确匹配
            assertTrue(DependencyExtractor.isAffected(Set.of("vip"), Set.of("vip")));
            // 不匹配
            assertFalse(DependencyExtractor.isAffected(Set.of("vip"), Set.of("coins")));
            // 通配符：总是受影响
            assertTrue(DependencyExtractor.isAffected(DependencyExtractor.WILDCARD, Set.of("anything")));
            // 空依赖集：不受影响
            assertFalse(DependencyExtractor.isAffected(Set.of(), Set.of("vip")));
            // 空变更集：不受影响
            assertFalse(DependencyExtractor.isAffected(Set.of("vip"), Set.of()));
            // 多变量交集
            assertTrue(DependencyExtractor.isAffected(Set.of("name", "level"), Set.of("coins", "level")));
        }

        @Test
        @DisplayName("buildDependencyMap：全模板依赖图")
        void testBuildDependencyMap() {
            Template template = engine.compile("""
                <template>
                    <title>{{serverName}}</title>
                    <line>玩家: {{name}}</line>
                    <line>金币: {{coins}}</line>
                    <line>纯文本行</line>
                </template>
                """);

            var map = extractor.buildDependencyMap(template);

            assertEquals(3, map.size(), "应有 3 个行条目");

            var entries = template.getLineEntries();
            assertTrue(map.get(entries.get(0)).contains("name"), "第1行依赖 name");
            assertTrue(map.get(entries.get(1)).contains("coins"), "第2行依赖 coins");
            assertTrue(map.get(entries.get(2)).isEmpty(), "第3行无依赖");
        }

        @Test
        @DisplayName("extractTitleDependencies：标题依赖提取")
        void testExtractTitleDependencies() {
            Template template = engine.compile("""
                <template>
                    <title>{{serverName}} 计分板</title>
                    <line>内容</line>
                </template>
                """);

            Set<String> titleDeps = extractor.extractTitleDependencies(template);

            assertTrue(titleDeps.contains("serverName"), "标题应依赖 serverName");
        }

        @Test
        @DisplayName("extractTitleDependencies：纯文本标题无依赖")
        void testStaticTitleNoDeps() {
            Template template = engine.compile("""
                <template>
                    <title>固定标题</title>
                    <line>内容</line>
                </template>
                """);

            Set<String> titleDeps = extractor.extractTitleDependencies(template);

            assertTrue(titleDeps.isEmpty(), "纯文本标题无依赖");
        }
    }

    // ==================== Layer 2: IncrementalRenderer ====================

    @Nested
    @DisplayName("Layer 2 - IncrementalRenderer：增量渲染")
    class IncrementalRendererTests {

        /** 测试用模板：3 行独立变量 */
        private final String scoreboardSource = """
            <template>
                <title>{{serverName}} 计分板</title>
                <line>玩家: {{name}}</line>
                <line>金币: {{coins}}</line>
                <line>等级: {{level}}</line>
            </template>
            """;

        @Test
        @DisplayName("全量渲染：首次渲染生成正确输出")
        void testFullRender() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            IncrementalRenderer renderer = new IncrementalRenderer();
            IncrementalRenderResult result = renderer.renderFull(template, data);

            assertEquals("生存服 计分板", result.getTitle());
            assertEquals(3, result.getAllLines().size());
            assertEquals("玩家: Steve", result.getAllLines().get(0));
            assertEquals("金币: 1000", result.getAllLines().get(1));
            assertEquals("等级: 30", result.getAllLines().get(2));
            assertTrue(result.isStructureChanged(), "全量渲染应标记为结构变更");
            assertTrue(result.hasChanges());
        }

        @Test
        @DisplayName("增量渲染：变更金币 → 只更新第2行")
        void testIncrementalSingleChange() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 变更金币
            data.put("coins", 5000);

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.hasChanges());
            assertFalse(result.isStructureChanged());
            assertFalse(result.isTitleChanged());
            assertEquals(1, result.getChangedLineCount(), "只有1行变更");
            assertTrue(result.getChangedLineIndices().contains(1), "变更行索引为1");
            assertEquals("金币: 5000", result.getAllLines().get(1));
            assertEquals("玩家: Steve", result.getAllLines().get(0), "未变更行保持不变");
        }

        @Test
        @DisplayName("增量渲染：变更 name 和 coins → 更新第1、2行")
        void testIncrementalMultipleChanges() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 批量变更 name 和 coins
            data.putAll(Map.of("name", "Alex", "coins", 9999));

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.hasChanges());
            assertEquals(2, result.getChangedLineCount(), "2行变更");
            assertTrue(result.getChangedLineIndices().contains(0), "第1行变更");
            assertTrue(result.getChangedLineIndices().contains(1), "第2行变更");
            assertEquals("玩家: Alex", result.getAllLines().get(0));
            assertEquals("金币: 9999", result.getAllLines().get(1));
            assertEquals("等级: 30", result.getAllLines().get(2), "第3行不变");
        }

        @Test
        @DisplayName("增量渲染：无关变量变更 → 无变化")
        void testIncrementalNoChange() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);
            data.putSilently("unusedVar", "initial");

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 变更一个模板中未使用的变量
            data.put("unusedVar", "changed");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertFalse(result.hasChanges(), "无关变量变更不应产生任何变化");
            assertEquals(0, result.getChangedLineCount());
        }

        @Test
        @DisplayName("增量渲染：空变更集 → 无变化")
        void testEmptyChangeSet() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // null ChangeSet
            IncrementalRenderResult result = renderer.renderIncremental(data, null);

            assertFalse(result.hasChanges(), "null ChangeSet 不应产生变化");
        }

        @Test
        @DisplayName("结构变更：for 块列表缩短 → 回退全量渲染")
        void testStructureChange() {
            Template template = engine.compile("""
                <template>
                    <title>排行榜</title>
                    <for items="rankings"><line>{{this.name}}</line></for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.putSilently("rankings", List.of(
                Map.of("name", "Alex"),
                Map.of("name", "Steve"),
                Map.of("name", "Bob")
            ));

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            IncrementalRenderResult full = renderer.renderFull(template, data);

            assertEquals(3, full.getAllLines().size(), "初始3个元素 → 3行");

            // 缩短列表为2个元素
            data.put("rankings", List.of(
                Map.of("name", "Alex"),
                Map.of("name", "Steve")
            ));

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.isStructureChanged(), "列表长度变化应触发结构变更");
            assertTrue(result.hasChanges());
            assertEquals(2, result.getAllLines().size(), "结构变更后应为2行");
        }

        @Test
        @DisplayName("结构变更：for 块列表增长 → 回退全量渲染")
        void testStructureChangeGrow() {
            Template template = engine.compile("""
                <template>
                    <title>排行榜</title>
                    <for items="rankings"><line>{{this.name}}</line></for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.putSilently("rankings", List.of(
                Map.of("name", "Alex")
            ));

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 增长列表为3个元素
            data.put("rankings", List.of(
                Map.of("name", "Alex"),
                Map.of("name", "Steve"),
                Map.of("name", "Bob")
            ));

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.isStructureChanged(), "列表增长应触发结构变更");
            assertEquals(3, result.getAllLines().size());
        }

        @Test
        @DisplayName("标题增量：变更 serverName → titleChanged=true")
        void testTitleIncrementalChange() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            data.put("serverName", "创造服");

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.isTitleChanged(), "标题变量变更应触发 titleChanged");
            assertEquals("创造服 计分板", result.getTitle());
        }

        @Test
        @DisplayName("标题增量：变更非标题变量 → titleChanged=false")
        void testTitleNotAffected() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            data.put("coins", 5000);

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertFalse(result.isTitleChanged(), "非标题变量变更不应触发 titleChanged");
            assertEquals("生存服 计分板", result.getTitle(), "标题保持不变");
        }

        @Test
        @DisplayName("连续增量渲染：多次变更累积正确")
        void testConsecutiveIncremental() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 第一次变更：coins
            data.put("coins", 2000);
            IncrementalRenderResult r1 = renderer.renderIncremental(data, captured[0]);
            assertEquals(Set.of(1), r1.getChangedLineIndices());
            assertEquals("金币: 2000", r1.getAllLines().get(1));

            // 第二次变更：name
            data.put("name", "Alex");
            IncrementalRenderResult r2 = renderer.renderIncremental(data, captured[0]);
            assertEquals(Set.of(0), r2.getChangedLineIndices(), "只有第1行变更");
            assertEquals("玩家: Alex", r2.getAllLines().get(0));
            assertEquals("金币: 2000", r2.getAllLines().get(1), "第2行保持上次变更后的值");

            // 第三次变更：level
            data.put("level", 50);
            IncrementalRenderResult r3 = renderer.renderIncremental(data, captured[0]);
            assertEquals(Set.of(2), r3.getChangedLineIndices());
            assertEquals("等级: 50", r3.getAllLines().get(2));
        }

        @Test
        @DisplayName("增量渲染后全量行列表正确：增量结果包含所有行")
        void testIncrementalAllLinesComplete() {
            Template template = engine.compile(scoreboardSource);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "生存服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);
            data.putSilently("level", 30);

            final ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            data.put("coins", 5000);

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            // 增量结果应包含全量行列表（不仅仅是变更行）
            assertEquals(3, result.getAllLines().size(), "增量结果应包含所有行");
            assertEquals("玩家: Steve", result.getAllLines().get(0));
            assertEquals("金币: 5000", result.getAllLines().get(1));
            assertEquals("等级: 30", result.getAllLines().get(2));
        }
    }
}
