package example;

import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;

public class ExampleItem {
    public record Identifier(Item item) {
    }

    @EventRoute(filter = "isExampleItem")
    @KeyExtractor
    public static Identifier extractItem(PlayerInteractEvent event) {
        return new Identifier(event.getItem());
    }
    public static boolean isExampleItem(PlayerInteractEvent event) {
        return event.getItem().getName().equals("Example Item");
    }

    Identifier item;

    public ExampleItem(Identifier item) {
        this.item = item;
    }


    @EventRoute(filter = "isExampleItem")
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        event.getPlayer().sendMessage("You interacted with an example item!");
    }
}
