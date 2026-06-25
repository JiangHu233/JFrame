package io.github.JiangHu.jframe.thread.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

@Configuration
@ComponentScan("io.github.JiangHu.jframe.thread")
@ImportResource("classpath:thread-spring.xml")
public class SpringConfig {
}
