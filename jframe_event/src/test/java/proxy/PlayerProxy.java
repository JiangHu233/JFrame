package proxy;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerEvent;

public class PlayerProxy {

    Player player;
    public PlayerProxy(Player player) {
        this.player = player;
    }


    public void onEvent(PlayerEvent event) {

    }
}
