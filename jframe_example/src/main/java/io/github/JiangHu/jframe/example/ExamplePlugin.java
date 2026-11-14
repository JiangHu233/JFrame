package io.github.JiangHu.jframe.example;

import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.command.CommandAPI;
import io.github.JiangHu.jframe.engine.ecs.EcsAPI;
import io.github.JiangHu.jframe.example.ecs.command.EcsTestController;
import io.github.JiangHu.jframe.example.logger.LogDemoController;
import io.github.JiangHu.jframe.main.JFrameMain;

/**
 * JFrame ECS 示例插件主类 —— jframe_ecs 模块的<b>实机测试入口</b>。
 *
 * <h3>本插件演示什么</h3>
 * <ul>
 *   <li><b>接入姿势</b>：业务插件依赖前置 JFrame 后，四行代码接入 ECS 引擎
 *       （注解扫描 → classpath 定义加载 → 命令手动启动）</li>
 *   <li><b>五组演示场景</b>（定义文件 {@code resources/gameobjects/example.yml}）：
 *     <ol>
 *       <li>魔法箱 —— 内置 blockentity-class 选择器 + 事件桥双通道（when SpEL / 自定义解析器）</li>
 *       <li>精英僵尸 —— 内置 entity-class 选择器 + 系统 query 名牌显血 + 方法内首行守卫</li>
 *       <li>守卫区 —— 自定义 near-spawn 参数化选择器 + 多定义并集</li>
 *       <li>幽灵波次 —— 自定义生成器 + 纯虚拟 gameobject + 波次系统</li>
 *       <li>竞技场 —— POJO 产物代理 + spawn/despawn 文件 IO 对（创建写 marker / 销毁删 marker）</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <h3>启动流程（手动 start）</h3>
 * <p>
 * {@code onEnable} 只完成<b>注册</b>（扫描五类注解 + 加载 YAML 定义），<b>不启动引擎</b>；
 * 由 {@code /ecs start} 手动启动——这样可以在启动前先观察服务器裸跑状态，
 * 启动瞬间又能直观看到"存量回溯"（已有的箱子/僵尸立即挂载）。
 *
 * <h3>为什么不自己搭建模块容器？</h3>
 * <p>
 * {@link JFrameMain} 已持有共享的模块容器并登记全部内置模块；业务插件首次调用
 * {@code getEcsAPI()} 时 engine 模块才真正初始化（懒加载），取门面调用即可，
 * 详见旧版示例的说明（模式不变）。需要私有 Bean 注册表的插件也可以
 * {@code new ModuleManager(this)} 自建隔离容器（见 README）。
 *
 * <h3>部署方式</h3>
 * <ul>
 *   <li>{@code plugin.yml} 声明 {@code depend: [JFrame]}</li>
 *   <li>{@code pom.xml} 中 {@code jframe_main} 为 {@code provided} 作用域（不重复打包框架类）</li>
 *   <li>服务器 {@code plugins/} 目录先放 {@code jframe_main.jar}，再放 {@code jframe_example.jar}</li>
 * </ul>
 *
 * @see EcsAPI
 * @see EcsTestController
 */
public class ExamplePlugin extends PluginBase {

    /** 本插件实例（ArenaTemplateSpawner 等无状态处理器取数据目录用） */
    private static ExamplePlugin instance;

    /**
     * 取本插件实例（onEnable 后可用）。
     *
     * @return 插件实例
     */
    public static ExamplePlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        // 1. 获取前置插件 JFrame 的单例（首次 getEcsAPI() 触发 engine 模块懒加载）
        Plugin jframePlugin = getServer().getPluginManager().getPlugin("JFrame");
        if (!(jframePlugin instanceof JFrameMain jframeMain)) {
            throw new IllegalStateException(
                    "前置插件 JFrame 未找到或类型不匹配，请确认已部署 jframe_main.jar 并在 plugin.yml 声明 depend: [JFrame]");
        }

        EcsAPI ecs = jframeMain.getEcsAPI();
        CommandAPI commandAPI = jframeMain.getCommandAPI();

        // 2. 命令先行：静态注入 EcsAPI（jframe_command 仅支持无参构造），再扫描命令控制器
        EcsTestController.setEcs(ecs);
        commandAPI.forPlugin(this).scan("io.github.JiangHu.jframe.example.ecs.command");

        // 2b. 统一日志演示：/logdemo 写各级别统一日志（默认静默，/jlog start 后网页查看）
        LogDemoController.setLogger(jframeMain.getLoggerAPI().forPlugin(this));
        commandAPI.forPlugin(this).scan("io.github.JiangHu.jframe.example.logger");

        // 3. ECS 注册：扫描本插件 jar 内五类注解（组件/选择器/生成器/解析器/系统）
        ecs.forPlugin(this).scan("io.github.JiangHu.jframe.example.ecs");

        // 4. 加载 GameObject 定义：扫描本插件 jar resources/gameobjects/ 下全部 *.yml
        //    （加载即对存量实体回溯挂载的语义在 start 时统一生效，见 /ecs start）
        ecs.forPlugin(this).loadGameObjects("gameobjects");

        // 5. 不启动引擎 —— 由 /ecs start 手动启动（实机测试"存量回溯"最直观的方式）
        getLogger().info("ECS 示例插件已启用：已注册 " + ecs.definitions().size()
                + " 条定义但引擎未启动，输入 /ecs 查看帮助，/ecs start 启动");
    }

    // onDisable 留空：引擎 stop 由 JFrameMain.onDisable 最先统一执行（未 start 时亦安全），
    // 命令随本插件卸载由命令模块处理。
}
