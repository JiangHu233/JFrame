package io.github.JiangHu.jframe.form.window;

import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowSimple;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.response.ButtonClick;
import io.github.JiangHu.jframe.form.response.FormResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 简单表单（按钮列表）。
 * <p>
 * 这是最常用的表单类型：一个标题 + 一段内容 + 若干按钮。
 * 对应 Nukkit {@link FormWindowSimple}。
 * <p>
 * 采用链式 Builder 风格构建，配合面向对象的 {@link Button}，消除传统的 {@code switch(id)} 魔法索引：
 *
 * <pre>{@code
 * new SimpleForm("§6主菜单")
 *     .content("§7请选择功能")
 *     .button(new Button("§a查看统计")
 *         .onClick(ctx -> ctx.view().addStack(new StatsView())))
 *     .button(new Button("§c关闭")
 *         .onClick(ctx -> ctx.close()));
 * }</pre>
 *
 * 也可使用便捷重载 {@link #button(String, Consumer)} 内联创建按钮。
 *
 * @see Button
 */
public class SimpleForm extends JForm {

    private String content = "";
    private final List<Button> buttons = new ArrayList<>();

    public SimpleForm(String title) {
        super(title);
    }

    /**
     * 设置正文内容（链式）。
     *
     * @param content 正文（支持 § 颜色代码与换行）
     * @return 当前表单
     */
    public SimpleForm content(String content) {
        this.content = content;
        return this;
    }

    /** 正文内容。 */
    public String content() {
        return content;
    }

    /**
     * 追加一个按钮对象（链式）。
     *
     * @param button 按钮对象
     * @return 当前表单
     */
    public SimpleForm button(Button button) {
        buttons.add(button);
        return this;
    }

    /**
     * 追加一个纯文本按钮并注册点击回调（链式便捷方法）。
     *
     * @param text    按钮文本
     * @param handler 点击回调
     * @return 当前表单
     */
    public SimpleForm button(String text, Consumer<ButtonClick> handler) {
        return button(new Button(text).onClick(handler));
    }

    /**
     * 追加一个纯文本按钮（链式便捷方法，无回调）。
     * <p>
     * 适用于按钮逻辑统一在 {@link FormView#onResult} 中处理的场景。
     *
     * @param text 按钮文本
     * @return 当前表单
     */
    public SimpleForm button(String text) {
        return button(new Button(text));
    }

    /** 全部按钮（不可变视图）。 */
    public List<Button> buttons() {
        return List.copyOf(buttons);
    }

    @Override
    public Type type() {
        return Type.SIMPLE;
    }

    @Override
    protected FormWindowSimple buildWindow() {
        FormWindowSimple win = new FormWindowSimple(title, content);
        for (Button b : buttons) {
            win.addButton(b.toNukkit());
        }
        return win;
    }

    @Override
    public FormResult buildResult(Player player) {
        if (window == null) {
            return FormResult.closed(player, Type.SIMPLE);
        }
        FormResponseSimple resp = (FormResponseSimple) window.getResponse();
        int id = resp.getClickedButtonId();
        // 索引越界（如玩家关闭窗口时可能返回 -1）视为关闭
        if (id < 0 || id >= buttons.size()) {
            return FormResult.closed(player, Type.SIMPLE);
        }
        return FormResult.simple(player, buttons.get(id), id);
    }

    @Override
    public void dispatch(FormView view, FormResult result) {
        if (result.wasClosed()) return;
        Button clicked = result.clickedButton();
        if (clicked == null) return;
        ButtonClick ctx = new ButtonClick(
                result.player(), result.clickedIndex(), clicked, view);
        clicked.click(ctx);
    }
}
