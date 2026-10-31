package com.smart.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务与异步配置。
 *
 * <p>本项目所有定时补偿任务（outbox 投递、预留过期释放、工单进度兜底扫描、库存对账）
 * 都依赖这里开启的调度能力。
 *
 * <p>⚠️ 注意：多实例部署时定时任务会重复执行，必须用分布式锁选主
 * （见 smart-inventory / smart-production 的 ScheduledLock 工具）。
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableAsync
@ConditionalOnProperty(name = "smart.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
