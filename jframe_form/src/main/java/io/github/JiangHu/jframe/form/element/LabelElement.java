package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementLabel;

/**
 * 纯文本标签元素。
 * <p>
 * 对应 Nukkit {@link ElementLabel}，仅用于展示一段静态文本，不接受输入。
 * 常用于在自定义表单中分隔区块或提供说明。
 * 值类型为 {@link String}（标签文本本身）。
 *
 * <pre>{@code
 * new CustomForm("帮助")
 *     .label(new LabelElement("§e以下设置将影响游戏难度"))
 *     .toggle(new ToggleElement("困难模式"));
 * }</pre>
 */
public class LabelElement extends FormElement<String> {

    private final String text;

    /**
     * 创建文本标签元素。
     *
     * @param text 显示的文本
     */
    public LabelElement(String text) {
        super(text);
        this.text = text;
    }

    @Override
    public ElementLabel toNukkit() {
        return new ElementLabel(text);
    }

    @Override
    protected String read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        return text;
    }
}
