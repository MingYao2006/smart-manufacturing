package com.smart.production.controller;

import com.smart.common.result.Result;
import com.smart.production.dto.ProductionDtos.DispatchDTO;
import com.smart.production.dto.ProductionDtos.ReportCreateDTO;
import com.smart.production.dto.ProductionDtos.ReportResultVO;
import com.smart.production.dto.ProductionDtos.WorkTaskVO;
import com.smart.production.entity.ProductionReport;
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
 * 工序任务与报工接口（第 5 周）。
 */
@Tag(name = "13-工序任务与报工")
@RestController
@RequiredArgsConstructor
public class WorkTaskController {

    private final WorkTaskService workTaskService;
    private final ProductionReportService reportService;

    @Operation(summary = "查询工单下的工序任务（按工序号排序）")
    @GetMapping("/work-task/order/{orderId}")
    public Result<List<WorkTaskVO>> listTasksByOrder(@PathVariable Long orderId) {
        return Result.ok(workTaskService.listByOrder(orderId).stream()
                .map(workTaskService::toVO).toList());
    }

    @Operation(summary = "工序任务详情")
    @GetMapping("/work-task/{taskId}")
    public Result<WorkTaskVO> detail(@PathVariable Long taskId) {
        return Result.ok(workTaskService.toVO(workTaskService.getTask(taskId)));
    }

    @Operation(summary = "工序派工（指定操作工/工作中心）")
    @PostMapping("/work-task/{taskId}/dispatch")
    public Result<WorkTaskVO> dispatch(@PathVariable Long taskId, @Valid @RequestBody DispatchDTO dto) {
        return Result.ok(workTaskService.dispatch(taskId, dto), "派工成功");
    }

    @Operation(summary = "开始工序（校验前道工序已完成 + CAS）",
            description = "工艺纪律：前道工序未完成时拒绝；两人同时点开始只有一个成功")
    @PostMapping("/work-task/{taskId}/start")
    public Result<WorkTaskVO> start(@PathVariable Long taskId,
                                    @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.startTask(taskId, operatorId), "工序已开始");
    }

    @Operation(summary = "暂停工序")
    @PostMapping("/work-task/{taskId}/pause")
    public Result<WorkTaskVO> pause(@PathVariable Long taskId,
                                    @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.pauseTask(taskId, operatorId), "工序已暂停");
    }

    @Operation(summary = "恢复工序")
    @PostMapping("/work-task/{taskId}/resume")
    public Result<WorkTaskVO> resume(@PathVariable Long taskId,
                                     @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.resumeTask(taskId, operatorId), "工序已恢复");
    }

    @Operation(summary = "完成工序（质检点工序会进入待质检状态）")
    @PostMapping("/work-task/{taskId}/complete")
    public Result<WorkTaskVO> complete(@PathVariable Long taskId,
                                       @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.completeTask(taskId, operatorId), "工序已完成");
    }

    @Operation(summary = "质检放行（待质检 → 已完成）")
    @PostMapping("/work-task/{taskId}/pass-inspection")
    public Result<WorkTaskVO> passInspection(@PathVariable Long taskId,
                                             @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.passInspection(taskId, operatorId), "质检放行，工序已完成");
    }

    @Operation(summary = "质检不合格退回返工（待质检 → 进行中）")
    @PostMapping("/work-task/{taskId}/reject-inspection")
    public Result<WorkTaskVO> rejectInspection(@PathVariable Long taskId,
                                               @RequestParam(required = false) Long operatorId) {
        return Result.ok(workTaskService.rejectInspection(taskId, operatorId), "已退回返工");
    }

    /* ==================== 报工 ==================== */

    @Operation(summary = "🔑 提交报工",
            description = "校验：合格数+不良数=报工数（服务端）；条件更新防超报；报工单号幂等；"
                    + "事务内写 outbox，异步驱动工单进度重算")
    @PostMapping("/report")
    public Result<ReportResultVO> report(@Valid @RequestBody ReportCreateDTO dto) {
        return Result.ok(reportService.report(dto), "报工成功");
    }

    @Operation(summary = "查询工序的报工明细")
    @GetMapping("/report/task/{taskId}")
    public Result<List<ProductionReport>> listReportsByTask(@PathVariable Long taskId) {
        return Result.ok(reportService.listByTask(taskId));
    }

    @Operation(summary = "查询工单的全部报工记录")
    @GetMapping("/report/order/{orderId}")
    public Result<List<ProductionReport>> listReportsByOrder(@PathVariable Long orderId) {
        return Result.ok(reportService.listByOrder(orderId));
    }

    @Operation(summary = "生成报工单号（供前端预先生成幂等键）")
    @GetMapping("/report/new-no")
    public Result<String> newReportNo() {
        return Result.ok(workTaskService.newReportNo());
    }
}
