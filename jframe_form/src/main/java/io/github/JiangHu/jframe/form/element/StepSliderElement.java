package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementStepSlider;

import java.util.Arrays;
import java.util.List;

/**
 * 步进滑块元素。
 * <p>
 * 对应 Nukkit {@link ElementStepSlider}，玩家在离散的预设档位中选择一项。
 * 与 {@link DropdownElement 下拉选择} 的区别：步进滑块以滑块形式左右切换档位，
 * 适合选项较少且语义连续的场景（如「低 / 中 / 高」）。
 * 值类型为 {@link String}（被选中档位的文本）。
 *
 * <pre>{@code
 * new CustomForm("画质")
 *     .stepSlider(new StepSliderElement("画质", "低", "中", "高"));
 * }</pre>
 */
public class StepSliderElement extends FormElement<String> {

    private final List<String> steps;
    private final int defaultIndex;

    /**
     * 创建步进滑块元素。
     *
     * @param label        元素标签
     * @param steps        档位列表
     * @param defaultIndex 默认选中档位索引（从 0 开始）
     */
    public StepSliderElement(String label, List<String> steps, int defaultIndex) {
        super(label);
        this.steps = steps;
        this.defaultIndex = defaultIndex;
    }

    /** 创建带档位的步进滑块元素，默认选中第一档。 */
    public StepSliderElement(String label, List<String> steps) {
        this(label, steps, 0);
    }

    /** 创建带档位（可变参数）的步进滑块元素，默认选中第一档。 */
    public StepSliderElement(String label, String... steps) {
        this(label, Arrays.asList(steps), 0);
    }

    @Override
    public ElementStepSlider toNukkit() {
        return new ElementStepSlider(label, steps, defaultIndex);
    }

    @Override
    protected String read(cn.nukkit.form.response.FormResponseCustom response, int index) {
        cn.nukkit.form.response.FormResponseData data = response.getStepSliderResponse(index);
        return data == null ? null : data.getElementContent();
    }
}
