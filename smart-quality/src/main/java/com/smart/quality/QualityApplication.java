package com.smart.quality;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 质量服务（9005）。
 *
 * <p>职责：质检单 / 批次管理 / 正反向追溯。
 * 本服务承载 MES 最有辨识度的能力 —— 基于批次关系图的追溯。
 *
 * <p>说明：质检与工序流转是强同步链路，理论上应放在 production-service 用本地事务保证原子性。
 * 本项目按需求文档把它独立成服务（职责清晰、便于展示），
 * 工序门禁通过 production-service 的 {@code WAIT_INSPECTION} 状态机实现，
 * 质检结果由本服务提供查询，二者通过状态而非分布式事务协同 —— 这是本项目的一个明确取舍。
 */
@SpringBootApplication(scanBasePackages = {"com.smart.quality", "com.smart.common"})
@EnableDiscoveryClient
@MapperScan("com.smart.quality.mapper")
public class QualityApplication {

    public static void main(String[] args) {
        SpringApplication.run(QualityApplication.class, args);
    }
}
