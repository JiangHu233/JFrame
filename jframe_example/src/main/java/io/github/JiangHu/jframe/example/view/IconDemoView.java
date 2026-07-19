package io.github.JiangHu.jframe.example.view;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.window.Button;
import io.github.JiangHu.jframe.form.window.FormIcon;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;

/**
 * 按钮图标（FormIcon）演示。
 * <p>
 * {@link FormIcon} 为按钮提供图标，支持两种来源：
 * <ul>
 *   <li>{@link FormIcon#path(String)} —— 客户端内置纹理路径（如 {@code textures/items/apple}），
 *       无需联网，加载快，适合常用物品 / 方块图标</li>
 *   <li>{@link FormIcon#url(String)} —— 网络图片地址，可展示任意图片，但需玩家客户端能访问该 URL</li>
 * </ul>
 * 图标通过 {@link Button#icon(FormIcon)} 或 {@link Button(String, FormIcon)} 构造设置。
 * <p>
 * 除简单表单按钮外，{@link io.github.JiangHu.jframe.form.window.CustomForm 自定义表单}
 * 也支持通过 {@code icon(FormIcon)} 设置表单整体图标。
 */
public class IconDemoView extends FormView {

    private final Player player;

    public IconDemoView(Player player) {
        this.player = player;
    }

    @Override
    protected JForm onBuild() {
        return new SimpleForm("§d§l按钮图标演示")
                .content("§7下方按钮展示了两种图标来源。\n"
                        + "§e上方 §f为客户端内置纹理（path），§e下方 §f为网络图片（url）。")
                // path 图标：客户端内置纹理，加载快、无需联网
                .button(new Button("§a苹果（path）", FormIcon.path("textures/items/apple"))
                        .onClick(ctx -> player.sendMessage("§a你点击了 §fpath §a图标按钮：苹果")))
                .button(new Button("§b钻石（path）", FormIcon.path("textures/items/diamond"))
                        .onClick(ctx -> player.sendMessage("§b你点击了 §fpath §b图标按钮：钻石")))
                .button(new Button("§6金苹果（path）", FormIcon.path("textures/items/apple_golden"))
                        .onClick(ctx -> player.sendMessage("§6你点击了 §fpath §6图标按钮：金苹果")))
                // url 图标：网络图片，可展示任意图片
                .button(new Button("§e网络图片（url）", FormIcon.url("https://www.minecraft.net/etc.clientlibs/minecraftnet/clientlibs/main/resources/favicon-96x96.png"))
                        .onClick(ctx -> player.sendMessage("§e你点击了 §furl §e图标按钮（需联网才能看到图标）")))
                .button("§7↩ 返回", ctx -> goBack());
    }

    @Override
    protected void onCloseAttempt() {
        goBack();
    }
}
