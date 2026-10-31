package com.smart.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 系统服务（9001）。用户 / 角色 / 登录（JWT）。
 */
@SpringBootApplication(scanBasePackages = {"com.smart.system", "com.smart.common"})
@EnableDiscoveryClient
@MapperScan("com.smart.system.mapper")
public class SystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
