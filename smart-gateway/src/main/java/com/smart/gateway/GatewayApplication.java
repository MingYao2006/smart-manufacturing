package com.smart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关（9000）。统一入口：路由转发 + JWT 鉴权 + 链路追踪。
 *
 * <p>⚠️ 网关基于 WebFlux（响应式），<b>绝对不能</b>引入 spring-boot-starter-web，
 * 否则会因 Servlet 与 Reactive 冲突而启动失败。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
