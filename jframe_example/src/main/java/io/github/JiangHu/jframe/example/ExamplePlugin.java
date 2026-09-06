package io.github.JiangHu.jframe.example;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.ai.AiAPI;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.example.command.AiController;
import io.github.JiangHu.jframe.example.entity.TestNpcEntity;
import io.github.JiangHu.jframe.example.inventory.InventoryTestView;
import io.github.JiangHu.jframe.example.inventory.ShopInventoryView;
import io.github.JiangHu.jframe.example.wrapper.ThunderSwordWrapper;
import io.github.JiangHu.jframe.form.ViewAPI;
import io.github.JiangHu.jframe.inventory.ui.InventoryAPI;
import io.github.JiangHu.jframe.main.JFrameMain;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;

/**
 * JFrame 示例插件主类。
 * <p>
 * 本类演示如何「作为业务插件依赖前置 JFrame」搭建一个完整的 Nukkit 插件：
 * <ol>
 *   <li>获取前置插件 {@link JFrameMain} 的单例</li>
 *   <li>直接复用 JFrame 已创建好的 Spring 容器中的各模块 API（EventAPI / ViewAPI / CommandAPI / AiAPI 等）</li>
 *   <li>用本插件类加载器扫描自身 jar 内的 {@code @Wrapper} / {@code @CommandController}，注册到框架共享容器</li>
 *   <li>通过 {@code /jframe} 命令打开表单菜单，{@code /ai} 命令测试 AI 功能</li>
 * </ol>
 *
 * <h3>为什么不自己创建 Spring 容器？</h3>
 * <p>
 * {@link JFrameMain} 在其 {@code onEnable} 时已用 {@code AnnotationConfigApplicationContext}
 * 创建好完整的 Spring 容器（基于 {@code MainSpringConfig}，非 XML），并完成了：
 * <ul>
 *   <li>加载全部模块 Bean（event / form / thread / command / inventory / ai / data）</li>
 *   <li>绑定 {@code PluginAware} Bean（EventEngine / CommandEngine 等已绑定 JFrame 插件实例）</li>
 *   <li>将各 API 注册到 Nukkit {@code ServiceManager}</li>
 * </ul>
 * 业务插件只需调用 {@link JFrameMain#getInstance()} 的门面方法（{@code getAiAPI()} 等）即可获取现成的 Bean，
 * 无需重复加载 {@code *-spring.xml}，也无需处理 Nukkit 多插件类加载器的资源加载问题。
 *
 * <h3>部署方式</h3>
 * <ul>
 *   <li>{@code plugin.yml} 声明 {@code depend: [JFrame]}</li>
 *   <li>{@code pom.xml} 中 {@code jframe_main} 为 {@code provided} 作用域（不重复打包框架类）</li>
 *   <li>服务器 {@code plugins/} 目录先放 {@code jframe_main.jar}，再放 {@code jframe_example.jar}</li>
 * </ul>
 *
 * <h3>箱子界面对比演示</h3>
 * 通过 {@code /inv <jframe|fake>} 命令可对比两种打开虚拟箱子的方式：
 * <ul>
 *   <li>{@code /inv jframe} —— 使用本框架 {@code jframe_inventory}（声明式组件 + 假方块延迟打开）</li>
 *   <li>{@code /inv fake} —— 使用第三方 {@code com.nukkitx:fakeinventories} 库（原生假方块 API）</li>
 * </ul>
 */
public class ExamplePlugin extends PluginBase {

    private EventAPI eventAPI;
    private ViewAPI viewAPI;
    private ThreadAPI threadAPI;
    private CommandAPI commandAPI;
    private InventoryAPI inventoryAPI;
    /** AI 服务（jframe_ai 模块门面） */
    private AiAPI aiAPI;


    @Override
    public void onEnable() {
        // 1. 获取前置插件 JFrame 的单例（其 onEnable 时已创建好 Spring 容器）
        Plugin jframePlugin = getServer().getPluginManager().getPlugin("JFrame");
        if (!(jframePlugin instanceof JFrameMain jframeMain)) {
            throw new IllegalStateException(
                    "前置插件 JFrame 未找到或类型不匹配，请确认已部署 jframe_main.jar 并在 plugin.yml 声明 depend: [JFrame]");
        }

        // 2. 直接复用 JFrame 已创建好的 Spring 容器中的各模块 API
        //    JFrameMain 在 onEnable 时已完成：创建容器、绑定 PluginAware、注册 ServiceManager
        eventAPI = jframeMain.getEventAPI();
        viewAPI = jframeMain.getViewAPI();
        threadAPI = jframeMain.getThreadAPI();
        commandAPI = jframeMain.getCommandAPI();
        inventoryAPI = jframeMain.getInventoryAPI();
        aiAPI = jframeMain.getAiAPI();

        // 3. 包扫描注册：用本插件类加载器扫描 example 包下的 @Wrapper 类
        //    （显式传入 getClass().getClassLoader()，因为要扫描的是 jframe_example.jar 内的类）
        eventAPI.scan(getClass().getClassLoader(), "io.github.JiangHu.jframe.example.wrapper");

        // 4. 命令模块：注入 AiAPI + 扫描 command 包下的 @CommandController 类
        //    CommandEngine 已由 JFrameMain 绑定 JFrame 插件实例，
        //    因此注册的根命令（如 /ai）会立即同步到 Nukkit，无需在 plugin.yml 声明。
        //    AiController 用静态字段持有 AiAPI（jframe_command 仅支持无参构造），需在 scan 前注入。
        // 注册 AI 测试用 NPC 实体类型（EntityHuman 子类，无怪物默认 AI）。
        // 必须在 /ai spawn 之前完成注册，否则 Entity.createEntity("TestNpc", ...) 无法识别；
        // TestNpcEntity.spawnAt 直接 new 构造不依赖注册，但注册后两种生成方式都可用。
        Entity.registerEntity(TestNpcEntity.NETWORK_NAME, TestNpcEntity.class);

        AiController.setAi(aiAPI);
        commandAPI.scan(getClass().getClassLoader(), "io.github.JiangHu.jframe.example.command");

        // 5. 创建一个名为 "example" 的串行任务队列，供表单中的异步任务使用
        threadAPI.createThreadTask("example");

        getLogger().info("JFrame 示例插件已启用：/jframe 菜单 | /shop 商店 | /inv <jframe|fake> 箱子对比 | /invtest 全特性测试 | /ai AI测试");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName();

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
        // 仅清理本插件创建的 AI 测试实体。
        // 注意：不关闭 Spring 容器 / 线程池 / 箱子视图 —— 这些由前置插件 JFrameMain 统一管理生命周期。
        AiController.clearAll();
    }
}
