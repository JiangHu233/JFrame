package io.github.JiangHu.jframe.content_template.render;

import io.github.JiangHu.jframe.content_template.RenderResult;
import io.github.JiangHu.jframe.content_template.Template;
import io.github.JiangHu.jframe.content_template.TemplateEngine;
import io.github.JiangHu.jframe.core.data.reactive.DataContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全量渲染（{@link TemplateRenderer}）出口列化的单元测试——验证：
 *
 * <ul>
 *   <li>render() 产出的行列表已是列化对齐后的最终文本（设计 3.2.4 决策 C）</li>
 *   <li>title 不参与列化</li>
 *   <li>颜色码 0 宽：含码行与纯文本行同宽对齐</li>
 *   <li>compileText 纯文本模板不受列化影响（renderText 路径回归）</li>
 * </ul>
 */
@DisplayName("TemplateRenderer 出口列化")
class TemplateRendererColumnTest {

    private TemplateEngine engine;
    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        engine = new TemplateEngine();
        renderer = new TemplateRenderer();
    }

    @Nested
    @DisplayName("render 出口列化")
    class RenderColumnizationTests {

        @Test
        @DisplayName("列化启用：输出为对齐后的行（设计 3.2 效果示例）")
        void testRenderAligned() {
            Template template = engine.compile("""
                    <template columns="|">
                        <title>{{serverName}} 计分板</title>
                        <line>名字|{{name}}</line>
                        <line>金币|{{coins}}</line>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "测试服");
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);

            RenderResult result = renderer.render(template, data);

            // 原始行 ["名字|Steve", "金币|1000"]，列宽 [4,5]（"1000" 宽 4 右补 1 空格）
            assertEquals(List.of("名字 | Steve", "金币 | 1000 "), result.getLines(),
                    "render 输出应为列化对齐后的最终文本");
        }

        @Test
        @DisplayName("title 不参与列化：含分隔符的标题原样输出")
        void testTitleNotColumnized() {
            Template template = engine.compile("""
                    <template columns="|">
                        <title>{{serverName}}</title>
                        <line>a|b</line>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("serverName", "左|右");
            data.putSilently("name", "x");

            RenderResult result = renderer.render(template, data);

            assertEquals("左|右", result.getTitle(), "标题不参与列化");
        }

        @Test
        @DisplayName("颜色码 0 宽：含码行与纯文本行同宽对齐")
        void testColorCodeAlignment() {
            Template template = engine.compile("""
                    <template columns="|">
                        <line>§a名字|{{name}}</line>
                        <line>§c金币|{{coins}}</line>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);

            RenderResult result = renderer.render(template, data);

            // "§a名字"/"§c金币" 显示宽 4（颜色码 0 宽）；列宽 [4,5]
            assertEquals(List.of("§a名字 | Steve", "§c金币 | 1000 "), result.getLines());
        }

        @Test
        @DisplayName("列化禁用：输出与原语义一致（向后兼容）")
        void testDisabledNoColumnization() {
            Template template = engine.compile("""
                    <template>
                        <line>名字|{{name}}</line>
                        <line>金币|{{coins}}</line>
                    </template>
                    """);
            DataContext data = DataContext.of();
            data.putSilently("name", "Steve");
            data.putSilently("coins", 1000);

            RenderResult result = renderer.render(template, data);

            assertEquals(List.of("名字|Steve", "金币|1000"), result.getLines(),
                    "未启用列化 → 原样输出");
        }
    }

    @Nested
    @DisplayName("renderText 纯文本路径")
    class RenderTextTests {

        @Test
        @DisplayName("compileText 模板：renderText 路径不列化")
        void testRenderTextNotColumnized() {
            Template template = engine.compileText("你好 {{name}}|raw");
            DataContext data = DataContext.of();
            data.putSilently("name", "Steve");

            // 纯文本模板走 renderText（返回单字符串），天然不经过列化
            assertEquals("你好 Steve|raw", renderer.renderText(template, data),
                    "纯文本模板天然禁用列化");
        }
    }
}
