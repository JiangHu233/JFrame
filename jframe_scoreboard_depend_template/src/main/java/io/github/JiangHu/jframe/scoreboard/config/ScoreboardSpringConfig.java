package io.github.JiangHu.jframe.scoreboard.config;

import io.github.JiangHu.jframe.scoreboard.ScoreboardAPI;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * 计分板模块 Spring 配置入口。
 * <p>
 * 导入 {@code scoreboard-spring.xml}，装配计分板模块的 Bean DAG：
 * {@code templateEngine → scoreboardManager → scoreboardAPI}。
 * <p>
 * 通过 {@code ConfigEnum.SCOREBOARD} 由 {@code JFrameMain} 动态导入。
 *
 * @see ScoreboardAPI
 */
@Configuration
@ImportResource("classpath:scoreboard-spring.xml")
public class ScoreboardSpringConfig {
}
