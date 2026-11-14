package io.github.JiangHu.jframe.content_template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.JiangHu.jframe.content_template.render.IncrementalRenderer;
import io.github.JiangHu.jframe.core.data.reactive.ChangeSet;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 块级结构化语法测试——if/for 按位置区分语义。
 *
 * <p>覆盖：
 * <ul>
 *   <li>块级 if/elif/else 包裹行组（line + 裸文本混排）</li>
 *   <li>块级 for：var/index 自定义循环变量、嵌套作用域（互访 + 遮蔽）、max 截断</li>
 *   <li>行内 for：拼接一行、var/index、max</li>
 *   <li>废弃语法不生效：line 的 if/else 属性、each 标签、line-each 标签</li>
 *   <li>容错：孤立 elif/else、未知标签</li>
 *   <li>增量渲染：块级 if 显隐切换 → 结构变更回退全量</li>
 * </ul>
 */
class BlockSyntaxTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    @Nested
    @DisplayName("块级 if/elif/else")
    class BlockIfTests {

        @Test
        @DisplayName("if 包裹多行：line + 裸文本混排，为真时整组显示")
        void testIfWrapsLines() {
            Template template = engine.compile("""
                <template>
                    <line>§8══════</line>
                    <if cond="vip">
                        §6⭐ VIP 专区
                        <line>§e到期: {{vipExpire}}</line>
                    </if>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("vip", true);
            data.put("vipExpire", "2026-12-31");

            RenderResult result = engine.render(template, data);

            assertEquals(List.of("§8══════", "§6⭐ VIP 专区", "§e到期: 2026-12-31"), result.getLines());
        }

        @Test
        @DisplayName("if 为假：整组隐藏，行数减少")
        void testIfFalseHidesGroup() {
            Template template = engine.compile("""
                <template>
                    <line>头部</line>
                    <if cond="vip">
                        <line>VIP 行1</line>
                        <line>VIP 行2</line>
                    </if>
                    <line>尾部</line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("vip", false);

            RenderResult result = engine.render(template, data);

            assertEquals(List.of("头部", "尾部"), result.getLines());
        }

        @Test
        @DisplayName("elif 链：按顺序取首个为真的分支")
        void testElifChain() {
            Template template = engine.compile("""
                <template>
                    <if cond="level >= 100">
                        <line>§6传奇</line>
                    </if>
                    <elif cond="level >= 50">
                        <line>§e高级</line>
                    </elif>
                    <else>
                        <line>§7普通</line>
                    </else>
                </template>
                """);

            // 场景1：level=80 → elif 分支
            DataContext data = DataContext.of();
            data.put("level", 80);
            assertEquals(List.of("§e高级"), engine.render(template, data).getLines());

            // 场景2：level=10 → else 分支
            data.put("level", 10);
            assertEquals(List.of("§7普通"), engine.render(template, data).getLines());

            // 场景3：level=120 → if 分支
            data.put("level", 120);
            assertEquals(List.of("§6传奇"), engine.render(template, data).getLines());
        }

        @Test
        @DisplayName("块级 if 嵌套：内层条件可引用外层变量")
        void testNestedBlockIf() {
            Template template = engine.compile("""
                <template>
                    <if cond="vip">
                        <if cond="vipLevel >= 3">
                            <line>高级 VIP</line>
                        </if>
                        <line>VIP 基础行</line>
                    </if>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("vip", true);
            data.put("vipLevel", 5);

            assertEquals(List.of("高级 VIP", "VIP 基础行"), engine.render(template, data).getLines());
        }
    }

    @Nested
    @DisplayName("块级 for")
    class BlockForTests {

        @Test
        @DisplayName("for 每元素渲染整组子条目（每元素两行）")
        void testForRendersGroupPerItem() {
            Template template = engine.compile("""
                <template>
                    <for items="teams">
                        <line>§e{{this.name}}</line>
                        <line>§7成员: {{this.count}}人</line>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("teams", List.of(
                Map.of("name", "红队", "count", 3),
                Map.of("name", "蓝队", "count", 5)
            ));

            RenderResult result = engine.render(template, data);

            assertEquals(List.of(
                "§e红队", "§7成员: 3人",
                "§e蓝队", "§7成员: 5人"
            ), result.getLines());
        }

        @Test
        @DisplayName("var/index 自定义循环变量名")
        void testCustomVarAndIndex() {
            Template template = engine.compile("""
                <template>
                    <for items="items" var="item" index="i">
                        <line>{{i}}. {{item}}</line>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("items", List.of("剑", "盾", "药水"));

            RenderResult result = engine.render(template, data);

            assertEquals(List.of("0. 剑", "1. 盾", "2. 药水"), result.getLines());
        }

        @Test
        @DisplayName("嵌套 for：外层变量内层可见，同名内层遮蔽外层")
        void testNestedForScoping() {
            Template template = engine.compile("""
                <template>
                    <for items="groups" var="g" index="gi">
                        <for items="g.members" var="m" index="mi">
                            <line>{{gi}}-{{mi}} {{g.name}}/{{m}}</line>
                        </for>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("groups", List.of(
                Map.of("name", "A", "members", List.of("a1", "a2")),
                Map.of("name", "B", "members", List.of("b1"))
            ));

            RenderResult result = engine.render(template, data);

            assertEquals(List.of(
                "0-0 A/a1", "0-1 A/a2", "1-0 B/b1"
            ), result.getLines());
        }

        @Test
        @DisplayName("嵌套 for 同名遮蔽：内层 this 覆盖外层 this")
        void testNestedForShadowing() {
            Template template = engine.compile("""
                <template>
                    <for items="outer" var="x">
                        <for items="inner" var="x">
                            <line>{{x}}</line>
                        </for>
                        <line>外层: {{x}}</line>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("outer", List.of("O"));
            data.put("inner", List.of("I1", "I2"));

            RenderResult result = engine.render(template, data);

            // 内层 x 遮蔽外层；离开内层后外层 x 恢复可见
            assertEquals(List.of("I1", "I2", "外层: O"), result.getLines());
        }

        @Test
        @DisplayName("max 截断：限制迭代元素数（每元素多行时总行数 = max × 每元素行数）")
        void testMaxTruncation() {
            Template template = engine.compile("""
                <template>
                    <for items="players" var="p" max="2">
                        <line>{{p.name}}</line>
                        <line>§7分数: {{p.score}}</line>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("players", List.of(
                Map.of("name", "A", "score", 1),
                Map.of("name", "B", "score", 2),
                Map.of("name", "C", "score", 3)
            ));

            RenderResult result = engine.render(template, data);

            assertEquals(4, result.getLines().size(), "max=2 × 每元素2行 = 4行");
            assertEquals("A", result.getLines().get(0));
            assertEquals("C".equals(result.getLines().get(0)) ? "" : "B", result.getLines().get(2));
        }

        @Test
        @DisplayName("for 内嵌块级 if：循环体内条件分支")
        void testForWithBlockIf() {
            Template template = engine.compile("""
                <template>
                    <for items="players" var="p">
                        <if cond="p.online">
                            <line>§a{{p.name}} 在线</line>
                        </if>
                        <else>
                            <line>§7{{p.name}} 离线</line>
                        </else>
                    </for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("players", List.of(
                Map.of("name", "A", "online", true),
                Map.of("name", "B", "online", false)
            ));

            RenderResult result = engine.render(template, data);

            assertEquals(List.of("§aA 在线", "§7B 离线"), result.getLines());
        }
    }

    @Nested
    @DisplayName("行内 for")
    class InlineForTests {

        @Test
        @DisplayName("行内 for 拼接一行：var/index/max 生效")
        void testInlineFor() {
            Template template = engine.compile("""
                <template>
                    <line>好友: <for items="friends" var="f" index="i" max="3">[{{i}}]{{f}} </for></line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("friends", List.of("A", "B", "C", "D", "E"));

            RenderResult result = engine.render(template, data);

            // 行尾空白会被渲染器 strip() 剔除（避免 sidebar 行尾多余空格）
            assertEquals(List.of("好友: [0]A [1]B [2]C"), result.getLines());
        }

        @Test
        @DisplayName("行内 for 缺省变量：this/index")
        void testInlineForDefaults() {
            Template template = engine.compile("""
                <template>
                    <line><for items="names">{{this}}-</for></line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("names", List.of("X", "Y"));

            assertEquals(List.of("X-Y-"), engine.render(template, data).getLines());
        }
    }

    @Nested
    @DisplayName("废弃语法不生效")
    class DeprecatedSyntaxTests {

        @Test
        @DisplayName("line 的 if 属性被忽略：无条件显示")
        void testLineIfAttrIgnored() {
            Template template = engine.compile("""
                <template>
                    <line if="vip">VIP 行</line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("vip", false);

            // if 属性已废弃：行无条件显示（条件请用块级 if 包裹）
            assertEquals(List.of("VIP 行"), engine.render(template, data).getLines());
        }

        @Test
        @DisplayName("each / line-each 标签被忽略：不产生行")
        void testEachTagsIgnored() {
            Template template = engine.compile("""
                <template>
                    <line>头部</line>
                    <each items="names">{{this}}</each>
                    <line-each items="names">{{this}}</line-each>
                    <line>尾部</line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("names", List.of("A", "B"));

            // each/line-each 已废弃：作为未知标签忽略，不产生任何行
            assertEquals(List.of("头部", "尾部"), engine.render(template, data).getLines());
        }
    }

    @Nested
    @DisplayName("容错")
    class RobustnessTests {

        @Test
        @DisplayName("孤立 elif/else：跳过不报错")
        void testOrphanElifElse() {
            Template template = engine.compile("""
                <template>
                    <line>头部</line>
                    <elif cond="a">孤儿分支</elif>
                    <else>孤儿else</else>
                    <line>尾部</line>
                </template>
                """);

            DataContext data = DataContext.of();

            assertEquals(List.of("头部", "尾部"), engine.render(template, data).getLines());
        }

        @Test
        @DisplayName("行区域裸文本：与 line 标签等价")
        void testBareTextLines() {
            Template template = engine.compile("""
                <template>
                    第一行
                    <line>第二行 {{name}}</line>
                    第三行
                </template>
                """);

            DataContext data = DataContext.of();
            data.put("name", "Steve");

            assertEquals(List.of("第一行", "第二行 Steve", "第三行"), engine.render(template, data).getLines());
        }
    }

    @Nested
    @DisplayName("增量渲染集成")
    class IncrementalTests {

        @Test
        @DisplayName("块级 if 显隐切换 → 行数变化 → 结构变更回退全量")
        void testBlockIfVisibilityToggle() {
            Template template = engine.compile("""
                <template>
                    <line>头部</line>
                    <if cond="vip">
                        <line>VIP 行1</line>
                        <line>VIP 行2</line>
                    </if>
                    <line>尾部</line>
                </template>
                """);

            DataContext data = DataContext.of();
            data.putSilently("vip", false);

            ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // vip: false → true，块显隐切换 → 行数 2 → 4 → 结构变更
            data.put("vip", true);

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertTrue(result.isStructureChanged(), "块显隐切换应触发结构变更");
            assertEquals(List.of("头部", "VIP 行1", "VIP 行2", "尾部"), result.getAllLines());
        }

        @Test
        @DisplayName("块级 for 列表内容变化但行数不变 → 增量更新")
        void testForContentChangeIncremental() {
            Template template = engine.compile("""
                <template>
                    <for items="rankings"><line>{{this.name}}</line></for>
                </template>
                """);

            DataContext data = DataContext.of();
            data.putSilently("rankings", List.of(
                Map.of("name", "Alex"),
                Map.of("name", "Steve")
            ));

            ChangeSet[] captured = new ChangeSet[1];
            data.onChange(cs -> captured[0] = cs);

            IncrementalRenderer renderer = new IncrementalRenderer();
            renderer.renderFull(template, data);

            // 名单变化但长度不变 → 行数不变 → 增量更新
            data.put("rankings", List.of(
                Map.of("name", "Bob"),
                Map.of("name", "Steve")
            ));

            IncrementalRenderResult result = renderer.renderIncremental(data, captured[0]);

            assertFalse(result.isStructureChanged(), "行数不变不应触发结构变更");
            assertEquals(List.of("Bob", "Steve"), result.getAllLines());
            assertEquals(1, result.getChangedLineCount(), "只有第一行变化");
        }
    }
}
