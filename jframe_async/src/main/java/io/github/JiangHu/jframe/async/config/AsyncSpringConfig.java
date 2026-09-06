package io.github.JiangHu.jframe.async.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

@Configuration
@ComponentScan("io.github.JiangHu.jframe.async")
@ImportResource("classpath:async-spring.xml")
public class AsyncSpringConfig {
}
