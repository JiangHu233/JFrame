package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

/**
 * 一次性数据传递（onData）演示。
 * <p>
 * 演示两种向子界面传递数据的方式，数据通过子视图的 {@link #onData(Object)} 接收：
 * <ul>
 *   <li>{@link #addStack(FormView, Object)} —— 进入子界面的同时传递一次性数据</li>
 *   <li>{@link #passDataTo(FormView, Object)} —— 向一个视图实例传递数据（触发其 onData），
 *       可在压栈前预先塞入</li>
 * </ul>
 * 与 {@link DataBusDemoView 数据总线}的区别：{@code onData} 是一次性对象传递，
 * 适合「带参打开子界面」；数据总线是基于键的持久化共享状态。
 *
 * @see OnDataDetailView
 * @see DataBusDemoView
 */
public class OnDataDemoView extends FormView {

    private final Player player;

    public OnDataDemoView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        String content = "§7一次性数据传递（onData）演示\n"
                + "§7━━━━━━━━━━━━━━━━\n"
                + "§f下方两个按钮用不同方式向详情界面传参，\n"
                + "§f详情界面会通过 §eonData §f接收并显示。";

        return new SimpleForm("§c§l一次性数据传递")
                .content(content)
                // 方式一：addStack(view, data) —— 进入子界面的同时传参
                .button("§a📦 带参打开详情（addStack + data）", ctx ->
                        addStack(new OnDataDetailView(player), "§e来自父界面的问候 #1"))
                // 方式二：passDataTo 预先塞入数据，再 addStack 显示
                .button("§b📨 用 passDataTo 传参后打开", ctx -> {
                    OnDataDetailView detail = new OnDataDetailView(player);
                    passDataTo(detail, "§d通过 passDataTo 传递的问候");
                    addStack(detail);
                })
                .button("§7↩ 返回", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
