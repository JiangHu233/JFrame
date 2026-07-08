package io.github.JiangHu.jframe.form.window;

import cn.nukkit.Player;
import cn.nukkit.form.element.Element;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.element.FormElement;
import io.github.JiangHu.jframe.form.response.FormResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义表单（输入元素集合）。
 * <p>
 * 对应 Nukkit {@link FormWindowCustom}，用于收集玩家输入。
 * 内部维护一组 {@link FormElement}，每个元素自带类型化读取逻辑，
 * 调用方无需关心 Nukkit 各类 Response 的读取方法差异。
 * <p>
 * 提交后，结果会以「元素标签 -> 值」的形式封装进 {@link FormResult}，
 * 可通过 {@link FormResult#get(String)} 按标签取值，或直接访问元素对象的 {@code value()}。
 *
 * <pre>{@code
 * InputElement nameEl = new InputElement("昵称");
 * DropdownElement modeEl = new DropdownElement("模式", "生存", "创造");
 *
 * new CustomForm("创建角色")
 *     .element(nameEl)
 *     .element(modeEl);
 *
 * // 提交后：
 * String name = nameEl.value();        // 直接类型安全取值
 * String mode = result.get("模式");    // 或按标签取值
 * }</pre>
 *
 * @see FormElement
 */
public class CustomForm extends JForm {

    private final List<FormElement<?>> elements = new ArrayList<>();
    private String submitText;
    private FormIcon icon;

    public CustomForm(String title) {
        super(title);
    }

    /**
     * 追加一个输入元素（链式）。
     *
     * @param element 元素对象
     * @return 当前表单
     */
    public CustomForm element(FormElement<?> element) {
        elements.add(element);
        return this;
    }

    /**
     * 设置提交按钮文本（链式）。
     *
     * @param submitText 提交按钮文本
     * @return 当前表单
     */
    public CustomForm submitText(String submitText) {
        this.submitText = submitText;
        return this;
    }

    /**
     * 设置表单图标（链式）。
     *
     * @param icon 图标
     * @return 当前表单
     */
    public CustomForm icon(FormIcon icon) {
        this.icon = icon;
        return this;
    }

    /** 全部元素（不可变视图）。 */
    public List<FormElement<?>> elements() {
        return List.copyOf(elements);
    }

    @Override
    public Type type() {
        return Type.CUSTOM;
    }

    @Override
    protected FormWindowCustom buildWindow() {
        List<Element> nukkitElements = new ArrayList<>();
        for (FormElement<?> e : elements) {
            nukkitElements.add(e.toNukkit());
        }
        FormWindowCustom win = new FormWindowCustom(title, nukkitElements);
        if (submitText != null) {
            win.setSubmitButtonText(submitText);
        }
        if (icon != null) {
            win.setIcon(icon.toNukkit());
        }
        return win;
    }

    @Override
    public FormResult buildResult(Player player) {
        if (window == null) {
            return FormResult.closed(player, Type.CUSTOM);
        }
        FormResponseCustom resp = (FormResponseCustom) window.getResponse();

        // 逐个元素按全局索引读取并缓存，同时构建「标签 -> 值」映射
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            FormElement<?> el = elements.get(i);
            el.readAndCache(resp, i);
            values.put(el.label(), el.value());
        }
        return FormResult.custom(player, values);
    }

    @Override
    public void dispatch(FormView view, FormResult result) {
        // 自定义表单无按钮级回调，结果统一交由 FormView.onResult 处理
    }
}
