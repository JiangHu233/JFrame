package io.github.JiangHu.jframe.command.config;

import io.github.JiangHu.jframe.command.CommandAPI;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * 命令模块 Spring 配置入口。
 * <p>
 * 导入 {@code command-spring.xml}，装配命令模块的 Bean DAG：
 * {@code commandRegistry → commandEngine → commandAPI}。
 * <p>
 * 通过 {@code ConfigEnum.COMMAND} 由 {@code JFrameMain} 动态导入。
 *
 * @see CommandAPI
 */
@Configuration
@ImportResource("classpath:command-spring.xml")
public class CommandSpringConfig {
}
