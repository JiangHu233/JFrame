package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementToggle;
import lombok.Setter;

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
@Setter
public class ToggleElement extends FormElement<Boolean> {

    private boolean defaultValue;

    /**
     * 创建开关元素，{@code key} 默认与 {@code label} 相同。
     *
     * @param label        元素标签
     * @param defaultValue 默认状态（true=开，false=关）
     */
    public ToggleElement(String label, boolean defaultValue) {
        this(label, label, defaultValue);
    }

    /**
     * 创建开关元素，显式分离唯一标识与显示标签。
     *
     * @param key          元素唯一标识（结果取值键，不可变）
     * @param label        元素标签
     * @param defaultValue 默认状态（true=开，false=关）
     */
    public ToggleElement(String key, String label, boolean defaultValue) {
        super(key, label);
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

    @Override
    protected void doApplyValue(Boolean value) {
        this.defaultValue = value;
    }
}
