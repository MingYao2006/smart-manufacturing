package com.smart.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档配置。
 *
 * <p>访问方式：http://localhost:{端口}/doc.html
 */
@Configuration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiConfig {

    @Value("${spring.application.name:smart-service}")
    private String appName;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI().info(new Info()
                .title(appName + " 接口文档")
                .description("智能制造生产执行管理系统（MES）· " + appName)
                .version("1.0.0")
                .contact(new Contact().name("Smart Manufacturing"))
                .license(new License().name("Apache 2.0")));
    }
}
