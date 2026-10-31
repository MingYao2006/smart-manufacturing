package com.smart.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 基础资料服务（9002）。
 *
 * <p>职责：产品 / 物料 / BOM（版本化）/ 工艺路线（版本化）。
 * 这是 MES 的"基础资料域"，其他服务通过 Feign 查询这里的生效版本。
 */
@SpringBootApplication(scanBasePackages = {"com.smart.product", "com.smart.common"})
@EnableDiscoveryClient
@MapperScan("com.smart.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
