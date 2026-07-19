package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementDropdown;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * 下拉选择元素。
 * <p>
 * 对应 Nukkit {@link ElementDropdown}，玩家从预设选项中选择一项。
 * 值类型为 {@link String}（被选中选项的文本）。
 *
 * <pre>{@code
 * new CustomForm("设置")
 *     .dropdown(new DropdownElement("难度", "简单", "普通", "困难"));
 * }</pre>
 */
@Setter
public class DropdownElement extends FormElement<String> {

    private List<String> options;
    private int defaultIndex;

    /**
     * 创建下拉选择元素，{@code key} 默认与 {@code label} 相同。
     *
     * @param label        元素标签
     * @param options      可选项列表
     * @param defaultIndex 默认选中项索引（从 0 开始）
     */
    public DropdownElement(String label, List<String> options, int defaultIndex) {
        this(label, label, options, defaultIndex);
    }

    /**
     * 创建下拉选择元素，显式分离唯一标识与显示标签。
     *
     * @param key          元素唯一标识（结果取值键，不可变）
     * @param label        元素标签
     * @param options      可选项列表
     * @param defaultIndex 默认选中项索引（从 0 开始）
     */
    public DropdownElement(String key, String label, List<String> options, int defaultIndex) {
        super(key, label);
        this.options = options;
        this.defaultIndex = defaultIndex;
    }

    /** 创建带可选项的下拉元素，默认选中第一项。 */
    public DropdownElement(String label, List<String> options) {
        this(label, options, 0);
    }

    /** 创建带可选项（可变参数）的下拉元素，默认选中第一项。 */
    public DropdownElement(String label, String... options) {
        this(label, Arrays.asList(options), 0);
    }

    @Override
    public ElementDropdown toNukkit() {
        return new ElementDropdown(label, options, defaultIndex);
    }

    @Override
    protected String read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        cn.nukkit.form.response.FormResponseData data = response.getDropdownResponse(index);
        return data == null ? null : data.getElementContent();
    }

    @Override
    protected void doApplyValue(String value) {
        // value 是被选中选项的文本，需转回索引才能作为默认选中项
        int idx = options.indexOf(value);
        if (idx >= 0) {
            this.defaultIndex = idx;
        }
    }
}
