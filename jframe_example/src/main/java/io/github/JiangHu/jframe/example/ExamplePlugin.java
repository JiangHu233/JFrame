package io.github.JiangHu.jframe.example;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.plugin.service.RegisteredServiceProvider;
import com.nukkitx.fakeinventories.inventory.ChestFakeInventory;
import com.nukkitx.fakeinventories.inventory.FakeInventories;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.example.inventory.InventoryTestView;
import io.github.JiangHu.jframe.example.inventory.ShopInventoryView;
import io.github.JiangHu.jframe.example.view.MainMenuView;
import io.github.JiangHu.jframe.example.wrapper.ThunderSwordWrapper;
import io.github.JiangHu.jframe.form.ViewAPI;
import io.github.JiangHu.jframe.inventory.InventoryAPI;
import io.github.JiangHu.jframe.thread.ThreadAPI;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * JFrame 示例插件主类。
 * <p>
 * 本类演示如何「使用框架各模块」搭建一个完整的 Nukkit 插件：
 * <ol>
 *   <li>启动 Spring 容器，加载 event / form / thread / command / inventory 五个模块的服务 Bean</li>
 *   <li>绑定插件实例到所有 {@link PluginAware} Bean（含 EventEngine 与 CommandEngine）</li>
 *   <li>注册事件包装类</li>
 *   <li>注册声明式命令控制器（{@code /kit} 及其子命令）</li>
 *   <li>通过 {@code /jframe} 命令打开表单菜单</li>
 * </ol>
 *
 * <h3>独立使用 Spring（不依赖 JFrameMain）</h3>
 * 本示例直接继承 {@link PluginBase}，用 {@link ClassPathXmlApplicationContext}
 * 加载各模块的 {@code *-spring.xml}。这是一种「脱离 {@code JFrameMain}、独立装配」的用法，
 * 适合不想继承框架主类、只需使用部分模块的插件。
 *
 * <h3>Nukkit 类加载器隔离</h3>
 * Spring 解析 classpath 资源（{@code *-spring.xml}）时默认使用「线程上下文类加载器」。
 * Nukkit 主线程的上下文类加载器是服务器类加载器，看不到插件 jar 内部的资源，
 * 因此在 {@code onEnable} 期间切换为插件自身的类加载器，方法结束前还原。
 *
 * <h3>箱子界面对比演示</h3>
 * 通过 {@code /inv <jframe|fake>} 命令可对比两种打开虚拟箱子的方式：
 * <ul>
 *   <li>{@code /inv jframe} —— 使用本框架 {@code jframe_inventory}（声明式组件 + 假方块延迟打开）</li>
 *   <li>{@code /inv fake} —— 使用第三方 {@code com.nukkitx:fakeinventories} 库（原生假方块 API）</li>
 * </ul>
 * 两者底层都依赖「假方块 + 延迟打开」机制欺骗基岩版客户端，区别在于上层抽象：
 * jframe_inventory 提供声明式组件（Button/StorageBox/Filler）与布局管理，
 * 而 fakeinventories 需手动 setItem 与监听点击。
 */
public class ExamplePlugin extends PluginBase {

    /** Spring 应用上下文：持有 event / form / thread / command / inventory 五个模块的 Bean。 */
    private ClassPathXmlApplicationContext applicationContext;

    private EventAPI eventAPI;
    private ViewAPI viewAPI;
    private ThreadAPI threadAPI;
    private CommandAPI commandAPI;
    private InventoryAPI inventoryAPI;


    @Override
    public void onEnable() {
        // 【关键】切换线程上下文类加载器为插件自身，确保 Spring 能找到 jar 内的 *-spring.xml 资源
        ClassLoader serverClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            // 1. 启动 Spring 容器：加载 event / form / thread / command / inventory 五个模块的 XML
            //    各模块内部均为单向 DAG，无循环依赖
            applicationContext = new ClassPathXmlApplicationContext(
                    "event-spring.xml",
                    "form-spring.xml",
                    "thread-spring.xml",
                    "command-spring.xml",
                    "inventory-spring.xml"
            );

            // 2. 获取各模块的服务 Bean
            eventAPI = applicationContext.getBean("eventAPI", EventAPI.class);
            viewAPI = applicationContext.getBean("viewService", ViewAPI.class);
            threadAPI = applicationContext.getBean("threadAPI", ThreadAPI.class);
            commandAPI = applicationContext.getBean("commandAPI", CommandAPI.class);
            inventoryAPI = applicationContext.getBean("inventoryAPI", InventoryAPI.class);

            // 3. 绑定插件实例：扫描容器中所有 PluginAware Bean（即 EventEngine）并注入插件实例。
            //    EventEngine 需要插件实例才能向 Nukkit 注册事件监听。
            for (PluginAware aware : applicationContext.getBeansOfType(PluginAware.class).values()) {
                aware.bindPlugin(this);
            }

            // 4. 包扫描注册：自动发现 wrapper 包下所有 @Wrapper 类并注册
            //    （类似 Spring 的 @ComponentScan，无需逐个手动 register）
            eventAPI.scan("io.github.JiangHu.jframe.example.wrapper");

            // 5. 命令模块：扫描 command 包下所有 @CommandController 类并注册。
            //    CommandEngine 已在第 3 步作为 PluginAware Bean 被绑定插件实例，
            //    因此注册的根命令（如 /kit）会立即同步到 Nukkit，无需在 plugin.yml 声明。
            commandAPI.scan(getClass().getClassLoader(), "io.github.JiangHu.jframe.example.command");

            // 6. 创建一个名为 "example" 的串行任务队列，供表单中的异步任务使用
            threadAPI.createThreadTask("example");

            getLogger().info("JFrame 示例插件已启用：/jframe 菜单 | /shop 商店 | /inv <jframe|fake> 箱子对比 | /invtest 全特性测试");
        } finally {
            Thread.currentThread().setContextClassLoader(serverClassLoader);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName();

        if (name.equalsIgnoreCase("jframe")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家在游戏内执行");
                return true;
            }
            // 打开主菜单：ViewAPI 会自动为该玩家创建视图管理器
            viewAPI.sendForm(new MainMenuView(threadAPI, player), player);
            return true;
        }

        if (name.equalsIgnoreCase("sword")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家在游戏内执行");
                return true;
            }
            // 发放一把名为 aaa 的闪电钻石剑
            Item sword = ThunderSwordWrapper.createSword();
            player.getInventory().addItem(sword);
            player.sendMessage("§b⚡ 你获得了一把 §f[aaa] §b闪电钻石剑！拿在手上右键方块即可召唤闪电。");
            return true;
        }

        // /shop：快捷打开 jframe_inventory 商店界面（等价于 /inv jframe）
        if (name.equalsIgnoreCase("shop")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家在游戏内执行");
                return true;
            }
            inventoryAPI.openView(player, new ShopInventoryView());
            return true;
        }

        // /invtest：打开 jframe_inventory 全特性测试界面
        // 集中测试所有组件(Button/StorageBox/Filler/IconLabel/Panel)、布局(Manual/Border/Grid)、
        // 事件(Click/Store)、外观(六字段)、图层、可见性、动态更新、组件导航、生命周期回调。
        // 每个测试点点击后会在聊天栏输出反馈，方便对照预期行为查找 bug。
        if (name.equalsIgnoreCase("invtest")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家在游戏内执行");
                return true;
            }
            inventoryAPI.openView(player, new InventoryTestView());
            return true;
        }

        // /inv <jframe|fake>：对比两种虚拟箱子实现
        if (name.equalsIgnoreCase("inv")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c该命令只能由玩家在游戏内执行");
                return true;
            }
            String mode = args.length > 0 ? args[0].toLowerCase() : "";
            switch (mode) {
                case "jframe" -> {
                    // 方式一：jframe_inventory 框架
                    // 声明式组件（Button/StorageBox/Filler）+ 自动布局 + 假方块延迟打开
                    inventoryAPI.openView(player, new ShopInventoryView());
                    player.sendMessage("§a已用 §bjframe_inventory§a 框架打开箱子（声明式组件）");
                }
            }
            return true;
        }

        return false;
    }




    @Override
    public void onDisable() {
        // 先关闭线程池，再关闭 Spring 容器
        if (inventoryAPI != null) {
            inventoryAPI.closeAll();
        }
        if (threadAPI != null) {
            threadAPI.stopAll();
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
    }
}
