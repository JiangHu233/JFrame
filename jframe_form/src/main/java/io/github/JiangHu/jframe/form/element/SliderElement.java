package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementSlider;

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
public class SliderElement extends FormElement<Float> {

    private final float min;
    private final float max;
    private final int step;
    private final float defaultValue;

    /**
     * 创建滑块元素。
     *
     * @param label        元素标签
     * @param min          最小值
     * @param max          最大值
     * @param step         步长
     * @param defaultValue 默认值
     */
    public SliderElement(String label, float min, float max, int step, float defaultValue) {
        super(label);
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
}
