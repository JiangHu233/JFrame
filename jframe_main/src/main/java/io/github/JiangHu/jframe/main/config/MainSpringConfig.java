package io.github.JiangHu.jframe.main.config;

import io.github.JiangHu.jframe.command.config.CommandSpringConfig;
import io.github.JiangHu.jframe.core.config.CoreSpringConfig;
import io.github.JiangHu.jframe.data.config.DataSpringConfig;
import io.github.JiangHu.jframe.event.config.EventSpringConfig;
import io.github.JiangHu.jframe.form.config.FormSpringConfig;
import io.github.JiangHu.jframe.inventory.ui.config.InventorySpringConfig;
import io.github.JiangHu.jframe.thread.config.ThreadSpringConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/**
 * 框架核心 Spring 配置。
 * <p>
 * 框架核心模块的 Spring 配置，包含核心模块的 Spring 配置。
 * <p>
 * 注意：该类不能被其他类继承。
 *
 * @see CoreSpringConfig
 */
@Configuration
@ImportResource("classpath:main-spring.xml")
@Import({
        CoreSpringConfig.class,
        CommandSpringConfig.class,
        ThreadSpringConfig.class,
        FormSpringConfig.class,
        EventSpringConfig.class,
        DataSpringConfig.class,
        InventorySpringConfig.class
})
public class MainSpringConfig {
}
