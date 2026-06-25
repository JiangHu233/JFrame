package io.github.JiangHu.jframe.core.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

@Configuration
@ComponentScan("io.github.JiangHu.jframe.core")
@ImportResource("classpath:core-spring.xml")
public class SpringConfig {
}
