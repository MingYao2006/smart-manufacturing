package com.smart.inventory.service;

import com.smart.inventory.entity.InventoryReconcileLog;
import com.smart.inventory.entity.MaterialInventory;
import com.smart.inventory.mapper.InventoryReconcileLogMapper;
import com.smart.inventory.mapper.MaterialInventoryMapper;
import com.smart.inventory.mapper.MaterialReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存对账服务（第 4 周任务 4.7 / 难点 8 机制 3）。
 *
 * <h3>对账做什么</h3>
 * <ol>
 *   <li><b>现有量对账</b>：库存表 {@code quantity} 应等于流水累加值</li>
 *   <li><b>预留量对账</b>：库存表 {@code reserved_quantity} 应等于有效预留表的
 *       {@code Σ(reserved - issued)}</li>
 *   <li><b>负值检测</b>：任何量出现负数都是严重异常（条件更新理论上不可能，但要防）</li>
 * </ol>
 *
 * <h3>为什么对账是必需的</h3>
 * 任何分布式/并发方案都需要最后一层"账实核对"兜底。
 * 即使条件更新做对了，也可能有手工改库、程序 bug、异常中断导致的偏差。
 * 对账的价值不在"修正"，而在<b>发现</b> —— 能发现才谈得上修。
 *
 * <p>默认只<b>检测并记录</b>，不自动修正（自动修正可能掩盖真实 bug）。
 * 提供 {@code autoFix} 开关用于演示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private final MaterialInventoryMapper inventoryMapper;
    private final MaterialReservationMapper reservationMapper;
    private final InventoryReconcileLogMapper reconcileLogMapper;

    /**
     * 执行对账。
     *
     * @param autoFix 是否自动以流水为准修正偏差
     * @return 发现的异常列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<InventoryReconcileLog> reconcile(boolean autoFix) {
        List<MaterialInventory> inventories = inventoryMapper.selectAll();
        Map<String, BigDecimal> ledgerQtyMap = new HashMap<>();
        Map<String, BigDecimal> ledgerReservedMap = new HashMap<>();
        for (Map<String, Object> row : inventoryMapper.sumLedger()) {
            String key = key(row.get("materialId"), row.get("warehouseId"));
            ledgerQtyMap.put(key, toDecimal(row.get("ledgerQty")));
            ledgerReservedMap.put(key, toDecimal(row.get("ledgerReserved")));
        }
        Map<String, BigDecimal> expectedReservedMap = new HashMap<>();
        for (Map<String, Object> row : reservationMapper.sumActiveReserved()) {
            expectedReservedMap.put(key(row.get("materialId"), row.get("warehouseId")),
                    toDecimal(row.get("expectedReserved")));
        }

        List<InventoryReconcileLog> anomalies = new ArrayList<>();
        for (MaterialInventory inv : inventories) {
            String k = key(inv.getMaterialId(), inv.getWarehouseId());
            BigDecimal realQty = nz(inv.getQuantity());
            BigDecimal realReserved = nz(inv.getReservedQuantity());
            BigDecimal ledgerQty = ledgerQtyMap.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal ledgerReserved = ledgerReservedMap.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal expectedReserved = expectedReservedMap
                    .getOrDefault(k, BigDecimal.ZERO);

            // 1. 负值检测
            if (realQty.compareTo(BigDecimal.ZERO) < 0) {
                anomalies.add(buildLog(inv, realQty, ledgerQty,
                        realQty.subtract(ledgerQty), realReserved, realReserved,
                        "NEGATIVE", "现有量为负数（严重异常）"));
            }
            if (realReserved.compareTo(BigDecimal.ZERO) < 0) {
                anomalies.add(buildLog(inv, realQty, ledgerQty, BigDecimal.ZERO,
                        realReserved, expectedReserved, "NEGATIVE", "预留量为负数（严重异常）"));
            }
            // 2. 预留量不能超过现有量
            if (realReserved.compareTo(realQty) > 0) {
                anomalies.add(buildLog(inv, realQty, ledgerQty, BigDecimal.ZERO,
                        realReserved, expectedReserved, "RESERVED_DIFF",
                        "预留量超过现有量（可用量为负）"));
            }

            // 3. 现有量对账：库存表 vs 流水累加
            if (realQty.compareTo(ledgerQty) != 0) {
                anomalies.add(buildLog(inv, realQty, ledgerQty,
                        realQty.subtract(ledgerQty), realReserved, realReserved,
                        "QUANTITY_DIFF", "库存表现有量与流水累加不一致"));
                if (autoFix) {
                    inventoryMapper.fixQuantity(inv.getId(), ledgerQty);
                    log.warn("[对账修正] material={} 现有量 {} -> {}（以流水为准）",
                            inv.getMaterialId(), realQty, ledgerQty);
                }
            }

            // 4. 预留量对账：库存表 reserved vs 有效预留表推算
            if (realReserved.compareTo(expectedReserved) != 0) {
                anomalies.add(buildLog(inv, realQty, ledgerQty, BigDecimal.ZERO,
                        realReserved, expectedReserved, "RESERVED_DIFF",
                        "库存表预留量与预留表推算不一致"));
            }
        }

        for (InventoryReconcileLog a : anomalies) {
            reconcileLogMapper.insert(a);
        }
        if (anomalies.isEmpty()) {
            log.info("[库存对账] 完成，共检查 {} 条库存记录，未发现异常", inventories.size());
        } else {
            log.error("[库存对账] 完成，共检查 {} 条记录，发现 {} 条异常（详见 inventory_reconcile_log）",
                    inventories.size(), anomalies.size());
        }
        return anomalies;
    }

    private InventoryReconcileLog buildLog(MaterialInventory inv, BigDecimal invQty,
                                           BigDecimal ledgerQty, BigDecimal diff,
                                           BigDecimal invReserved, BigDecimal expectedReserved,
                                           String type, String remark) {
        InventoryReconcileLog log = new InventoryReconcileLog();
        log.setMaterialId(inv.getMaterialId());
        log.setWarehouseId(inv.getWarehouseId());
        log.setInventoryQty(invQty);
        log.setLedgerQty(ledgerQty);
        log.setDiff(diff);
        log.setInventoryReserved(invReserved);
        log.setExpectedReserved(expectedReserved);
        log.setAbnormalType(type);
        log.setHandleStatus(0);
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }

    private String key(Object materialId, Object warehouseId) {
        return String.valueOf(materialId) + ":" + String.valueOf(warehouseId);
    }

    private BigDecimal toDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(o.toString());
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
