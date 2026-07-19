package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.element.DropdownElement;
import io.github.JiangHu.jframe.form.element.InputElement;
import io.github.JiangHu.jframe.form.element.LabelElement;
import io.github.JiangHu.jframe.form.element.SliderElement;
import io.github.JiangHu.jframe.form.element.StepSliderElement;
import io.github.JiangHu.jframe.form.element.ToggleElement;
import io.github.JiangHu.jframe.form.response.FormResult;
import io.github.JiangHu.jframe.form.window.CustomForm;
import io.github.JiangHu.jframe.form.window.JForm;

/**
 * 自定义表单（CustomForm）演示。
 * <p>
 * 在一个表单中集齐全部六种输入元素，演示如何收集玩家输入：
 * <ul>
 *   <li>{@link LabelElement} —— 纯文本标签（不接受输入，仅展示说明）</li>
 *   <li>{@link InputElement} —— 文本输入（值类型 String）</li>
 *   <li>{@link DropdownElement} —— 下拉选择（值类型 String）</li>
 *   <li>{@link SliderElement} —— 滑块（值类型 Float）</li>
 *   <li>{@link StepSliderElement} —— 步进滑块（值类型 String）</li>
 *   <li>{@link ToggleElement} —— 开关（值类型 Boolean）</li>
 * </ul>
 * 提交后通过 {@link #onResult(FormResult)} 统一读取结果，演示两种取值方式：
 * <ol>
 *   <li>元素对象的 {@code value()} —— 类型安全，推荐</li>
 *   <li>{@link FormResult#get(String)} —— 按元素标签取值</li>
 * </ol>
 */
public class CustomFormDemoView extends FormView {

    private final Player player;

    // 将元素声明为字段，提交后可直接调用其 value() 类型安全地读取
    private InputElement nameEl;
    private DropdownElement jobEl;
    private SliderElement levelEl;
    private StepSliderElement difficultyEl;
    private ToggleElement pvpEl;

    public CustomFormDemoView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        nameEl = new InputElement("§e昵称", "请输入你的昵称", player.getName());
        jobEl = new DropdownElement("§e职业", "战士", "法师", "弓箭手", "牧师");
        levelEl = new SliderElement("§e等级", 1, 100, 1, 1);
        difficultyEl = new StepSliderElement("§e难度", "简单", "普通", "困难", "地狱");
        pvpEl = new ToggleElement("§e开启 PvP", false);

        return new CustomForm("§a§l创建角色")
                // LabelElement：纯文本，用于分隔区块 / 提供说明
                .element(new LabelElement("§7━━━ 请填写以下信息 ━━━"))
                .element(nameEl)
                .element(jobEl)
                .element(levelEl)
                .element(difficultyEl)
                .element(pvpEl)
                .element(new LabelElement("§7━━━━━━━━━━━━━━━━"))
                // submitText：自定义提交按钮文本
                .submitText("§a确认创建");
    }

    /**
     * 统一结果处理：玩家点击「确认创建」后读取全部输入值。
     * <p>
     * 同时演示两种取值方式：元素 {@code value()} 与 {@link FormResult#get(String)}。
     */
    @Override
    protected void onResult(FormResult result) {
        // 方式一：元素对象 value() —— 类型安全，无需强转
        String name = nameEl.value();
        String job = jobEl.value();
        float level = levelEl.value();
        String difficulty = difficultyEl.value();
        boolean pvp = pvpEl.value();

        // 方式二：FormResult.get(label) —— 按标签取值（需自行断言类型）
        // String jobByLabel = result.get("§e职业");

        player.sendMessage("§a[角色创建] §f昵称：§e" + name
                + " §f| 职业：§b" + job
                + " §f| 等级：§c" + (int) level
                + " §f| 难度：§d" + difficulty
                + " §f| PvP：§6" + (pvp ? "开" : "关"));

        // 提交完毕，返回演示总菜单
        goBack();
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
