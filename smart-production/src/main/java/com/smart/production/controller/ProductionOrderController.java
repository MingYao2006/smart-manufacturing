package com.smart.production.controller;

import com.smart.common.result.PageResult;
import com.smart.common.result.Result;
import com.smart.production.dto.ProductionDtos.OrderCreateDTO;
import com.smart.production.dto.ProductionDtos.OrderDetailVO;
import com.smart.production.dto.ProductionDtos.OrderVO;
import com.smart.production.dto.ProductionDtos.TransitDTO;
import com.smart.production.service.ProductionOrderService;
import com.smart.production.statemachine.ProductionOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 生产工单接口（第 3 周）。
 */
@Tag(name = "11-生产工单（状态机）")
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class ProductionOrderController {

    private final ProductionOrderService orderService;

    @Operation(summary = "创建工单（自动快照 BOM 与工艺路线版本）",
            description = "工单创建时会校验产品启用、存在生效 BOM 与工艺路线，并把 bomId/routeId 写入工单锁定版本")
    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.ok(orderService.createOrder(dto), "工单创建成功（草稿）");
    }

    @Operation(summary = "分页查询工单（看板用）")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "工单号/产品名称模糊搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态码 1-8") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long productId) {
        return Result.ok(orderService.pageOrder(current, size, keyword, status, productId));
    }

    @Operation(summary = "工单详情（含工序任务与状态流转历史）")
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable Long id) {
        return Result.ok(orderService.getOrderDetail(id));
    }

    @Operation(summary = "工单状态流转（统一入口，CAS 并发安全）",
            description = "合法迁移：DRAFT→APPROVED/CANCELLED, APPROVED→RELEASED/CANCELLED, "
                    + "RELEASED→IN_PROGRESS/CANCELLED, IN_PROGRESS→PAUSED/COMPLETED/CANCELLED, "
                    + "PAUSED→IN_PROGRESS/CANCELLED, COMPLETED→CLOSED")
    @PostMapping("/{id}/transit")
    public Result<OrderVO> transit(@PathVariable Long id, @Valid @RequestBody TransitDTO dto) {
        ProductionOrderStatus target = parseTarget(dto.getTargetStatus());
        return Result.ok(orderService.transit(id, target, dto.getReason()),
                "工单状态已流转到[" + target.getDesc() + "]");
    }

    @Operation(summary = "审核通过")
    @PostMapping("/{id}/approve")
    public Result<OrderVO> approve(@PathVariable Long id,
                                   @RequestParam(required = false) String reason) {
        return Result.ok(orderService.approve(id, reason), "工单审核通过");
    }

    @Operation(summary = "下达工单（同步生成工序任务）",
            description = "下达后按快照的 routeId 展开工序任务；重复下达不会重复生成（唯一索引兜底）")
    @PostMapping("/{id}/release")
    public Result<OrderVO> release(@PathVariable Long id,
                                   @RequestParam(required = false) String reason) {
        return Result.ok(orderService.release(id, reason), "工单已下达");
    }

    @Operation(summary = "取消工单")
    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable Long id,
                                  @RequestParam(required = false) String reason) {
        return Result.ok(orderService.cancel(id, reason), "工单已取消");
    }

    @Operation(summary = "手动触发工单进度重算（幂等）")
    @PostMapping("/{id}/recalculate")
    public Result<Void> recalculate(@PathVariable Long id) {
        orderService.recalculateProgress(id);
        return Result.ok(null, "进度已重算");
    }

    @Operation(summary = "定时兜底：修复'工序全完成但工单未完工'的异常数据")
    @PostMapping("/repair-stuck")
    public Result<Integer> repairStuck() {
        return Result.ok(orderService.repairStuckOrders(), "兜底修复完成");
    }

    @Operation(summary = "看板：各状态工单数统计")
    @GetMapping("/stats/status")
    public Result<List<Map<String, Object>>> statusStats() {
        return Result.ok(orderService.statusStats());
    }

    /**
     * 允许传状态码（数字）或枚举名（如 APPROVED）。
     */
    private ProductionOrderStatus parseTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new com.smart.common.exception.BusinessException(
                    com.smart.common.result.ErrorCode.PARAM_ERROR, "目标状态不能为空");
        }
        String v = raw.trim().toUpperCase();
        try {
            int code = Integer.parseInt(v);
            return ProductionOrderStatus.of(code);
        } catch (NumberFormatException ignore) {
            // 不是数字，按枚举名解析
        }
        try {
            return ProductionOrderStatus.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new com.smart.common.exception.BusinessException(
                    com.smart.common.result.ErrorCode.PARAM_ERROR, "未知的工单状态: " + raw);
        }
    }
}
