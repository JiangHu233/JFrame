package io.github.JiangHu.jframe.form.element;

import cn.nukkit.form.element.Element;
import cn.nukkit.form.response.FormResponseCustom;
import lombok.Setter;

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
 * <h2>key 与 label 的职责分离</h2>
 * 每个元素携带两个字符串属性：
 * <ul>
 *   <li><strong>{@code key}</strong> —— 元素<strong>唯一标识</strong>，构造时确定、
 *       {@code final} 不可变。用作 {@link io.github.JiangHu.jframe.form.response.FormResult}
 *       的取值键（{@code result.get(key)}）。</li>
 *   <li><strong>{@code label}</strong> —— 元素<strong>显示文本</strong>（输入框左侧提示），
 *       可在运行期通过 {@link #setLabel(String)} 动态修改，下次 {@link #toNukkit()} 转换时生效。</li>
 * </ul>
 * 二者分离后，即使运行期修改了 {@code label}（如切换语言、刷新提示文案），
 * 取值键 {@code key} 仍保持稳定，避免「改了显示文案就取不到值」的问题。
 * <p>
 * 为向后兼容，未显式指定 {@code key} 时默认与 {@code label} 初始值相同，
 * 老代码 {@code result.get("昵称")} 仍可正常工作。
 *
 * @param <T> 元素的值类型
 */
public abstract class FormElement<T> {

    /**
     * 元素唯一标识（用作 {@link io.github.JiangHu.jframe.form.response.FormResult} 取值键）。
     * <p>
     * 构造时确定，<strong>不可变</strong>。这样即使 {@link #label 显示文本} 在运行期被修改，
     * 取值键也保持稳定。未显式指定时默认与 {@code label} 初始值相同。
     *
     * @see #key()
     */
    private final String key;

    /**
     * 元素标签（显示在输入框左侧的提示文字）。
     * <p>
     * 可在运行期通过 {@link #setLabel(String)} 动态修改，
     * 下次 {@link #toNukkit()} 转换时生效。
     * <p>
     * 注意：{@code label} 仅用于<strong>显示</strong>，不再作为结果取值键；取值请使用 {@link #key()}。
     */
    @Setter
    protected String label;

    /**
     * 创建元素，{@code key} 默认与 {@code label} 相同（向后兼容）。
     *
     * @param label 元素标签（同时作为默认 key）
     */
    protected FormElement(String label) {
        this(label, label);
    }

    /**
     * 创建元素，显式分离唯一标识与显示标签。
     *
     * @param key   元素唯一标识（结果取值键，不可变）
     * @param label 元素显示标签（可变）
     */
    protected FormElement(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 元素唯一标识（结果取值键，不可变）。 */
    public String key() {
        return key;
    }

    /** 元素标签（显示用，可变）。 */
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

    /**
     * 将最近一次读取并缓存的值（{@link #value()}）应用为下次显示的默认值。
     * <p>
     * 典型用于「回显上次输入」：玩家提交后在 {@code onResult} 中调用本方法，
     * 再次显示该元素时，输入框 / 选项会预填上次的值。
     * <p>
     * 本方法等价于 {@code applyValue(value())}。若尚未提交过（{@code value} 为 {@code null}），不做任何操作。
     * <p>
     * <strong>注意：</strong>本方法只修改元素自身的默认值字段，要让回显真正生效，
     * 还需保证下次显示时复用的是<strong>同一个元素对象</strong>（而非每次 {@code onBuild} 都新建）。
     *
     * @return 当前元素（链式）
     * @see #applyValue(Object)
     */
    public FormElement<T> applyLastValue() {
        return applyValue(this.value);
    }

    /**
     * 将指定值应用为下次显示的默认值（从外部回填）。
     * <p>
     * 与 {@link #applyLastValue()} 的区别：本方法从外部传入值，不依赖是否有过提交。
     * 可用于「预填初始值」「从存档恢复」等场景。{@code value} 为 {@code null} 时不做任何操作。
     *
     * @param value 要应用的值
     * @return 当前元素（链式）
     * @see #applyLastValue()
     */
    public FormElement<T> applyValue(T value) {
        if (value != null) {
            doApplyValue(value);
        }
        return this;
    }

    /**
     * 将值写入默认值字段的具体逻辑（子类实现）。
     * <p>
     * 不同子类的默认值字段类型各异（如输入框是文本、下拉框是索引），
     * 子类在此完成「值 → 默认值」的类型转换。调用方传入的 {@code value} 保证非 {@code null}。
     *
     * @param value 要应用的值（保证非 {@code null}）
     */
    protected abstract void doApplyValue(T value);
}
