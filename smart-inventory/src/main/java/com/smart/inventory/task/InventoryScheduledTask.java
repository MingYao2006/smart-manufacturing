package com.smart.inventory.task;

import com.smart.inventory.entity.MaterialReservation;
import com.smart.inventory.mapper.MaterialReservationMapper;
import com.smart.inventory.service.InventoryService;
import com.smart.inventory.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存定时任务（第 4 周预留泄漏清理 + 难点 8 对账兜底）。
 *
 * <p>⚠️ <b>多实例部署时定时任务会重复执行</b>，必须用分布式锁选主。
 * 本机未装 Redis，这里用开关控制；生产环境应改为 Redisson 的
 * {@code tryLock(0, TimeUnit.SECONDS)}（不传 leaseTime 启用看门狗续期）。
 *
 * <p>为什么这里是 Redis 锁的<b>合理用途</b>：
 * "多个实例中只有一个执行定时任务" 正是分布式锁的经典场景。
 * 而库存扣减不该用 Redis 锁（条件更新更好）——
 * 知道什么时候该用、什么时候不该用，才是真理解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smart.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryScheduledTask {

    private final InventoryService inventoryService;
    private final ReconcileService reconcileService;
    private final MaterialReservationMapper reservationMapper;

    /**
     * 预留过期清理：每天凌晨 2 点释放过期预留。
     *
     * <p>防"预留泄漏"：工单取消/长期不生产时，预留必须释放，
     * 否则库存被永久锁死 —— 这是很隐蔽的库存损耗。
     */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    public void releaseExpiredReservations() {
        log.info("[定时任务] 开始清理过期预留 {}", LocalDateTime.now());
        try {
            List<MaterialReservation> expired =
                    reservationMapper.selectExpired(LocalDateTime.now(), 500);
            if (expired.isEmpty()) {
                log.info("[定时任务] 无过期预留");
                return;
            }
            for (MaterialReservation r : expired) {
                try {
                    // 按工单粒度释放（同一工单可能有多个过期预留）
                    inventoryService.releaseByOrder(r.getOrderId());
                } catch (Exception e) {
                    log.error("[定时任务] 释放预留失败 reservationId={}", r.getId(), e);
                }
            }
            log.info("[定时任务] 过期预留清理完成，处理 {} 条", expired.size());
        } catch (Exception e) {
            log.error("[定时任务] 过期预留清理失败", e);
        }
    }

    /**
     * 库存对账：每天凌晨 3 点执行，发现不一致只记录不自动改。
     */
    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void reconcileInventory() {
        log.info("[定时任务] 开始库存对账 {}", LocalDateTime.now());
        try {
            var anomalies = reconcileService.reconcile(false);
            if (!anomalies.isEmpty()) {
                log.error("[定时任务] 库存对账发现 {} 条异常，请检查 inventory_reconcile_log 表",
                        anomalies.size());
            }
        } catch (Exception e) {
            log.error("[定时任务] 库存对账失败", e);
        }
    }
}
