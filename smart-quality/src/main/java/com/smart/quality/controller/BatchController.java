package com.smart.quality.controller;

import com.smart.common.result.Result;
import com.smart.quality.dto.QualityDtos.BatchVO;
import com.smart.quality.dto.QualityDtos.InspectionCreateDTO;
import com.smart.quality.dto.QualityDtos.InspectionVO;
import com.smart.quality.dto.QualityDtos.MaterialBatchCreateDTO;
import com.smart.quality.dto.QualityDtos.ProductBatchCreateDTO;
import com.smart.quality.dto.QualityDtos.RelationCreateDTO;
import com.smart.quality.dto.QualityDtos.TraceResultVO;
import com.smart.quality.entity.BatchRelation;
import com.smart.quality.service.BatchService;
import com.smart.quality.service.InspectionService;
import com.smart.quality.service.TraceService;
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
import java.util.Map;

/**
 * 质量与批次追溯接口（第 6 周）。
 */
@Tag(name = "31-质量与批次追溯")
@RestController
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;
    private final TraceService traceService;
    private final InspectionService inspectionService;

    /* ==================== 批次 ==================== */

    @Operation(summary = "创建物料批次（入库时生成，带供应商作为追溯源头）")
    @PostMapping("/batch/material")
    public Result<BatchVO> createMaterialBatch(@Valid @RequestBody MaterialBatchCreateDTO dto) {
        return Result.ok(batchService.createMaterialBatch(dto), "物料批次创建成功");
    }

    @Operation(summary = "创建产品批次（工单完工时生成）")
    @PostMapping("/batch/product")
    public Result<BatchVO> createProductBatch(@Valid @RequestBody ProductBatchCreateDTO dto) {
        return Result.ok(batchService.createProductBatch(dto), "产品批次创建成功");
    }

    @Operation(summary = "按批次号查询批次")
    @GetMapping("/batch/{batchNo}")
    public Result<BatchVO> getBatch(@PathVariable String batchNo) {
        return Result.ok(batchService.getBatch(batchNo));
    }

    @Operation(summary = "按类型查询批次 1-物料批次 2-产品批次")
    @GetMapping("/batch/list")
    public Result<List<BatchVO>> listByType(@RequestParam(required = false) Integer batchType) {
        return Result.ok(batchService.listByType(batchType));
    }

    @Operation(summary = "查询工单产出的批次")
    @GetMapping("/batch/order/{orderId}")
    public Result<List<BatchVO>> listBatchesByOrder(@PathVariable Long orderId) {
        return Result.ok(batchService.listByOrder(orderId));
    }

    @Operation(summary = "按供应商追溯物料批次")
    @GetMapping("/batch/supplier/{supplierId}")
    public Result<List<BatchVO>> listBySupplier(@PathVariable Long supplierId) {
        return Result.ok(batchService.listBySupplier(supplierId));
    }

    /* ==================== 批次关系 ==================== */

    @Operation(summary = "记录批次关系（领料消耗 / 完工产出）",
            description = "必须在领料/完工的同一事务内记录，否则追溯链会断")
    @PostMapping("/batch/relation")
    public Result<BatchRelation> recordRelation(@Valid @RequestBody RelationCreateDTO dto) {
        return Result.ok(traceService.recordRelation(dto.getSourceBatchNo(), dto.getTargetBatchNo(),
                dto.getProductionOrderId(), dto.getOrderNo(), dto.getQuantity(),
                dto.getRelationType()), "批次关系已记录");
    }

    @Operation(summary = "完工时把领料阶段的待关联关系回填到产品批次")
    @PostMapping("/batch/relation/bind")
    public Result<Integer> bind(@RequestParam Long productionOrderId,
                                @RequestParam String productBatchNo) {
        return Result.ok(traceService.bindOrderConsumptionsToProductBatch(
                productionOrderId, productBatchNo), "批次关系已回填");
    }

    /* ==================== 🔑 追溯 ==================== */

    @Operation(summary = "🔑 反向追溯（成品→原料）：查出用了哪些物料批次及供应商",
            description = "沿 source 方向 BFS；含环路防护与深度限制（默认 10 层）")
    @GetMapping("/trace/backward")
    public Result<TraceResultVO> backward(
            @Parameter(description = "产品批次号", example = "PB20250115001")
            @RequestParam String batchNo,
            @Parameter(description = "最大深度，0 表示默认 10 层")
            @RequestParam(defaultValue = "0") int maxDepth) {
        return Result.ok(traceService.backward(batchNo, maxDepth));
    }

    @Operation(summary = "🔑 正向追溯（原料→成品）：查出该批次影响了哪些成品批次",
            description = "质量事故精准召回的技术基础；沿 target 方向 BFS")
    @GetMapping("/trace/forward")
    public Result<TraceResultVO> forward(
            @Parameter(description = "物料批次号", example = "MB20250101001")
            @RequestParam String batchNo,
            @Parameter(description = "最大深度，0 表示默认 10 层")
            @RequestParam(defaultValue = "0") int maxDepth) {
        return Result.ok(traceService.forward(batchNo, maxDepth));
    }

    /* ==================== 质检 ==================== */

    @Operation(summary = "创建质检单（工序检/完工检/来料检）",
            description = "result 四态：1-合格 2-不合格 3-让步接收 4-返工")
    @PostMapping("/inspection")
    public Result<InspectionVO> createInspection(@Valid @RequestBody InspectionCreateDTO dto) {
        return Result.ok(inspectionService.createInspection(dto), "质检单已创建");
    }

    @Operation(summary = "质检单详情（含检验项明细）")
    @GetMapping("/inspection/{id}")
    public Result<InspectionVO> getInspection(@PathVariable Long id) {
        return Result.ok(inspectionService.getInspection(id));
    }

    @Operation(summary = "查询工单的质检记录")
    @GetMapping("/inspection/order/{orderId}")
    public Result<List<InspectionVO>> listInspectionsByOrder(@PathVariable Long orderId) {
        return Result.ok(inspectionService.listByOrder(orderId));
    }

    @Operation(summary = "查询工序任务的质检记录")
    @GetMapping("/inspection/task/{taskId}")
    public Result<List<InspectionVO>> listInspectionsByTask(@PathVariable Long taskId) {
        return Result.ok(inspectionService.listByTask(taskId));
    }

    @Operation(summary = "工序质检是否已合格放行（门禁校验）")
    @GetMapping("/inspection/task/{taskId}/passed")
    public Result<Boolean> isPassed(@PathVariable Long taskId) {
        return Result.ok(inspectionService.isTaskInspectionPassed(taskId));
    }

    @Operation(summary = "不良代码统计（质量分析）")
    @GetMapping("/inspection/defect-stats")
    public Result<List<Map<String, Object>>> defectStats() {
        return Result.ok(inspectionService.defectStatistics());
    }
}
