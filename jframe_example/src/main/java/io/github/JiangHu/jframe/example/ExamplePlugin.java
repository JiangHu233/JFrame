package io.github.JiangHu.jframe.example;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.event.EventService;
import io.github.JiangHu.jframe.example.view.MainMenuView;
import io.github.JiangHu.jframe.example.wrapper.ThunderSwordWrapper;
import io.github.JiangHu.jframe.form.ViewService;
import io.github.JiangHu.jframe.thread.ThreadService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * JFrame 示例插件主类。
 * <p>
 * 本类演示如何「使用框架各模块」搭建一个完整的 Nukkit 插件：
 * <ol>
 *   <li>启动 Spring 容器，加载 event / form / thread 三个模块的服务 Bean</li>
 *   <li>绑定插件实例到所有 {@link PluginAware} Bean</li>
 *   <li>注册事件包装类</li>
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
 */
public class ExamplePlugin extends PluginBase {

    /** Spring 应用上下文：持有 event / form / thread 三个模块的 Bean。 */
    private ClassPathXmlApplicationContext applicationContext;

    private EventService eventService;
    private ViewService viewService;
    private ThreadService threadService;

    @Override
    public void onEnable() {
        // 【关键】切换线程上下文类加载器为插件自身，确保 Spring 能找到 jar 内的 *-spring.xml 资源
        ClassLoader serverClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            // 1. 启动 Spring 容器：加载 event / form / thread 三个模块的 XML
            //    event 模块内部为单向 DAG（EventEngine → HandlerRegistry → EventService），无循环依赖
            applicationContext = new ClassPathXmlApplicationContext(
                    "event-spring.xml",
                    "form-spring.xml",
                    "thread-spring.xml"
            );

            // 2. 获取各模块的服务 Bean
            eventService = applicationContext.getBean("eventService", EventService.class);
            viewService = applicationContext.getBean("viewService", ViewService.class);
            threadService = applicationContext.getBean("threadService", ThreadService.class);

            // 3. 绑定插件实例：扫描容器中所有 PluginAware Bean（即 EventEngine）并注入插件实例。
            //    EventEngine 需要插件实例才能向 Nukkit 注册事件监听。
            for (PluginAware aware : applicationContext.getBeansOfType(PluginAware.class).values()) {
                aware.bindPlugin(this);
            }

            // 4. 包扫描注册：自动发现 wrapper 包下所有 @Wrapper 类并注册
            //    （类似 Spring 的 @ComponentScan，无需逐个手动 register）
            eventService.scan("io.github.JiangHu.jframe.example.wrapper");

            // 5. 创建一个名为 "example" 的串行任务队列，供表单中的异步任务使用
            threadService.createThreadTask("example");

            getLogger().info("JFrame 示例插件已启用，在游戏内输入 /jframe 打开菜单");
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
            // 打开主菜单：ViewService 会自动为该玩家创建视图管理器
            viewService.sendForm(new MainMenuView(threadService, player), player);
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
        return false;
    }

    @Override
    public void onDisable() {
        // 先关闭线程池，再关闭 Spring 容器
        if (threadService != null) {
            threadService.stopAll();
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
    }
}
