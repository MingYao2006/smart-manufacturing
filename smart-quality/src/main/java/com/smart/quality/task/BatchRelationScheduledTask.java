package com.smart.quality.task;

import com.smart.quality.entity.ProductBatch;
import com.smart.quality.mapper.ProductBatchMapper;
import com.smart.quality.service.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 完工回填定时任务：把领料阶段空 target 的边，按工单号统一指向产品批次。
 *
 * <p>为什么可以异步：边在领料时就通过 outbox 落库了，数据不会丢；
 * 晚一点补 target 只是补全指向。而且边本身是异步投递的，完全可能
 * "产品批次先生成、边后到"，所以回填必须是<b>反复扫描</b>，不能只在完工时做一次。
 *
 * <p>幂等：{@code bindOrderToProductBatch} 只更新 {@code target_batch_id IS NULL} 的行，
 * 重复扫描不会覆盖已回填的边。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smart.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BatchRelationScheduledTask {

    private final TraceService traceService;
    private final ProductBatchMapper batchMapper;

    @Scheduled(fixedDelayString = "${smart.quality.bind-delay-ms:10000}",
            initialDelayString = "${smart.quality.bind-initial-delay-ms:15000}")
    public void bindPendingRelations() {
        List<Long> orderIds;
        try {
            orderIds = traceService.listPendingBindOrderIds(100);
        } catch (Exception e) {
            log.warn("[批次回填] 扫描失败 {}", e.getMessage());
            return;
        }
        if (orderIds.isEmpty()) {
            return;
        }
        int bound = 0;
        for (Long orderId : orderIds) {
            List<ProductBatch> batches = batchMapper.selectByOrderId(orderId);
            String productBatchNo = batches.stream()
                    .filter(b -> b.getBatchType() != null
                            && b.getBatchType() == ProductBatch.TYPE_PRODUCT)
                    .map(ProductBatch::getBatchNo)
                    .findFirst().orElse(null);
            if (productBatchNo == null) {
                continue;   // 产品批次还没生成，下次扫再处理
            }
            try {
                bound += traceService.bindOrderConsumptionsToProductBatch(orderId, productBatchNo);
            } catch (Exception e) {
                log.error("[批次回填] orderId={} 回填失败", orderId, e);
            }
        }
        if (bound > 0) {
            log.info("[批次回填] 本轮扫描 {} 个工单，回填 {} 条边", orderIds.size(), bound);
        }
    }
}
