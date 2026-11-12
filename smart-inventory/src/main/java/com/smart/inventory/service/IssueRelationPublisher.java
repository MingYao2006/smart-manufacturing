package com.smart.inventory.service;

import java.math.BigDecimal;

/**
 * 领料批次关系消息的写入口（依赖倒置）。
 *
 * <p>与 smart-production 的 {@code MessagePublisher} 同一套路：
 * 业务层只依赖抽象，单元测试可用 JDK 动态代理桩替换，不必启动数据库。
 *
 * <p>实现见 {@link MaterialIssueOutboxService} —— 本地消息表，与领料同事务写入。
 */
public interface IssueRelationPublisher {

    /**
     * 记下"某工单领了某批次料"，由调用方的事务保证原子性。
     *
     * @param orderId      工单 ID（完工回填 target 的依据）
     * @param orderNo      工单号快照
     * @param issueNo      领料单号
     * @param materialId   物料 ID
     * @param materialCode 物料编码快照
     * @param batchNo      领用的物料批次号（追溯起点）；为空表示非批次管理物料，跳过
     * @param quantity     领用数量
     */
    void enqueue(Long orderId, String orderNo, String issueNo,
                 Long materialId, String materialCode,
                 String batchNo, BigDecimal quantity);
}
