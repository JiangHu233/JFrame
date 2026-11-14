package io.github.JiangHu.jframe.engine.behavior;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.core.scan.AnnotatedClassScanner;
import io.github.JiangHu.jframe.engine.ecs.engine.EcsEngine;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.behavior.builtin.BlackboardCondition;
import io.github.JiangHu.jframe.engine.behavior.builtin.HasComponentCondition;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为树 API — 游戏引擎模块中与 ECS 并列的决策子系统入口
 * （树=数据资产、词表=代码注册、黑板=每宿主记忆、效果输出=纯函数适配异步决策）。
 *
 * <h3>宿主接入</h3>
 * <ol>
 *   <li>启动：{@code bt.forPlugin(this).scan("包名")} 注册行为/条件词表</li>
 *   <li>载树：{@link #loadTrees(File)}（或 reloadTrees 热载）</li>
 *   <li>每决策周期：构造 {@link BtContext}（黑板+宿主实体+tick）→
 *       {@link BtTree#tick} → 宿主 apply 段执行 {@link BtContext#takeEffects()}</li>
 * </ol>
 */
public final class BtAPI implements PluginAware {

    /** 词表注册表 */
    private final BtRegistry registry;

    /** 引擎（内置条件词表直读组件用；宿主实体已在 Context 中，此处仅作装配依赖） */
    private final EcsEngine engine;

    /** 已加载树：树名 → 定义 */
    private final Map<String, BtTree> trees = new ConcurrentHashMap<>();

    /** 绑定插件 */
    private Plugin plugin;

    /**
     * 构造 API（Spring 装配；词表预注册内置集）。
     *
     * @param engine 引擎
     */
    public BtAPI(EcsEngine engine) {
        this.engine = engine;
        this.registry = new BtRegistry();
        this.registry.bindComponentRegistry(engine.componentRegistry());
        registerBuiltins();
    }

    private void registerBuiltins() {
        try {
            registry.registerCondition("ecs:has-component", HasComponentCondition.class);
            registry.registerCondition("bt:blackboard", BlackboardCondition.class);
        } catch (Exception e) {
            throw new EcsConfigException("BT 内置词表注册失败", e);
        }
    }

    // ==================== PluginAware ====================

    /**
     * 绑定插件。
     *
     * @param plugin 插件
     */
    @Override
    public void bindPlugin(Plugin plugin) {
        if (this.plugin == null) {
            this.plugin = plugin;
        }
    }

    /**
     * 插件作用域（词表扫描）。
     *
     * @param plugin 插件
     * @return 作用域
     */
    public BtPluginScope forPlugin(Plugin plugin) {
        return new BtPluginScope(this, plugin.getClass().getClassLoader());
    }

    // ==================== 注册与加载 ====================

    /**
     * 注册行为词（scan 路径内部使用）。
     *
     * @param name 词名
     * @param type 行为叶类
     */
    public void registerAction(String name, Class<? extends BtAction> type) {
        registry.registerAction(name, type);
    }

    /**
     * 注册条件词（scan 路径内部使用）。
     *
     * @param name 词名
     * @param type 条件叶类
     */
    public void registerCondition(String name, Class<? extends BtCondition> type) {
        registry.registerCondition(name, type);
    }

    /**
     * 从文件或目录加载树（目录递归 *.yml；同名树冲突即报错）。
     *
     * @param file 文件或目录（如 game/bt/）
     * @return 加载的树列表
     */
    public List<BtTree> loadTrees(File file) {
        List<BtTree> loaded = new BtTreeLoader(registry).load(file);
        for (BtTree tree : loaded) {
            if (trees.putIfAbsent(tree.name(), tree) != null) {
                throw new EcsConfigException("BT 树名冲突: '" + tree.name() + "'");
            }
        }
        return loaded;
    }

    /**
     * 热载树（同名覆盖；新名追加——树定义引用由宿主缓存时注意取新实例）。
     *
     * @param file 文件或目录
     * @return 加载的树列表
     */
    public List<BtTree> reloadTrees(File file) {
        List<BtTree> loaded = new BtTreeLoader(registry).load(file);
        for (BtTree tree : loaded) {
            trees.put(tree.name(), tree);
        }
        return loaded;
    }

    /**
     * 按名取树。
     *
     * @param name 树名
     * @return 树定义；不存在返回 null
     */
    public BtTree getTree(String name) {
        return trees.get(name);
    }

    /**
     * 已加载树清单（诊断）。
     *
     * @return 树名列表
     */
    public List<String> treeNames() {
        return List.copyOf(trees.keySet());
    }

    /**
     * 词表诊断信息。
     *
     * @return 行为词+条件词清单
     */
    public Map<String, List<String>> describeRegistry() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("actions", registry.actionWords());
        result.put("conditions", registry.conditionWords());
        return result;
    }

    /**
     * 新建每宿主黑板。
     *
     * @return 黑板
     */
    public BtBlackboard newBlackboard() {
        return new BtBlackboard();
    }
}
