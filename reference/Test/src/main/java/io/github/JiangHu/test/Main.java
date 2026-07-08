package io.github.JiangHu.test;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.plugin.service.RegisteredServiceProvider;
import com.nukkitx.fakeinventories.inventory.ChestFakeInventory;
import com.nukkitx.fakeinventories.inventory.FakeInventories;
import lombok.Getter;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main extends PluginBase {

    @Getter
    private static Main instance;

    @Override
    public void onEnable() {
        instance = this;

        RegisteredServiceProvider<FakeInventories> provider =
                getServer().getServiceManager().getProvider(FakeInventories.class);

        if (provider == null || provider.getProvider() == null) {
            getLogger().error("FakeInventories not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        FakeInventories fakeInventories = provider.getProvider();

        getServer().getCommandMap().register("fakeinventory", new FakeInventoryCommand("fakeinventory"));
    }
}