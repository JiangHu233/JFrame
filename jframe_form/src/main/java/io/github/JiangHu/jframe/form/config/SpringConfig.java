package io.github.JiangHu.jframe.form.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

@Configuration
@ComponentScan("io.github.JiangHu.jframe.form")
@ImportResource("classpath:form-spring.xml")
public class SpringConfig {

}
