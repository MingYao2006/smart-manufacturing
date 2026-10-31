package com.smart.production.controller;

import com.smart.common.result.Result;
import com.smart.production.dto.ProductionDtos.DispatchDTO;
import com.smart.production.dto.ProductionDtos.MaterialRequirementVO;
import com.smart.production.dto.ProductionDtos.PlanCreateDTO;
import com.smart.production.dto.ProductionDtos.ReportCreateDTO;
import com.smart.production.dto.ProductionDtos.ReportResultVO;
import com.smart.production.dto.ProductionDtos.WorkTaskVO;
import com.smart.production.entity.ProductionPlan;
import com.smart.production.entity.ProductionReport;
import com.smart.production.service.ProductionOrderService;
import com.smart.production.service.ProductionPlanService;
import com.smart.production.service.ProductionReportService;
import com.smart.production.service.WorkTaskService;
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
 * 生产计划 / 物料需求 / 工序任务 / 报工 接口。
 */
@Tag(name = "12-生产计划与物料需求")
@RestController
@RequiredArgsConstructor
public class ProductionPlanController {

    private final ProductionPlanService planService;
    private final ProductionOrderService orderService;

    @Operation(summary = "创建生产计划")
    @PostMapping("/plan")
    public Result<ProductionPlan> createPlan(@Valid @RequestBody PlanCreateDTO dto) {
        return Result.ok(planService.createPlan(dto), "生产计划创建成功");
    }

    @Operation(summary = "查询生产计划列表")
    @GetMapping("/plan/list")
    public Result<List<ProductionPlan>> listPlans(
            @Parameter(description = "状态 1-草稿 2-已确认 3-已转工单 4-已取消")
            @RequestParam(required = false) Integer status) {
        return Result.ok(planService.listPlans(status));
    }

    @Operation(summary = "生产计划详情")
    @GetMapping("/plan/{id}")
    public Result<ProductionPlan> getPlan(@PathVariable Long id) {
        return Result.ok(planService.getPlan(id));
    }

    @Operation(summary = "确认生产计划（草稿 → 已确认）")
    @PostMapping("/plan/{id}/confirm")
    public Result<ProductionPlan> confirmPlan(@PathVariable Long id) {
        return Result.ok(planService.confirmPlan(id), "计划已确认");
    }

    @Operation(summary = "标记计划已转工单")
    @PostMapping("/plan/{id}/convert")
    public Result<ProductionPlan> convertPlan(@PathVariable Long id, @RequestParam Long orderId) {
        return Result.ok(planService.markConverted(id, orderId), "计划已标记为已转工单");
    }

    @Operation(summary = "🔑 计算工单物料需求（BOM 展开 × 计划数量 × 损耗率，向上取整、同物料汇总）",
            description = "使用工单【锁定的 bomId】而非产品当前 BOM；损耗率会被计入，结果向上取整到整数")
    @GetMapping("/order/{orderId}/requirements")
    public Result<List<MaterialRequirementVO>> requirements(@PathVariable Long orderId) {
        return Result.ok(planService.calculateRequirements(orderService.getOrderEntity(orderId)));
    }

    @Operation(summary = "按 bomId + 数量试算物料需求（不创建工单）")
    @GetMapping("/bom/{bomId}/requirements")
    public Result<List<MaterialRequirementVO>> trialRequirements(
            @PathVariable Long bomId,
            @Parameter(description = "计划数量", example = "100") @RequestParam java.math.BigDecimal quantity) {
        return Result.ok(planService.calculateRequirementsByBom(bomId, quantity));
    }
}
