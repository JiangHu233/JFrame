package io.github.JiangHu.test;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import com.nukkitx.fakeinventories.inventory.ChestFakeInventory;
import com.nukkitx.fakeinventories.inventory.FakeInventory;

public class FakeInventoryCommand extends Command {
    public FakeInventoryCommand(String name) {
        super(name);
    }

    public void openMenu(Player player) {
        ChestFakeInventory menu = new ChestFakeInventory(null, "My Menu");

        // Set menu items
        menu.setItem(0, Item.get(Item.DIAMOND).setCustomName("Option 1"));
        menu.setItem(1, Item.get(Item.EMERALD).setCustomName("Option 2"));
        menu.setItem(2, Item.get(Item.GOLD_INGOT).setCustomName("Option 3"));

        // Handle clicks
        menu.addListener(event -> {
            event.setCancelled(); // Prevent taking items

            Player p = event.getPlayer();
            int slot = event.getAction().getSlot();

            switch (slot) {
                case 0:
                    p.sendMessage("Selected Option 1");
                    break;
                case 1:
                    p.sendMessage("Selected Option 2");
                    break;
                case 2:
                    p.sendMessage("Selected Option 3");
                    break;
            }

            p.removeWindow(event.getInventory()); // Close menu
        });

        // 延迟 10 tick 后再打开箱子界面，避免界面打不开的问题
        Main.getInstance().getServer().getScheduler().scheduleDelayedTask(Main.getInstance(), () -> player.addWindow(menu), 10);
    }

    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be run by a player.");
            return false;
        }
        openMenu((Player) sender);
        return true;
    }
}
