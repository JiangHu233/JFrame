package io.github.JiangHu.jframe.main;

import cn.nukkit.Server;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.plugin.service.ServicePriority;
import io.github.JiangHu.jframe.ai.AiAPI;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.core.config.CoreSpringConfig;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.data.DataSaver;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.form.ViewAPI;
import io.github.JiangHu.jframe.inventory.ui.InventoryAPI;
import io.github.JiangHu.jframe.main.config.MainSpringConfig;
import io.github.JiangHu.jframe.main.utils.ConfigEnum;
import io.github.JiangHu.jframe.async.flow.ThreadFlow;
import io.github.JiangHu.jframe.async.release.ReleaseAPI;
import io.github.JiangHu.jframe.async.task.TaskAPI;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import io.github.JiangHu.jframe.scoreboard.ScoreboardAPI;
import io.github.JiangHu.jframe.title.TitleAPI;
import lombok.Getter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JFrame 框架主类。
 * <p>
 * 框架入口，负责加载核心模块。
 * <p>
 * 框架启动时，会自动加载核心模块（{@link CoreSpringConfig}），
 * 并注册其他模块的 SpringConfig。
 *
 * <h3>作为「前置插件」使用</h3>
 * 本类本身是一个 Nukkit 插件，可部署为「前置插件 / 库插件」。
 * 其他业务插件在自身 {@code plugin.yml} 中声明 {@code depend: [JFrame]} 后，
 * 即可共享本插件启动的 Spring 容器与各模块 API，无需各自打包框架代码。
 * 获取 API 有两种等价方式：
 * <ul>
 *   <li><strong>门面方法</strong> —— {@code JFrameMain.getInstance().getEventAPI()} 等</li>
 *   <li><strong>ServiceManager</strong> ——
 *       {@code getServer().getServiceManager().getProvider(EventAPI.class).getProvider()}
 *       （框架在 {@link #onEnable()} 时已自动注册全部模块服务）</li>
 * </ul>
 * 业务插件可调用 {@link EventAPI#scan(ClassLoader, String...)} /
 * {@link CommandAPI#scan(ClassLoader, String...)} 传入<b>自身类加载器</b>，
 * 将自己 jar 内的 {@code @Wrapper} / {@code @CommandController} 注册到框架共享容器中。
 *
 * @see ConfigEnum
 */
public class JFrameMain extends PluginBase {
    public static final String ON_LOAD = "JFrame loaded";

    @Getter
    protected static JFrameMain instance;

    @Getter
    protected AnnotationConfigApplicationContext applicationContext;
    @Getter
    protected Set<ConfigEnum> modules = new HashSet<ConfigEnum>(List.of(ConfigEnum.CORE));

    public static <T> T getBean(Class<T> beanType) {
        return instance.applicationContext.getBean(beanType);
    }

    public JFrameMain() {
        this.applicationContext = createApplicationContext();
    }

    /**
     * 创建并刷新 Spring 应用上下文。
     * <p>
     * <strong>类加载器修正（Nukkit 插件环境）：</strong>
     * Nukkit 主线程的上下文类加载器是「服务器类加载器」，它看不到插件 jar 内部的
     * classpath 资源（如各模块的 {@code *-spring.xml}）。而 Spring 解析 {@code classpath:}
     * 资源时优先使用 {@link Thread#getContextClassLoader()}，因此会抛出
     * {@code FileNotFoundException: class path resource [xxx-spring.xml] cannot be opened}。
     * <p>
     * 本方法在创建上下文期间临时将线程上下文类加载器切换为「插件类加载器」
     * （即本类所在 jar 的类加载器），使 Spring 能正确加载插件内的 classpath 资源；
     * 同时让上下文本身持有该类加载器，保证后续 Bean 类型解析与资源加载一致。
     * 创建完成后恢复原线程上下文类加载器。
     *
     * @return 已完成刷新的应用上下文
     */
    private AnnotationConfigApplicationContext createApplicationContext() {
        ClassLoader pluginLoader = getClass().getClassLoader();
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(pluginLoader);
        try {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            // 让上下文本身也使用插件类加载器，确保后续类型解析、资源加载一致
            context.setClassLoader(pluginLoader);
            context.register(MainSpringConfig.class);
            context.refresh();
            return context;
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    /**
     * 将当前插件实例绑定到容器中所有 {@link PluginAware} Bean。
     * <p>
     * 在以下时机自动调用：
     * <ul>
     *   <li>{@link #onEnable()}：插件启用时，为核心模块及已导入模块绑定一次</li>
     * </ul>
     * 单个 Bean 绑定异常会被捕获并记录，不影响其他 Bean。
     */
    protected void bindPlugin() {
        Map<String, PluginAware> awareBeans = applicationContext.getBeansOfType(PluginAware.class);
        for (PluginAware aware : awareBeans.values()) {
            try {
                aware.bindPlugin(this);
            } catch (Exception e) {
                getLogger().error("绑定 plugin 到 " + aware.getClass().getName() + " 失败", e);
            }
        }
    }

    // -------------------- 模块 API 门面 --------------------

    /**
     * 事件模块 API。
     *
     * @return 事件服务
     */
    public EventAPI getEventAPI() {
        return getApi(EventAPI.class);
    }

    /**
     * 表单模块 API。
     *
     * @return 表单服务
     */
    public ViewAPI getViewAPI() {
        return getApi(ViewAPI.class);
    }

    /**
     * 线程模块 API。
     *
     * @return 线程服务
     */
    public ThreadAPI getThreadAPI() {
        return getApi(ThreadAPI.class);
    }

    /**
     * 可挂起任务模块 API。
     * <p>
     * 提供基于 Nukkit 主线程调度的「按需驱动、自动挂起、可恢复」任务管理。
     *
     * @return 可挂起任务服务
     * @see TaskAPI
     */
    public TaskAPI getTaskAPI() {
        return getApi(TaskAPI.class);
    }

    /**
     * 线程切换流程 API。
     * <p>
     * 提供异步线程与 Nukkit 主线程之间的优雅切换编排，支持回调链（{@link ThreadFlow#create()}）
     * 与虚拟线程同步风格（{@link ThreadFlow#virtual(java.util.function.Consumer)}）两种模式。
     *
     * @return 线程切换流程服务
     * @see ThreadFlow
     */
    public ThreadFlow getThreadFlow() {
        return getApi(ThreadFlow.class);
    }

    /**
     * 主线程缓释调度模块 API。
     * <p>
     * 提供异步任务按策略（截止时间 / 固定速率 / 尽力而为）分批释放到 Nukkit 主线程的
     * 多通道调度：全局每 tick 预算内仲裁，支持背压、完成回调与虚拟线程
     * {@code awaitChannel} 等待整批落地。
     *
     * @return 缓释调度服务
     * @see ReleaseAPI
     */
    public ReleaseAPI getReleaseAPI() {
        return getApi(ReleaseAPI.class);
    }

    /**
     * 命令模块 API。
     *
     * @return 命令服务
     */
    public CommandAPI getCommandAPI() {
        return getApi(CommandAPI.class);
    }

    /**
     * 箱子界面模块 API。
     *
     * @return 箱子界面服务
     */
    public InventoryAPI getInventoryAPI() {
        return getApi(InventoryAPI.class);
    }

    /**
     * AI 模块 API。
     * <p>
     * 提供启发式寻路、实体导航、战术行为（找掩体/远离/包抄/寻找高地）与战斗动作（攻击/射箭/使用物品）。
     *
     * @return AI 服务
     */
    public AiAPI getAiAPI() {
        return getApi(AiAPI.class);
    }

    /**
     * 数据保存模块入口。
     * <p>
     * 返回的 {@link DataSaver} 已通过 {@link PluginAware} 机制自动绑定插件实例，
     * rootDir 默认指向本插件（JFrame）数据目录（{@code getDataFolder()}）。
     * <p>
     * <b>业务插件注意</b>：若本类作为前置工具插件被其他插件调用，业务插件应通过
     * {@code getDataSaver().forPlugin(this)} 获取以<b>自身数据目录</b>为根的独立保存器，
     * 避免把数据写进 JFrame 目录。
     *
     * @return 数据保存器
     */
    public DataSaver getDataSaver() {
        return getApi(DataSaver.class);
    }

    /**
     * 计分板模块 API。
     * <p>
     * 提供基于模板的动态计分板：注册 XML 模板 → 给指定/条件/全体玩家显示 →
     * 更新数据自动刷新。
     *
     * @return 计分板服务
     * @see ScoreboardAPI
     */
    public ScoreboardAPI getScoreboardAPI() {
        return getApi(ScoreboardAPI.class);
    }

    /**
     * 标题模块 API。
     * <p>
     * 提供基于模板的标题（Title/Subtitle）与动作栏（ActionBar）显示：
     * 注册 XML 模板 → 给指定/条件/全体玩家显示 → 更新数据自动刷新；
     * 支持瞬时（TRANSIENT，淡入停留淡出后自动清除）与常驻（PERSISTENT，KeepAlive 保活）双模式，
     * 以及 title/subtitle/actionbar 双槽位通道。
     *
     * @return 标题服务
     * @see TitleAPI
     */
    public TitleAPI getTitleAPI() {
        return getApi(TitleAPI.class);
    }

    /**
     * 按类型从容器获取模块 API。
     *
     * @param apiType API 类型
     * @param <T>     API 泛型
     * @return API 实例
     */
    private <T> T getApi(Class<T> apiType) {
        if (applicationContext == null) {
            throw new IllegalStateException("JFrame 容器尚未初始化");
        }
        return applicationContext.getBean(apiType);
    }

    /**
     * 将各模块 API 注册到 Nukkit {@link cn.nukkit.plugin.service.ServiceManager}，
     * 供其他插件通过 {@code getServiceManager().getProvider(XxxAPI.class)} 获取。
     * <p>
     * 在 {@link #onEnable()} 中自动调用。
     */
    private void registerServices() {
        Server server = getServer();
        server.getServiceManager().register(EventAPI.class, getEventAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(ViewAPI.class, getViewAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(ThreadAPI.class, getThreadAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(TaskAPI.class, getTaskAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(ThreadFlow.class, getThreadFlow(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(ReleaseAPI.class, getReleaseAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(CommandAPI.class, getCommandAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(InventoryAPI.class, getInventoryAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(AiAPI.class, getAiAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(DataSaver.class, getDataSaver(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(ScoreboardAPI.class, getScoreboardAPI(), this, ServicePriority.NORMAL);
        server.getServiceManager().register(TitleAPI.class, getTitleAPI(), this, ServicePriority.NORMAL);
    }

    @Override
    public void onLoad() {
        getLogger().info(ON_LOAD);
    }

    @Override
    public void onEnable() {
        instance = this;
        // 插件启用时为已加载模块（含构造阶段加载的 core）绑定 plugin
        bindPlugin();
        // 将各模块 API 注册为服务，供依赖本插件的其他插件发现
        registerServices();
        getLogger().info("JFrame 前置插件已启用，各模块 API 已注册到 ServiceManager");
    }

    @Override
    public void onDisable() {
        // 先关闭可关闭的资源（箱子视图、线程池），再关闭 Spring 容器
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(InventoryAPI.class).closeAll();
            }
        } catch (Exception e) {
            getLogger().error("关闭箱子视图失败", e);
        }
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(ThreadAPI.class).stopAll();
            }
        } catch (Exception e) {
            getLogger().error("关闭线程池失败", e);
        }
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(TaskAPI.class).cancelAll();
            }
        } catch (Exception e) {
            getLogger().error("关闭可挂起任务失败", e);
        }
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(ReleaseAPI.class).close();
            }
        } catch (Exception e) {
            getLogger().error("关闭主线程缓释调度器失败", e);
        }
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(ScoreboardAPI.class).hideAll();
            }
        } catch (Exception e) {
            getLogger().error("关闭计分板失败", e);
        }
        try {
            if (applicationContext != null && applicationContext.isActive()) {
                applicationContext.getBean(TitleAPI.class).disposeAll();
            }
        } catch (Exception e) {
            getLogger().error("关闭标题视图失败", e);
        }
        if (this.applicationContext != null) {
            this.applicationContext.close();
        }
    }
}
