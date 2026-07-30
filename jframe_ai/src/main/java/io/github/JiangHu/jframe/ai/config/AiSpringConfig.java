package io.github.JiangHu.jframe.ai.config;

import io.github.JiangHu.jframe.ai.AiAPI;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * AI 模块 Spring 配置入口。
 * <p>
 * 导入 {@code ai-spring.xml}，装配 AI 模块的 Bean DAG：
 * 四个独立组件（pathFinder / navigatorManager / tacticalScanner / combatActions）
 * → aiAPI。
 * <p>
 * 通过 {@code ConfigEnum.AI} 由 {@code JFrameMain} 动态导入。
 *
 * @see AiAPI
 */
@Configuration
@ImportResource("classpath:ai-spring.xml")
public class AiSpringConfig {
}
