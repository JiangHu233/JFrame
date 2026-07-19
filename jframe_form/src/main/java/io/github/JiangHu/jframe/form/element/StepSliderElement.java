package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.ElementStepSlider;
import lombok.Setter;

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
@Setter
public class StepSliderElement extends FormElement<String> {

    private List<String> steps;
    private int defaultIndex;

    /**
     * 创建步进滑块元素，{@code key} 默认与 {@code label} 相同。
     *
     * @param label        元素标签
     * @param steps        档位列表
     * @param defaultIndex 默认选中档位索引（从 0 开始）
     */
    public StepSliderElement(String label, List<String> steps, int defaultIndex) {
        this(label, label, steps, defaultIndex);
    }

    /**
     * 创建步进滑块元素，显式分离唯一标识与显示标签。
     *
     * @param key          元素唯一标识（结果取值键，不可变）
     * @param label        元素标签
     * @param steps        档位列表
     * @param defaultIndex 默认选中档位索引（从 0 开始）
     */
    public StepSliderElement(String key, String label, List<String> steps, int defaultIndex) {
        super(key, label);
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

    @Override
    protected void doApplyValue(String value) {
        // value 是被选中档位的文本，需转回索引才能作为默认选中档位
        int idx = steps.indexOf(value);
        if (idx >= 0) {
            this.defaultIndex = idx;
        }
    }
}
