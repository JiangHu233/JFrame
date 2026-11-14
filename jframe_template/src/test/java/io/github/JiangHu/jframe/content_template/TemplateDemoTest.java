package io.github.JiangHu.jframe.content_template;

import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板引擎单元测试——覆盖从 XML 源码编译到渲染的完整流程。
 *
 * <p>测试特性：
 * <ul>
 *   <li>基本 {@code {{ }} } 插值</li>
 *   <li>SpEL 算术运算 / 三元表达式 / 方法调用</li>
 *   <li>嵌套属性访问（{@code player.name}）</li>
 *   <li>{@code <if>/<elif>/<else>} 条件渲染</li>
 *   <li>{@code <for>} 循环渲染（排行榜）</li>
 *   <li>DataContext 响应式更新（同一模板，不同数据 → 不同结果）</li>
 *   <li>纯文本模板（{@code compileText}）</li>
 *   <li>模板缓存（相同源码编译返回同一实例）</li>
 * </ul>
 */
@DisplayName("模板引擎单元测试")
class TemplateDemoTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
    }

    // ==================== 1. 基本插值 ====================

    @Test
    @DisplayName("基本插值：{{变量名}} 替换为数据值")
    void testBasicInterpolation() {
        Template template = engine.compile("""
            <template>
                <title>欢迎</title>
                <line>你好，{{name}}！</line>
                <line>你的等级是 {{level}}</line>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("name", "Steve");
        data.put("level", 30);

        RenderResult result = engine.render(template, data);

        assertEquals("欢迎", result.getTitle());
        assertEquals(2, result.getLines().size());
        assertEquals("你好，Steve！", result.getLines().get(0));
        assertEquals("你的等级是 30", result.getLines().get(1));
    }

    // ==================== 2. SpEL 表达式 ====================

    @Test
    @DisplayName("SpEL 表达式：算术运算、三元判断、方法调用")
    void testSpelExpressions() {
        Template template = engine.compile("""
            <template>
                <title>属性面板</title>
                <line>攻击力: {{attack}}</line>
                <line>暴击率: {{critRate * 100}}%</line>
                <line>总伤害: {{attack * (1 + critRate)}}</line>
                <line>状态: {{hp > 50 ? '健康' : '危险'}}</line>
                <line>职业: {{job.toUpperCase()}}</line>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("attack", 150);
        data.put("critRate", 0.3);
        data.put("hp", 80);
        data.put("job", "warrior");

        RenderResult result = engine.render(template, data);

        assertEquals("属性面板", result.getTitle());
        List<String> lines = result.getLines();
        assertEquals("攻击力: 150", lines.get(0));
        assertEquals("暴击率: 30.0%", lines.get(1));
        assertEquals("总伤害: 195.0", lines.get(2));
        assertEquals("状态: 健康", lines.get(3));
        assertEquals("职业: WARRIOR", lines.get(4));
    }

    // ==================== 3. 条件渲染 ====================

    @Nested
    @DisplayName("条件渲染：if / elif / else")
    class ConditionalRendering {

        private final String source = """
            <template>
                <title>玩家信息</title>
                <line>玩家: {{name}}</line>
                <line><if cond="vip">身份: §6[VIP]</if><elif cond="level >= 50">身份: §b[高手]</elif><else>身份: §7[普通]</else></line>
                <line><if cond="online">状态: §a在线</if><else>状态: §c离线</else></line>
            </template>
            """;

        @Test
        @DisplayName("VIP 玩家：命中 if 分支")
        void testVipPlayer() {
            Template template = engine.compile(source);
            DataContext data = DataContext.of();
            data.put("name", "Notch");
            data.put("vip", true);
            data.put("level", 80);
            data.put("online", true);

            RenderResult result = engine.render(template, data);

            assertEquals("玩家信息", result.getTitle());
            assertEquals("玩家: Notch", result.getLines().get(0));
            assertEquals("身份: §6[VIP]", result.getLines().get(1));
            assertEquals("状态: §a在线", result.getLines().get(2));
        }

        @Test
        @DisplayName("高手玩家：命中 elif 分支（非 VIP，等级 >= 50）")
        void testProPlayer() {
            Template template = engine.compile(source);
            DataContext data = DataContext.of();
            data.put("name", "Dinnerbone");
            data.put("vip", false);
            data.put("level", 65);
            data.put("online", true);

            RenderResult result = engine.render(template, data);

            assertEquals("玩家: Dinnerbone", result.getLines().get(0));
            assertEquals("身份: §b[高手]", result.getLines().get(1));
            assertEquals("状态: §a在线", result.getLines().get(2));
        }

        @Test
        @DisplayName("普通玩家：命中 else 分支（非 VIP，等级 < 50，离线）")
        void testNormalPlayer() {
            Template template = engine.compile(source);
            DataContext data = DataContext.of();
            data.put("name", "Steve");
            data.put("vip", false);
            data.put("level", 10);
            data.put("online", false);

            RenderResult result = engine.render(template, data);

            assertEquals("玩家: Steve", result.getLines().get(0));
            assertEquals("身份: §7[普通]", result.getLines().get(1));
            assertEquals("状态: §c离线", result.getLines().get(2));
        }

        @Test
        @DisplayName("多行写法：<line> 内嵌 if/else 各占一行，渲染输出不含换行符")
        void testMultilineIfElseNoWhitespace() {
            // 模拟用户实际场景：<line> 标签内，<if>/<else> 各占一行（带缩进）
            // 修复前：渲染结果含换行符（如 "\n 222\n "），导致 Bedrock 客户端显示 "..."
            // 修复后：渲染结果无换行符（如 "222"）
            Template template = engine.compile("""
                <template>
                    <title>FapEFT</title>
                    <line>§8═════</line>
                    <line>
                        <if cond="matching">111</if>
                        <else>222</else>
                    </line>
                    <line>§8═════</line>
                </template>
                """);

            // 场景1：matching=true → 命中 if 分支
            DataContext data1 = DataContext.of();
            data1.put("matching", true);
            RenderResult result1 = engine.render(template, data1);

            String ifLine = result1.getLines().get(1);
            assertFalse(ifLine.contains("\n"), "if 分支渲染结果不应包含换行符，实际值: [" + ifLine.replace("\n", "\\n") + "]");
            assertEquals("111", ifLine);

            // 场景2：matching=false → 命中 else 分支
            DataContext data2 = DataContext.of();
            data2.put("matching", false);
            RenderResult result2 = engine.render(template, data2);

            String elseLine = result2.getLines().get(1);
            assertFalse(elseLine.contains("\n"), "else 分支渲染结果不应包含换行符，实际值: [" + elseLine.replace("\n", "\\n") + "]");
            assertEquals("222", elseLine);
        }

        @Test
        @DisplayName("if/else 分支内容跨行写法，strip 去除首尾换行")
        void testIfBranchContentMultiline() {
            // if/else 分支内容本身跨行（内容前后有换行+缩进）
            // 修复前：渲染为 "\n        AAA\n    "（含换行）
            // 修复后（渲染层 strip）：渲染为 "AAA"（无换行）
            Template template = engine.compile("""
                <template>
                    <line>
                        <if cond="a">
                            AAA
                        </if>
                        <else>
                            BBB
                        </else>
                    </line>
                </template>
                """);

            // 场景1：a=true → if 分支内容跨行
            DataContext data1 = DataContext.of();
            data1.put("a", true);
            RenderResult result1 = engine.render(template, data1);

            String ifLine = result1.getLines().get(0);
            assertFalse(ifLine.contains("\n"), "if 分支内容跨行渲染结果不应包含换行符，实际值: [" + ifLine.replace("\n", "\\n") + "]");
            assertEquals("AAA", ifLine);

            // 场景2：a=false → else 分支内容跨行
            DataContext data2 = DataContext.of();
            data2.put("a", false);
            RenderResult result2 = engine.render(template, data2);

            String elseLine = result2.getLines().get(0);
            assertFalse(elseLine.contains("\n"), "else 分支内容跨行渲染结果不应包含换行符，实际值: [" + elseLine.replace("\n", "\\n") + "]");
            assertEquals("BBB", elseLine);
        }
    }

    // ==================== 4. 循环渲染 ====================

    @Test
    @DisplayName("循环渲染：for 块展开排行榜列表")
    void testEachLoop() {
        Template template = engine.compile("""
            <template>
                <title>击杀排行榜</title>
                <for items="rankings"><line>第{{index + 1}}名: {{this.name}} - {{this.kills}}击杀</line></for>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("rankings", List.of(
            Map.of("name", "Alex", "kills", 120),
            Map.of("name", "Steve", "kills", 95),
            Map.of("name", "Herobrine", "kills", 50)
        ));

        RenderResult result = engine.render(template, data);

        assertEquals("击杀排行榜", result.getTitle());
        List<String> lines = result.getLines();
        assertEquals(3, lines.size());
        assertEquals("第1名: Alex - 120击杀", lines.get(0));
        assertEquals("第2名: Steve - 95击杀", lines.get(1));
        assertEquals("第3名: Herobrine - 50击杀", lines.get(2));
    }

    @Test
    @DisplayName("for 块裸文本跨行写法，strip 去除首尾换行")
    void testLineEachBodyMultiline() {
        // for 块内裸文本跨行写法（文本前后有换行+缩进）
        // 修复前：每行渲染为 "\n    Alex\n"（含换行）
        // 修复后（渲染层 strip）：每行渲染为 "Alex"（无换行）
        Template template = engine.compile("""
            <template>
                <title>测试</title>
                <for items="players">
                    {{this}}
                </for>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("players", List.of("Alex", "Steve"));

        RenderResult result = engine.render(template, data);

        List<String> lines = result.getLines();
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).contains("\n"), "for 块第一行不应包含换行符，实际值: [" + lines.get(0).replace("\n", "\\n") + "]");
        assertEquals("Alex", lines.get(0));
        assertFalse(lines.get(1).contains("\n"), "for 块第二行不应包含换行符，实际值: [" + lines.get(1).replace("\n", "\\n") + "]");
        assertEquals("Steve", lines.get(1));
    }

    // ==================== 5. 完整计分板模板 ====================

    @Test
    @DisplayName("综合模板：静态行 + 动态行 + 条件 + 循环")
    void testFullScoreboard() {
        Template template = engine.compile("""
            <template>
                <title>§e§l⚔ 战斗信息</title>
                <line>§8═══════════════</line>
                <line>§c生命: {{hp}}/{{maxHp}}</line>
                <line>§a攻击: {{attack}}</line>
                <line><if cond="buff != null">§d增益: {{buff}}</if><else>§7无增益效果</else></line>
                <line>§8═══════════════</line>
                <for items="kills"><line>§6#{{index + 1}} §f{{this.target}} §7×{{this.count}}</line></for>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("hp", 85);
        data.put("maxHp", 100);
        data.put("attack", 42);
        data.put("buff", "力量 II");
        data.put("kills", List.of(
            Map.of("target", "僵尸", "count", 15),
            Map.of("target", "骷髅", "count", 8),
            Map.of("target", "蜘蛛", "count", 3)
        ));

        RenderResult result = engine.render(template, data);

        assertEquals("§e§l⚔ 战斗信息", result.getTitle());
        List<String> lines = result.getLines();
        assertEquals(8, lines.size());
        assertEquals("§8═══════════════", lines.get(0));
        assertEquals("§c生命: 85/100", lines.get(1));
        assertEquals("§a攻击: 42", lines.get(2));
        assertEquals("§d增益: 力量 II", lines.get(3));
        assertEquals("§8═══════════════", lines.get(4));
        assertEquals("§6#1 §f僵尸 §7×15", lines.get(5));
        assertEquals("§6#2 §f骷髅 §7×8", lines.get(6));
        assertEquals("§6#3 §f蜘蛛 §7×3", lines.get(7));
    }

    @Test
    @DisplayName("综合模板：buff 为 null 时走 else 分支")
    void testFullScoreboardNoBuff() {
        Template template = engine.compile("""
            <template>
                <title>§e§l⚔ 战斗信息</title>
                <line>§c生命: {{hp}}/{{maxHp}}</line>
                <line><if cond="buff != null">§d增益: {{buff}}</if><else>§7无增益效果</else></line>
            </template>
            """);

        DataContext data = DataContext.of();
        data.put("hp", 50);
        data.put("maxHp", 100);
        // buff 不设置 → get 返回 null

        RenderResult result = engine.render(template, data);

        List<String> lines = result.getLines();
        assertEquals("§c生命: 50/100", lines.get(0));
        assertEquals("§7无增益效果", lines.get(1));
    }

    // ==================== 6. 响应式更新 ====================

    @Nested
    @DisplayName("响应式更新：同一模板，不同数据 → 不同结果")
    class ResponsiveUpdate {

        private final String source = """
            <template>
                <title>§e经济系统</title>
                <line>§7玩家: §f{{name}}</line>
                <line>§7金币: §6{{coins}}</line>
                <line>§7排名: §e#{{rank}}</line>
            </template>
            """;

        @Test
        @DisplayName("初始状态渲染")
        void testInitialState() {
            Template template = engine.compile(source);
            DataContext data = DataContext.of();
            data.put("name", "Steve");
            data.put("coins", 1000);
            data.put("rank", 3);

            RenderResult result = engine.render(template, data);

            assertEquals("§e经济系统", result.getTitle());
            assertEquals("§7玩家: §fSteve", result.getLines().get(0));
            assertEquals("§7金币: §61000", result.getLines().get(1));
            assertEquals("§7排名: §e#3", result.getLines().get(2));
        }

        @Test
        @DisplayName("数据更新后重新渲染（模板不变，数据变化）")
        void testUpdatedState() {
            Template template = engine.compile(source);
            DataContext data = DataContext.of();
            data.put("name", "Steve");
            data.put("coins", 1000);
            data.put("rank", 3);

            // 第一次渲染
            RenderResult before = engine.render(template, data);
            assertEquals("§7金币: §61000", before.getLines().get(1));

            // 模拟金币变化——只需更新数据，重新渲染即可
            data.put("coins", 5000);
            data.put("rank", 1);

            RenderResult after = engine.render(template, data);
            assertEquals("§7金币: §65000", after.getLines().get(1));
            assertEquals("§7排名: §e#1", after.getLines().get(2));
        }
    }

    // ==================== 7. 纯文本模板 ====================

    @Test
    @DisplayName("纯文本模板：compileText + renderText")
    void testTextTemplate() {
        Template template = engine.compileText("欢迎 {{name}} 来到 {{server}}！当前在线 {{online}} 人。");

        DataContext data = DataContext.of();
        data.put("name", "Steve");
        data.put("server", "生存服");
        data.put("online", 42);

        String text = engine.renderText(template, data);

        assertEquals("欢迎 Steve 来到 生存服！当前在线 42 人。", text);
    }

    // ==================== 8. 模板缓存 ====================

    @Test
    @DisplayName("模板缓存：相同源码编译返回同一实例")
    void testTemplateCache() {
        String source = "<template><line>Hello {{name}}</line></template>";

        Template t1 = engine.compile(source);
        Template t2 = engine.compile(source);

        assertNotNull(t1);
        assertTrue(t1 == t2, "相同源码应返回缓存的同一实例");
    }

    @Test
    @DisplayName("模板缓存：不同源码编译返回不同实例")
    void testDifferentSourceDifferentTemplate() {
        Template t1 = engine.compile("<template><line>A</line></template>");
        Template t2 = engine.compile("<template><line>B</line></template>");

        assertTrue(t1 != t2, "不同源码应返回不同实例");
    }
}
