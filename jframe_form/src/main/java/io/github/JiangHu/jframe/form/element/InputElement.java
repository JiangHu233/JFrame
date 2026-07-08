package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementInput;

/**
 * 文本输入元素。
 * <p>
 * 对应 Nukkit {@link ElementInput}，玩家可输入任意文本。
 * 值类型为 {@link String}。
 *
 * <pre>{@code
 * new CustomForm("注册")
 *     .input(new InputElement("昵称", "请输入昵称"));
 * }</pre>
 */
public class InputElement extends FormElement<String> {

    private final String placeholder;
    private final String defaultText;

    /**
     * 创建文本输入元素。
     *
     * @param label       元素标签
     * @param placeholder 占位提示文本（输入框为空时显示）
     * @param defaultText 默认文本（输入框预填内容）
     */
    public InputElement(String label, String placeholder, String defaultText) {
        super(label);
        this.placeholder = placeholder;
        this.defaultText = defaultText;
    }

    /** 创建带占位提示的文本输入元素。 */
    public InputElement(String label, String placeholder) {
        this(label, placeholder, "");
    }

    /** 创建仅带标签的文本输入元素。 */
    public InputElement(String label) {
        this(label, "", "");
    }

    @Override
    public ElementInput toNukkit() {
        return new ElementInput(label, placeholder, defaultText);
    }

    @Override
    protected String read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        return response.getInputResponse(index);
    }
}
