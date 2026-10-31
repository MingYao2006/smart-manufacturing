package com.smart.inventory.controller;

import com.smart.common.result.Result;
import com.smart.inventory.dto.InventoryDtos.InventoryVO;
import com.smart.inventory.dto.InventoryDtos.KittingVO;
import com.smart.inventory.dto.InventoryDtos.RequirementItem;
import com.smart.inventory.dto.InventoryDtos.ReservationVO;
import com.smart.inventory.dto.InventoryDtos.ReserveRequest;
import com.smart.inventory.dto.InventoryDtos.ReserveResultVO;
import com.smart.inventory.service.InventoryService;
import com.smart.inventory.service.ReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存查询 / 预留 / 入库 / 对账接口（第 4 周）。
 */
@Tag(name = "21-库存（三量模型）")
@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ReconcileService reconcileService;

    @Operation(summary = "查询物料库存（返回现有量/预留量/可用量三个值）")
    @GetMapping("/inventory/{materialId}")
    public Result<InventoryVO> getInventory(
            @PathVariable Long materialId,
            @Parameter(description = "仓库 ID，不传默认 1") @RequestParam(required = false) Long warehouseId) {
        return Result.ok(inventoryService.getInventory(materialId, warehouseId));
    }

    @Operation(summary = "查询全部库存")
    @GetMapping("/inventory/list")
    public Result<List<InventoryVO>> listInventory() {
        return Result.ok(inventoryService.listInventory());
    }

    @Operation(summary = "低库存预警（可用量 < 安全库存）")
    @GetMapping("/inventory/below-safety")
    public Result<List<InventoryVO>> belowSafety() {
        return Result.ok(inventoryService.listBelowSafetyStock());
    }

    @Operation(summary = "物料入库（含批次号，追溯起点）")
    @PostMapping("/inventory/inbound")
    public Result<InventoryVO> inbound(@Valid @RequestBody com.smart.inventory.dto.InventoryDtos.InboundRequest req) {
        return Result.ok(inventoryService.inbound(req.getMaterialId(), req.getQuantity(),
                req.getBatchNo(), req.getWarehouseId(), req.getOperatorId(),
                req.getOperatorName(), req.getRemark()), "入库成功");
    }

    /* ==================== 预留 ==================== */

    @Operation(summary = "🔑 物料预留（条件更新，可用量不足则整体回滚）",
            description = "多物料按 materialId 升序预留防死锁；任一物料不足则整个事务回滚，不会出现部分预留")
    @PostMapping("/reservation/reserve")
    public Result<ReserveResultVO> reserve(@Valid @RequestBody ReserveRequest req) {
        return Result.ok(inventoryService.reserve(req));
    }

    @Operation(summary = "查询工单的预留记录")
    @GetMapping("/reservation/order/{orderId}")
    public Result<List<ReservationVO>> listReservations(@PathVariable Long orderId) {
        return Result.ok(inventoryService.listReservationsByOrder(orderId));
    }

    @Operation(summary = "释放工单的全部预留（工单取消时调用）")
    @PostMapping("/reservation/release/{orderId}")
    public Result<Integer> release(@PathVariable Long orderId) {
        return Result.ok(inventoryService.releaseByOrder(orderId), "预留已释放");
    }

    /* ==================== 齐套分析 ==================== */

    @Operation(summary = "🔑 齐套分析（需求 vs 可用量，标记充足/短缺与缺口数量）",
            description = "只计算和比对，不扣库存 —— 计算与预留职责分离")
    @PostMapping("/inventory/kitting/{orderId}")
    public Result<KittingVO> kitting(@PathVariable Long orderId,
                                     @RequestParam(required = false) Long warehouseId,
                                     @RequestBody List<RequirementItem> requirements) {
        return Result.ok(inventoryService.kitting(orderId, requirements, warehouseId));
    }

    /* ==================== 对账 ==================== */

    @Operation(summary = "库存对账（库存表 vs 流水累加 / 预留表推算）",
            description = "默认只检测并记录异常，不自动修正；autoFix=true 时以流水为准修正")
    @PostMapping("/inventory/reconcile")
    public Result<Integer> reconcile(
            @Parameter(description = "是否自动修正（以流水为准）") @RequestParam(defaultValue = "false") boolean autoFix) {
        var anomalies = reconcileService.reconcile(autoFix);
        return Result.ok(anomalies.size(),
                anomalies.isEmpty() ? "对账通过，未发现异常" : "发现 " + anomalies.size() + " 条异常");
    }
}
