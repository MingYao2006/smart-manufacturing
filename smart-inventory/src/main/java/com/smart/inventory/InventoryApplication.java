package com.smart.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 库存服务（9004）。
 *
 * <p>职责：物料库存（三量模型）/ 预留 / 领退料 / 库存流水 / 对账。
 * 必须独立成服务 —— 库存扣减是整个项目技术含量最高的部分
 * （并发控制、预留实扣两段式、账实对账），独立出来才能讲成一个完整的技术专题。
 */
@SpringBootApplication(scanBasePackages = {"com.smart.inventory", "com.smart.common"})
@EnableDiscoveryClient
@MapperScan("com.smart.inventory.mapper")
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
