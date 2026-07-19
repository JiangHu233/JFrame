package io.github.JiangHu.jframe.form.response;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.window.Button;
import io.github.JiangHu.jframe.form.window.JForm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的表单提交结果。
 * <p>
 * 无论底层是简单表单、自定义表单还是模态框，玩家提交后框架都会构造一个 {@link FormResult}
 * 交给 {@link io.github.JiangHu.jframe.form.FormView#onResult(FormResult)} 处理。
 * 这使得视图无需关心表单类型差异，可用统一接口读取结果。
 * <p>
 * 各字段含义随 {@link #type()} 而异：
 * <ul>
 *   <li>{@link JForm.Type#SIMPLE} —— 使用 {@link #clickedButton()} / {@link #clickedIndex()}</li>
 *   <li>{@link JForm.Type#MODAL} —— 使用 {@link #clickedButton1()}（true 表示点了第一个按钮）</li>
 *   <li>{@link JForm.Type#CUSTOM} —— 使用 {@link #get(String)} 按元素标签取值，或 {@link #values()} 取全部</li>
 * </ul>
 * 若玩家直接关闭窗口（点 X），则 {@link #wasClosed()} 为 {@code true}，其余字段无意义。
 *
 * @see io.github.JiangHu.jframe.form.FormView#onResult(FormResult)
 */
public final class FormResult {

    private final Player player;
    private final boolean closed;
    private final JForm.Type type;

    // ---- SIMPLE ----
    private final Button clickedButton;
    private final int clickedIndex;

    // ---- MODAL ----
    private final boolean modalButton1;

    // ---- CUSTOM ----
    private final Map<String, Object> values;

    private FormResult(Player player, boolean closed, JForm.Type type,
                       Button clickedButton, int clickedIndex,
                       boolean modalButton1,
                       Map<String, Object> values) {
        this.player = player;
        this.closed = closed;
        this.type = type;
        this.clickedButton = clickedButton;
        this.clickedIndex = clickedIndex;
        this.modalButton1 = modalButton1;
        this.values = values == null ? Collections.emptyMap() : values;
    }

    /** 构造「玩家关闭窗口」的结果。 */
    public static FormResult closed(Player player, JForm.Type type) {
        return new FormResult(player, true, type, null, -1, false, null);
    }

    /** 构造简单表单的点击结果。 */
    public static FormResult simple(Player player, Button button, int index) {
        return new FormResult(player, false, JForm.Type.SIMPLE, button, index, false, null);
    }

    /** 构造模态框的点击结果（button1 为 true 表示点击了第一个按钮）。 */
    public static FormResult modal(Player player, boolean button1) {
        return new FormResult(player, false, JForm.Type.MODAL, null, button1 ? 0 : 1, button1, null);
    }

    /** 构造自定义表单的提交结果（values 为「元素标签 -> 值」映射）。 */
    public static FormResult custom(Player player, Map<String, Object> values) {
        return new FormResult(player, false, JForm.Type.CUSTOM, null, -1, false,
                Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** 触发本次提交的玩家。 */
    public Player player() {
        return player;
    }

    /** 玩家是否直接关闭了窗口（未提交）。 */
    public boolean wasClosed() {
        return closed;
    }

    /** 产生本结果的表单类型。 */
    public JForm.Type type() {
        return type;
    }

    // -------------------- SIMPLE --------------------

    /** 简单表单中被点击的按钮对象，非简单表单或已关闭时为 {@code null}。 */
    public Button clickedButton() {
        return clickedButton;
    }

    /** 简单表单中被点击按钮的索引，非简单表单或已关闭时为 {@code -1}。 */
    public int clickedIndex() {
        return clickedIndex;
    }

    // -------------------- MODAL --------------------

    /**
     * 模态框点击结果。
     *
     * @return {@code true} 表示点击了第一个按钮（通常为「确认」），{@code false} 表示第二个
     */
    public boolean clickedButton1() {
        return modalButton1;
    }

    /** 模态框点击结果（语义同 {@link #clickedButton1()}，取反）。 */
    public boolean clickedButton2() {
        return !modalButton1;
    }

    // -------------------- CUSTOM --------------------

    /**
     * 按元素标签读取自定义表单的提交值。
     *
     * @param label 元素标签（添加元素时传入的 label）
     * @param <T>   值类型，由调用方按元素类型断言
     * @return 提交值，不存在时为 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String label) {
        return (T) values.get(label);
    }

    /**
     * 按元素标签读取自定义表单的提交值，不存在时返回默认值。
     *
     * @param label   元素标签
     * @param defVal  默认值
     * @param <T>     值类型
     * @return 提交值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String label, T defVal) {
        Object v = values.get(label);
        return v == null ? defVal : (T) v;
    }

    /** 自定义表单全部提交值（「元素标签 -> 值」的不可变映射）。 */
    public Map<String, Object> values() {
        return values;
    }
}
