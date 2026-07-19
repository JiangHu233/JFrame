package io.github.JiangHu.jframe.form.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FormElement#applyValue(Object)} 与 {@link FormElement#applyLastValue()} 的单元测试。
 * <p>
 * 各元素的默认值字段为 private 且仅暴露 setter，本测试通过反射读取字段值来断言回填结果，
 * 不依赖 Nukkit 原生元素的 getter API，保证测试稳定。
 */
@DisplayName("FormElement 回显回填（applyValue / applyLastValue）")
class FormElementApplyValueTest {

    /** 反射读取字段值（沿继承链向上查找，兼容基类 private 字段）。 */
    private static Object field(Object obj, String name) throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 反射写入字段值（沿继承链向上查找）。 */
    private static void setField(Object obj, String name, Object value) throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // -------------------- InputElement --------------------

    @Test
    @DisplayName("InputElement：applyValue 写入 defaultText")
    void inputApplyValue() throws Exception {
        InputElement el = new InputElement("昵称", "提示", "旧值");
        el.applyValue("新值");
        assertEquals("新值", field(el, "defaultText"));
    }

    @Test
    @DisplayName("InputElement：applyValue(null) 不修改原值")
    void inputApplyValueNull() throws Exception {
        InputElement el = new InputElement("昵称", "提示", "旧值");
        el.applyValue(null);
        assertEquals("旧值", field(el, "defaultText"));
    }

    @Test
    @DisplayName("applyValue 链式返回自身")
    void applyValueReturnsSelf() {
        InputElement el = new InputElement("昵称");
        assertSame(el, el.applyValue("x"));
    }

    // -------------------- ToggleElement --------------------

    @Test
    @DisplayName("ToggleElement：applyValue 写入 defaultValue")
    void toggleApplyValue() throws Exception {
        ToggleElement el = new ToggleElement("开关", false);
        el.applyValue(true);
        assertTrue((boolean) field(el, "defaultValue"));
    }

    // -------------------- SliderElement --------------------

    @Test
    @DisplayName("SliderElement：applyValue 写入 defaultValue")
    void sliderApplyValue() throws Exception {
        SliderElement el = new SliderElement("音量", 0, 100, 1, 10);
        el.applyValue(75f);
        assertEquals(75f, (float) field(el, "defaultValue"), 0.001f);
    }

    // -------------------- DropdownElement --------------------

    @Test
    @DisplayName("DropdownElement：applyValue 文本→索引转换")
    void dropdownApplyValue() throws Exception {
        DropdownElement el = new DropdownElement("难度", "简单", "普通", "困难");
        // options = [简单, 普通, 困难]，"困难" 索引为 2
        el.applyValue("困难");
        assertEquals(2, field(el, "defaultIndex"));
    }

    @Test
    @DisplayName("DropdownElement：applyValue 不存在的选项保持原索引")
    void dropdownApplyValueNotFound() throws Exception {
        DropdownElement el = new DropdownElement("难度", "简单", "普通", "困难");
        int before = (int) field(el, "defaultIndex");
        el.applyValue("不存在的选项");
        assertEquals(before, field(el, "defaultIndex"));
    }

    // -------------------- StepSliderElement --------------------

    @Test
    @DisplayName("StepSliderElement：applyValue 文本→索引转换")
    void stepSliderApplyValue() throws Exception {
        StepSliderElement el = new StepSliderElement("画质", "低", "中", "高");
        // steps = [低, 中, 高]，"高" 索引为 2
        el.applyValue("高");
        assertEquals(2, field(el, "defaultIndex"));
    }

    // -------------------- LabelElement --------------------

    @Test
    @DisplayName("LabelElement：applyValue 空实现不抛异常")
    void labelApplyValueNoOp() {
        LabelElement el = new LabelElement("说明文字");
        assertDoesNotThrow(() -> el.applyValue("任意值"));
    }

    // -------------------- applyLastValue --------------------

    @Test
    @DisplayName("applyLastValue：用缓存的 value 回填默认值")
    void applyLastValue() throws Exception {
        InputElement el = new InputElement("昵称", "提示", "");
        // 模拟 readAndCache 后 value 被缓存
        setField(el, "value", "上次输入");
        el.applyLastValue();
        assertEquals("上次输入", field(el, "defaultText"));
    }

    @Test
    @DisplayName("applyLastValue：value 为 null 时不操作")
    void applyLastValueNull() throws Exception {
        InputElement el = new InputElement("昵称", "提示", "原默认");
        el.applyLastValue(); // value 仍为 null
        assertEquals("原默认", field(el, "defaultText"));
    }

    @Test
    @DisplayName("applyLastValue 链式返回自身")
    void applyLastValueReturnsSelf() {
        InputElement el = new InputElement("昵称");
        assertSame(el, el.applyLastValue());
    }
}
