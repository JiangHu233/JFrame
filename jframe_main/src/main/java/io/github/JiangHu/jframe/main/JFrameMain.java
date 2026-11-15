package io.github.JiangHu.jframe.main;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.ai.AiAPI;
import io.github.JiangHu.jframe.ai.AiModule;
import io.github.JiangHu.jframe.async.AsyncModule;
import io.github.JiangHu.jframe.async.flow.ThreadFlow;
import io.github.JiangHu.jframe.async.release.ReleaseAPI;
import io.github.JiangHu.jframe.async.task.TaskAPI;
import io.github.JiangHu.jframe.async.thread.ThreadAPI;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.command.CommandModule;
import io.github.JiangHu.jframe.core.module.ModuleManager;
import io.github.JiangHu.jframe.data.DataModule;
import io.github.JiangHu.jframe.data.DataSaver;
import io.github.JiangHu.jframe.engine.EngineModule;
import io.github.JiangHu.jframe.engine.ecs.EcsAPI;
import io.github.JiangHu.jframe.event.EventAPI;
import io.github.JiangHu.jframe.event.EventModule;
import io.github.JiangHu.jframe.floatingtext.FloatingTextAPI;
import io.github.JiangHu.jframe.floatingtext.FloatingTextModule;
import io.github.JiangHu.jframe.form.FormModule;
import io.github.JiangHu.jframe.form.ViewAPI;
import io.github.JiangHu.jframe.inventory.InventoryModule;
import io.github.JiangHu.jframe.inventory.ui.InventoryAPI;
import io.github.JiangHu.jframe.logger.JLogCommand;
import io.github.JiangHu.jframe.logger.LoggerAPI;
import io.github.JiangHu.jframe.logger.LoggerModule;
import io.github.JiangHu.jframe.main.utils.ConfigEnum;
import io.github.JiangHu.jframe.scoreboard.ScoreboardAPI;
import io.github.JiangHu.jframe.scoreboard.ScoreboardModule;
import io.github.JiangHu.jframe.title.TitleAPI;
import io.github.JiangHu.jframe.title.TitleModule;
import io.github.JiangHu.jframe.transform.TransformAPI;
import io.github.JiangHu.jframe.transform.TransformModule;
import lombok.Getter;

import java.util.List;

/**
 * JFrame 框架主类。
 * <p>
 * 框架入口，持有自研模块容器 {@link ModuleManager} 并登记全部内置模块。
 * 各模块<b>按需加载</b>：首次调用 {@code getXxxAPI()} 时才执行对应模块工厂，
 * 未用到的模块不创建任何对象；{@code config.yml} 的 {@code modules:} 列表
 * 可选地预加载部分模块。
 *
 * <h3>作为「前置插件」使用</h3>
 * 本类本身是一个 Nukkit 插件，可部署为「前置插件 / 库插件」。
 * 其他业务插件在自身 {@code plugin.yml} 中声明 {@code depend: [JFrame]} 后，
 * 即可复用本插件的模块容器与各模块 API，无需各自打包框架代码。
 * 获取 API 的推荐方式是<b>门面方法</b>（首次调用触发懒加载）：
 * <ul>
 *   <li>{@code JFrameMain.getInstance().getEventAPI()} 等</li>
 *   <li>{@code JFrameMain.getApi(EventAPI.class)}</li>
 * </ul>
 * Nukkit {@code ServiceManager} 在模块<b>真实加载后</b>才注册对应服务
 * （未加载模块 {@code getProvider(XxxAPI.class)} 返回 null，可经
 * {@code config.yml} 预加载规避）。
 * <p>
 * 业务插件可调用 {@link EventAPI} / {@link CommandAPI} 的
 * {@code forPlugin(...).scan(...)} 传入<b>自身类加载器</b>，
 * 将自己 jar 内的 {@code @Wrapper} / {@code @CommandController} 注册到框架。
 * 也可 {@code new ModuleManager(this)} 创建<b>自己的隔离容器</b>
 * （详见 core 模块 {@link ModuleManager} 的 javadoc）。
 *
 * @see ConfigEnum
 * @see ModuleManager
 */
public class JFrameMain extends PluginBase {
    public static final String ON_LOAD = "JFrame loaded";

    /** 框架自身单例（构造时赋值，getApi 等静态入口依赖它） */
    @Getter
    protected static JFrameMain instance;

    /** 自研模块容器（懒加载 / PluginAware 绑定 / ServiceManager 注册 / 按序关闭） */
    @Getter
    protected ModuleManager moduleManager;

    /**
     * 按类型获取模块 API（首次调用触发对应模块懒加载）。
     *
     * @param apiType API 类型
     * @param <T>     API 泛型
     * @return API 单例
     */
    public static <T> T getApi(Class<T> apiType) {
        return instance.moduleManager.get(apiType);
    }

    /**
     * 按类型获取 Bean —— 兼容旧 Spring 时代的入口。
     *
     * @param beanType Bean 类型
     * @param <T>      Bean 泛型
     * @return Bean 单例
     * @deprecated Spring 容器已移除，请改用 {@link #getApi(Class)} 或门面方法
     */
    @Deprecated
    public static <T> T getBean(Class<T> beanType) {
        return getApi(beanType);
    }

    public JFrameMain() {
        instance = this;
        this.moduleManager = new ModuleManager(this);
        registerModules(moduleManager);
    }

    /**
     * 登记全部内置模块（仅登记元数据，零实例化——懒加载由首次 get 触发）。
     *
     * @param manager 模块容器
     */
    protected void registerModules(ModuleManager manager) {
        manager.register(new EventModule());
        manager.register(new CommandModule());
        manager.register(new AsyncModule());
        manager.register(new FormModule());
        manager.register(new InventoryModule());
        manager.register(new DataModule());
        manager.register(new AiModule());
        manager.register(new ScoreboardModule());
        manager.register(new TitleModule());
        manager.register(new EngineModule());
        manager.register(new FloatingTextModule());
        manager.register(new LoggerModule());
        manager.register(new TransformModule());
    }

    // -------------------- 模块 API 门面（首次调用触发懒加载） --------------------

    /**
     * 事件模块 API。
     *
     * @return 事件服务
     */
    public EventAPI getEventAPI() {
        return moduleManager.get(EventAPI.class);
    }

    /**
     * 表单模块 API。
     *
     * @return 表单服务
     */
    public ViewAPI getViewAPI() {
        return moduleManager.get(ViewAPI.class);
    }

    /**
     * 线程模块 API。
     *
     * @return 线程服务
     */
    public ThreadAPI getThreadAPI() {
        return moduleManager.get(ThreadAPI.class);
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
        return moduleManager.get(TaskAPI.class);
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
        return moduleManager.get(ThreadFlow.class);
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
        return moduleManager.get(ReleaseAPI.class);
    }

    /**
     * 命令模块 API。
     *
     * @return 命令服务
     */
    public CommandAPI getCommandAPI() {
        return moduleManager.get(CommandAPI.class);
    }

    /**
     * 箱子界面模块 API。
     *
     * @return 箱子界面服务
     */
    public InventoryAPI getInventoryAPI() {
        return moduleManager.get(InventoryAPI.class);
    }

    /**
     * AI 模块 API。
     * <p>
     * 提供启发式寻路、实体导航、战术行为（找掩体/远离/包抄/寻找高地）与战斗动作（攻击/射箭/使用物品）。
     *
     * @return AI 服务
     */
    public AiAPI getAiAPI() {
        return moduleManager.get(AiAPI.class);
    }

    /**
     * 数据保存模块入口。
     * <p>
     * 返回的 {@link DataSaver} 已自动绑定 JFrame 插件实例，
     * rootDir 默认指向本插件（JFrame）数据目录（{@code getDataFolder()}）。
     * <p>
     * <b>业务插件注意</b>：若本类作为前置工具插件被其他插件调用，业务插件应通过
     * {@code getDataSaver().forPlugin(this)} 获取以<b>自身数据目录</b>为根的独立保存器，
     * 避免把数据写进 JFrame 目录。
     *
     * @return 数据保存器
     */
    public DataSaver getDataSaver() {
        return moduleManager.get(DataSaver.class);
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
        return moduleManager.get(ScoreboardAPI.class);
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
        return moduleManager.get(TitleAPI.class);
    }

    /**
     * ECS 模块 API。
     * <p>
     * 提供实体-组件-系统引擎：注解扫描注册（组件/选择器/生成器/解析器/系统）→
     * YAML GameObject 定义加载（文件/字符串/classpath）→ start 启动
     * （存量回溯挂载 + 世界 diff 监听 + 事件桥四级过滤分发 + System tick）。
     *
     * @return ECS 服务
     * @see EcsAPI
     */
    public EcsAPI getEcsAPI() {
        return moduleManager.get(EcsAPI.class);
    }

    /**
     * 行为树 API（游戏引擎模块中与 ECS 并列的决策子系统：
     * 词表注册/数据驱动树加载/黑板——树=数据资产、词表=代码注册、效果输出纯函数）。
     *
     * @return BT 服务
     * @see io.github.JiangHu.jframe.engine.behavior.BtAPI
     */
    public io.github.JiangHu.jframe.engine.behavior.BtAPI getBtAPI() {
        return moduleManager.get(io.github.JiangHu.jframe.engine.behavior.BtAPI.class);
    }

    /**
     * 浮空字模块 API。
     * <p>
     * 提供世界空间文字显示通用原语：瞬态（伤害数字/脚步，寿命+动画自动回收）与
     * 常驻组（大厅全息，\n 多行 + 重连自动补发）；发包假实体渲染（零服务器实体）；
     * 观众策略/自定义动画/中途改字等扩展点。
     *
     * @return 浮空字服务
     * @see FloatingTextAPI
     */
    public FloatingTextAPI getFloatingTextAPI() {
        return moduleManager.get(FloatingTextAPI.class);
    }

    /**
     * 统一日志模块 API。
     * <p>
     * 业务插件经 {@code getLoggerAPI().forPlugin(this)} 取得 {@link LoggerAPI JLogger} 写入
     * 统一日志库（默认静默不进控制台/MCSM 面板，可按插件开镜像）；Web 展示端口由
     * {@code /jlog start|stop|status} 手动启停（首次执行本指令亦触发本模块懒加载），
     * 同机多实例自动聚合（根路由汇总 / {@code /s/<子服>} 分面板）。
     *
     * @return 统一日志服务
     * @see LoggerAPI
     * @see JLogCommand
     */
    public LoggerAPI getLoggerAPI() {
        return moduleManager.get(LoggerAPI.class);
    }

    /**
     * 动态类增强模块 API。
     * <p>
     * 依赖 JFrame 的插件可动态增强 Nukkit（及任意非 JDK 类）的<b>已加载</b>方法：
     * <ul>
     *   <li>注解声明式（推荐）：{@code getTransformAPI().forPlugin(this)
     *       .scan("com.example.myplugin.hook")}——{@code @HookOn} 标注方法，
     *       {@code at = HEAD/RETURN} 为 before/after，{@code at = REPLACE} 为
     *       签名镜像整方法接管（同参同返回值）</li>
     *   <li>命令式：{@code forPlugin(this).hook(...)/replace(...)} 动态挂/摘</li>
     * </ul>
     * 服务器<b>正常启动后</b>即可用（首次注册自动向 JVM 自附加 agent，零启动参数；
     * JDK 21+ 控制台会打一条无害警告；可选 {@code -javaagent:jframe_main.jar}
     * 预注入消除）。插件 disable 时自动停分发并还原字节码。
     *
     * @return 动态类增强服务
     * @see TransformAPI
     * @see io.github.JiangHu.jframe.transform.annotation.HookOn
     */
    public TransformAPI getTransformAPI() {
        return moduleManager.get(TransformAPI.class);
    }

    // -------------------- 生命周期 --------------------

    @Override
    public void onLoad() {
        getLogger().info(ON_LOAD);
    }

    @Override
    public void onEnable() {
        // 绑定插件：刷新暂存的 PluginAware 绑定与 ServiceManager 注册
        moduleManager.bindPlugin(this);
        // 读取 config.yml 的 modules 预加载列表（可选）
        preloadFromConfig();
        getLogger().info("JFrame 前置插件已启用：模块按需加载（首次 getXxxAPI() 触发），"
                + "可用模块见 config.yml 注释");
    }

    @Override
    public void onDisable() {
        // 按序关闭已加载模块（引擎最先、浮空字停拍清场、箱子视图、线程池……logger 最后收尾），
        // 等价于旧版 onDisable 的硬编码清理链
        moduleManager.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /jlog：统一日志 Web 端口手动开关（plugin.yml 静态声明；首次执行懒加载 logger 模块）
        if (command.getName().equalsIgnoreCase("jlog")) {
            return JLogCommand.execute(moduleManager.get(LoggerAPI.class), sender, args);
        }
        return false;
    }

    /**
     * 按 config.yml 的 {@code modules:} 列表预加载模块（可选项，非法 id 记警告）。
     */
    private void preloadFromConfig() {
        List<String> modules = getConfig().getStringList("modules");
        if (modules == null || modules.isEmpty()) {
            return;
        }
        for (String id : modules) {
            String moduleId = id == null ? "" : id.trim();
            if (moduleId.isEmpty()) {
                continue;
            }
            try {
                moduleManager.preload(moduleId);
                getLogger().info("已预加载模块: " + moduleId);
            } catch (Exception e) {
                getLogger().warning("预加载模块失败: " + moduleId + "（" + e.getMessage() + "）");
            }
        }
    }
}
