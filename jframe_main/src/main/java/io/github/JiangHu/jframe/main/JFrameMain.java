package io.github.JiangHu.jframe.main;

import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.core.config.CoreSpringConfig;
import io.github.JiangHu.jframe.core.module.PluginAware;
import io.github.JiangHu.jframe.main.config.MainSpringConfig;
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
 * 框架启动时，会自动加载核心模块（{@link CoreSpringConfig}），
 * 并注册其他模块的 SpringConfig。
 *
 */
public class JFrameMain extends PluginBase {
    public static final String ON_LOAD = "JFrame loaded";

    @Getter
    protected static JFrameMain instance;

    @Getter
    protected AnnotationConfigApplicationContext applicationContext;
    @Getter
    protected Set<ConfigEnum> modules = new HashSet<ConfigEnum>(List.of(ConfigEnum.CORE));

    protected JFrameMain() {
        this.applicationContext = new AnnotationConfigApplicationContext(MainSpringConfig.class);
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
