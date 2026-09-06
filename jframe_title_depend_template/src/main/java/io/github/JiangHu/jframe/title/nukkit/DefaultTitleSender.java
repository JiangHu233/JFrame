package io.github.JiangHu.jframe.title.nukkit;

import cn.nukkit.Player;

import java.util.Objects;

/**
 * 默认标题发送器：直接委托 Nukkit {@link Player} 发包 API
 *
 * <p>实测 Nukkit MOT API 签名（与设计一致）：</p>
 * <ul>
 *   <li>{@code Player.sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut)}</li>
 *   <li>{@code Player.sendActionBar(String text)}</li>
 *   <li>{@code Player.clearTitle()}</li>
 * </ul>
 */
public final class DefaultTitleSender implements TitleSender {

    private final Player player;

    public DefaultTitleSender(Player player) {
        this.player = Objects.requireNonNull(player, "目标玩家不得为 null");
    }

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    @Override
    public void sendActionBar(String text) {
        player.sendActionBar(text);
    }

    @Override
    public void clearTitle() {
        player.clearTitle();
    }
}
