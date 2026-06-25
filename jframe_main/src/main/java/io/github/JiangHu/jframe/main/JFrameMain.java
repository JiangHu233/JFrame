package io.github.JiangHu.jframe.main;

import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.main.config.SpringConfig;
import io.github.JiangHu.jframe.main.utils.ConfigEnum;
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
 * 框架启动时，会自动加载核心模块（{@link io.github.JiangHu.jframe.core.config.SpringConfig}），
 * 并注册其他模块的 SpringConfig。
 *
 */
public class JFrameMain extends PluginBase {
    public static final String ON_LOAD = "JFrame loaded";

    @Getter
    private static JFrameMain instance;

    @Getter
    private AnnotationConfigApplicationContext applicationContext;
    @Getter
    private Set<ConfigEnum> modules = new HashSet<ConfigEnum>(List.of(ConfigEnum.CORE));

    private JFrameMain() {
        this.applicationContext = new AnnotationConfigApplicationContext(SpringConfig.class);
    }

    /**
     * 添加模块。
     * <p>
     * 将指定模块的 SpringConfig 动态注册到当前容器并刷新。
     * 刷新完成后会<b>自动绑定 plugin</b>：扫描容器中所有 {@link PluginAware}
     * Bean 并调用 {@link PluginAware#bindPlugin(cn.nukkit.plugin.Plugin)}，
     * 将当前插件实例注入进去。
     * <p>
     * 由于 {@code refresh()} 会重建所有单例 Bean，每次导入后都会重新绑定，
     * 因此无需担心新模块的 Bean 丢失插件引用。
     *
     * @param modules 模块枚举数组
     */
    public void satisfyRequired(ConfigEnum... modules) {
        for (ConfigEnum module : modules) {
            if (!this.modules.contains(module)) {
                this.modules.add(module);
                this.applicationContext.register(module.getSpringConfigClass());
                this.applicationContext.refresh();
            }
        }
        // 导入模块后自动绑定 plugin（refresh 会重建 Bean，故每次都需重新绑定）
        bindPlugin();
    }

    /**
     * 将当前插件实例绑定到容器中所有 {@link PluginAware} Bean。
     * <p>
     * 在以下时机自动调用：
     * <ul>
     *   <li>{@link #onEnable()}：插件启用时，为核心模块及已导入模块绑定一次</li>
     *   <li>{@link #satisfyRequired(ConfigEnum...)}：每次导入新模块刷新容器后绑定</li>
     * </ul>
     * 单个 Bean 绑定异常会被捕获并记录，不影响其他 Bean。
     */
    private void bindPlugin() {
        Map<String, PluginAware> awareBeans = applicationContext.getBeansOfType(PluginAware.class);
        for (PluginAware aware : awareBeans.values()) {
            try {
                aware.bindPlugin(this);
            } catch (Exception e) {
                getLogger().error("绑定 plugin 到 " + aware.getClass().getName() + " 失败", e);
            }
        }
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
    }

    @Override
    public void onDisable() {
        if (this.applicationContext != null) {
            this.applicationContext.close();
        }
    }
}
