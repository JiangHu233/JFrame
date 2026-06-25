package io.github.JiangHu.jframe.main;

import cn.nukkit.plugin.PluginBase;
import io.github.JiangHu.jframe.main.config.SpringConfig;
import io.github.JiangHu.jframe.main.utils.ConfigEnum;
import lombok.Getter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * 添加模块
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
    }

    @Override
    public void onLoad() {
        getLogger().info("ON_LOAD");
    }

    @Override
    public void onEnable() {
        instance = this;
    }

    @Override
    public void onDisable() {
        if (this.applicationContext != null) {
            this.applicationContext.close();
        }
    }
}
