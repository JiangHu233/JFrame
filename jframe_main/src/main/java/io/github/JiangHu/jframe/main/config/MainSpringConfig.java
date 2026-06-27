package io.github.JiangHu.jframe.main.config;

import io.github.JiangHu.jframe.core.config.CoreSpringConfig;
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
@Import({CoreSpringConfig.class})
public class MainSpringConfig {
}
