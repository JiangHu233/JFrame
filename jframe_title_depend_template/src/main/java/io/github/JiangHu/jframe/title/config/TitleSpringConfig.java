package io.github.JiangHu.jframe.title.config;

import io.github.JiangHu.jframe.title.TitleAPI;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * 标题模块 Spring 配置入口。
 * <p>
 * 导入 {@code title-spring.xml}，装配标题模块的 Bean DAG：
 * {@code templateEngine → titleManager → titleAPI}。
 * <p>
 * 通过 {@code ConfigEnum.TITLE} 由 {@code JFrameMain} 动态导入（阶段三接入，本阶段仅提供配置类）。
 *
 * @see TitleAPI
 */
@Configuration
@ImportResource("classpath:title-spring.xml")
public class TitleSpringConfig {
}
