package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementToggle;

/**
 * 开关元素。
 * <p>
 * 对应 Nukkit {@link ElementToggle}，玩家切换开 / 关状态。
 * 值类型为 {@link Boolean}。
 *
 * <pre>{@code
 * new CustomForm("偏好")
 *     .toggle(new ToggleElement("开启 PvP", false));
 * }</pre>
 */
public class ToggleElement extends FormElement<Boolean> {

    private final boolean defaultValue;

    /**
     * 创建开关元素。
     *
     * @param label        元素标签
     * @param defaultValue 默认状态（true=开，false=关）
     */
    public ToggleElement(String label, boolean defaultValue) {
        super(label);
        this.defaultValue = defaultValue;
    }

    /** 创建默认关闭的开关元素。 */
    public ToggleElement(String label) {
        this(label, false);
    }

    @Override
    public ElementToggle toNukkit() {
        return new ElementToggle(label, defaultValue);
    }

    @Override
    protected Boolean read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        return response.getToggleResponse(index);
    }
}
