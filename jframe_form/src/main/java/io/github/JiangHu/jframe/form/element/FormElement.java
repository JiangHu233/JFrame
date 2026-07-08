package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.Element;
import cn.nukkit.form.response.FormResponseCustom;

/**
 * 类型化的自定义表单元素抽象基类。
 * <p>
 * Nukkit 原生 {@link FormResponseCustom} 为不同元素提供了不同的读取方法
 * （{@code getInputResponse} / {@code getDropdownResponse} / {@code getSliderResponse} …），
 * 调用方必须记住「第几个元素用哪个方法」，且返回值类型各异，极易出错。
 * <p>
 * 本抽象为每种元素提供统一的「构建 + 类型化读取」契约：
 * <ul>
 *   <li>{@link #toNukkit()} —— 将本元素转换为 Nukkit {@link Element}，由 {@link io.github.JiangHu.jframe.form.window.CustomForm} 统一收集</li>
 *   <li>{@link #read(FormResponseCustom, int)} —— 按元素索引从响应中读取类型化值</li>
 * </ul>
 * 泛型 {@code <T>} 表示该元素的值类型，使取值类型安全：
 *
 * <pre>{@code
 * InputElement name = new InputElement("玩家名");
 * // ... 提交后 ...
 * String value = name.value();   // 直接是 String，无需强转
 * }</pre>
 *
 * @param <T> 元素的值类型
 */
public abstract class FormElement<T> {

    /** 元素标签（显示在输入框左侧的提示文字）。 */
    protected final String label;

    protected FormElement(String label) {
        this.label = label;
    }

    /** 元素标签。 */
    public String label() {
        return label;
    }

    /**
     * 转换为 Nukkit 原生元素。
     *
     * @return Nukkit {@link Element}
     */
    public abstract Element toNukkit();

    /**
     * 按元素索引从响应中读取类型化值。
     * <p>
     * 注意：此处的 {@code index} 是该元素在自定义表单中的全局序号
     * （由 {@link io.github.JiangHu.jframe.form.window.CustomForm} 统一分配），
     * 而非同类元素的序号。
     *
     * @param response 原生响应
     * @param index    元素全局索引
     * @return 读取到的值
     */
    protected abstract T read(FormResponseCustom response, int index);

    /** 最近一次读取到的值（{@link #read} 后缓存）。 */
    private T value;

    /**
     * 读取并缓存值。由 {@link io.github.JiangHu.jframe.form.window.CustomForm} 在构建结果时调用。
     *
     * @param response 原生响应
     * @param index    元素全局索引
     */
    public void readAndCache(FormResponseCustom response, int index) {
        this.value = read(response, index);
    }

    /** 获取最近一次读取到的值。 */
    public T value() {
        return value;
    }
}
