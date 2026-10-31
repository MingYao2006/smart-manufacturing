package com.smart.production;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 生产执行服务（9003）。MES 的"中枢"。
 *
 * <p>职责：生产计划 / 生产工单（状态机）/ 工序任务 / 生产报工 / 本地消息表。
 * 吸收了原方案里的 process-service —— 工序任务是工单的执行视图，
 * 报工是工单进度的唯一数据来源，它们共享同一个聚合根和事务边界，
 * 拆开会导致"报工 → 更新工单进度"变成跨服务调用 + 分布式事务。
 */
@SpringBootApplication(scanBasePackages = {"com.smart.production", "com.smart.common"})
@EnableDiscoveryClient
@MapperScan("com.smart.production.mapper")
public class ProductionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductionApplication.class, args);
    }
}
