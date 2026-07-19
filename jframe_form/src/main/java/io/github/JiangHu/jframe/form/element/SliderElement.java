package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementSlider;
import lombok.Setter;

/**
 * 滑块元素。
 * <p>
 * 对应 Nukkit {@link ElementSlider}，玩家拖动滑块在数值范围内选择。
 * 值类型为 {@link Float}。
 *
 * <pre>{@code
 * new CustomForm("音量")
 *     .slider(new SliderElement("音量", 0, 100, 1, 50));
 * }</pre>
 */
@Setter
public class SliderElement extends FormElement<Float> {

    private float min;
    private float max;
    private int step;
    private float defaultValue;

    /**
     * 创建滑块元素，{@code key} 默认与 {@code label} 相同。
     *
     * @param label        元素标签
     * @param min          最小值
     * @param max          最大值
     * @param step         步长
     * @param defaultValue 默认值
     */
    public SliderElement(String label, float min, float max, int step, float defaultValue) {
        this(label, label, min, max, step, defaultValue);
    }

    /**
     * 创建滑块元素，显式分离唯一标识与显示标签。
     *
     * @param key          元素唯一标识（结果取值键，不可变）
     * @param label        元素标签
     * @param min          最小值
     * @param max          最大值
     * @param step         步长
     * @param defaultValue 默认值
     */
    public SliderElement(String key, String label, float min, float max, int step, float defaultValue) {
        super(key, label);
        this.min = min;
        this.max = max;
        this.step = step;
        this.defaultValue = defaultValue;
    }

    /** 创建滑块元素，默认值为最小值。 */
    public SliderElement(String label, float min, float max, int step) {
        this(label, min, max, step, min);
    }

    /** 创建滑块元素，步长为 1，默认值为最小值。 */
    public SliderElement(String label, float min, float max) {
        this(label, min, max, 1, min);
    }

    @Override
    public ElementSlider toNukkit() {
        return new ElementSlider(label, min, max, step, defaultValue);
    }

    @Override
    protected Float read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        return response.getSliderResponse(index);
    }

    @Override
    protected void doApplyValue(Float value) {
        this.defaultValue = value;
    }
}
