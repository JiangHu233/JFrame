package io.github.JiangHu.jframe.form.window;

import cn.nukkit.form.element.ElementButton;
import io.github.JiangHu.jframe.form.response.ButtonClick;
import lombok.Setter;

import java.util.function.Consumer;

/**
 * 面向对象的表单按钮。
 * <p>
 * 这是新架构相对旧版 {@code moe.him188.gui.window.FormSimple} 的核心改进之一：
 * 按钮不再是一个无逻辑的字符串，而是一个携带「文本 + 图标 + 点击回调」的对象。
 * <p>
 * 传统写法需要在 {@code onClicked(int id)} 中用 {@code switch(id)} 分发，
 * 按钮顺序一旦调整，所有索引都要同步修改，极易出错。本类让每个按钮自行持有回调，
 * 消除魔法索引，实现「按钮即行为」：
 *
 * <pre>{@code
 * new SimpleForm("商店")
 *     .button(new Button("购买").onClick(ctx -> buy(ctx.player())))
 *     .button(new Button("出售").onClick(ctx -> sell(ctx.player())));
 * }</pre>
 *
 * 也可使用 {@link SimpleForm#button(String, Consumer)} 等便捷方法内联创建。
 *
 * @see FormIcon
 * @see ButtonClick
 */
public class Button {

    /**
     * 按钮显示文本。
     * <p>
     * 可在运行期通过 {@link #setText(String)} 动态修改，
     * 下次 {@link #toNukkit()} 转换时生效。
     */
    @Setter
    private String text;
    private FormIcon icon;
    private Consumer<ButtonClick> handler;

    /**
     * 创建一个文本按钮。
     *
     * @param text 按钮显示文本
     */
    public Button(String text) {
        this.text = text;
    }

    /**
     * 创建一个带图标的按钮。
     *
     * @param text 按钮显示文本
     * @param icon 按钮图标
     */
    public Button(String text, FormIcon icon) {
        this.text = text;
        this.icon = icon;
    }

    /** 按钮显示文本。 */
    public String text() {
        return text;
    }

    /** 按钮图标，未设置时返回 {@code null}。 */
    public FormIcon icon() {
        return icon;
    }

    /**
     * 设置按钮图标（链式）。
     *
     * @param icon 图标
     * @return 当前按钮，便于链式调用
     */
    public Button icon(FormIcon icon) {
        this.icon = icon;
        return this;
    }

    /**
     * 注册点击回调（链式）。
     * <p>
     * 玩家点击本按钮后，框架会以 {@link ButtonClick} 为参数调用该回调。
     *
     * @param handler 点击回调
     * @return 当前按钮，便于链式调用
     */
    public Button onClick(Consumer<ButtonClick> handler) {
        this.handler = handler;
        return this;
    }

    /** 是否已注册点击回调。 */
    public boolean hasHandler() {
        return handler != null;
    }

    /**
     * 触发点击回调。由框架在收到玩家响应时调用。
     *
     * @param ctx 点击上下文
     */
    void click(ButtonClick ctx) {
        if (handler != null) {
            handler.accept(ctx);
        }
    }

    /**
     * 转换为 Nukkit 原生按钮元素。
     *
     * @return Nukkit {@link ElementButton}
     */
    ElementButton toNukkit() {
        return icon == null
                ? new ElementButton(text)
                : new ElementButton(text, icon.toNukkit());
    }
}
