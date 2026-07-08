package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;
import io.github.JiangHu.jframe.thread.ThreadAPI;

/**
 * 表单特性演示总菜单。
 * <p>
 * 本视图是 jframe_form 模块全部特性的入口，每个按钮进入一个独立的演示视图，
 * 分别覆盖：三种表单类型、六种输入元素、按钮图标、栈式导航、数据总线、
 * 一次性数据传递、构建策略等。
 * <p>
 * 所有演示视图都以「栈式导航」挂在主菜单之下，点击「返回」或关闭窗口（X）
 * 均会回到上一级，不会丢失导航路径。
 */
public class FormDemoView extends FormView {

    private final ThreadAPI threadAPI;
    private final Player player;

    public FormDemoView(ThreadAPI threadAPI, Player player) {
        this.threadAPI = threadAPI;
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7以下按钮逐一演示 jframe_form 的各项特性。\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§e每个演示视图均可通过 §f返回 §e或关闭窗口回到本菜单";

        return new SimpleForm("§9§l表单特性演示")
                .content(content)
                // 自定义表单：演示 CustomForm + 全部6种输入元素 + onResult
                .button("§a📝 自定义表单（CustomForm）", ctx -> addStack(new CustomFormDemoView(player)))
                // 模态框：演示 ModalForm + onConfirm/onCancel
                .button("§b❓ 模态框（ModalForm）", ctx -> addStack(new ModalFormDemoView(player)))
                // 按钮图标：演示 FormIcon.path / FormIcon.url
                .button("§d🖼️ 按钮图标（FormIcon）", ctx -> addStack(new IconDemoView(player)))
                // 栈式导航：演示 addStack/goBack/replaceThis/close/restartWith
                .button("§6🧭 栈式导航", ctx -> addStack(new NavigationDemoView(threadAPI, player)))
                // 数据总线：演示 putData/getData/subscribe/notifyRefresh
                .button("§e🔄 数据总线（ViewDataBus）", ctx -> addStack(new DataBusDemoView(player)))
                // 一次性数据传递：演示 addStack(view,data)/onData/passDataTo
                .button("§c📦 一次性数据传递（onData）", ctx -> addStack(new OnDataDemoView(player)))
                // 构建策略：演示 ON_DEMAND/markDirty/refresh
                .button("§3⚙️ 构建策略（BuildStrategy）", ctx -> addStack(new BuildStrategyDemoView(player)))
                // 返回主菜单
                .button("§7↩ 返回主菜单", ctx -> goBack());
    }

    /**
     * 关闭窗口（X）时返回上一级（主菜单），而非默认的「窗口弹回」。
     */
    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
