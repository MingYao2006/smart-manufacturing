package com.smart.production.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产执行相关的 DTO / VO。
 */
public final class ProductionDtos {

    private ProductionDtos() {
    }

    /* ==================== 生产计划 ==================== */

    @Data
    @Schema(description = "生产计划创建请求")
    public static class PlanCreateDTO {

        @NotNull(message = "产品 ID 不能为空")
        @Schema(description = "生产什么产品", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long productId;

        @NotNull(message = "计划数量不能为空")
        @DecimalMin(value = "0.0001", message = "计划数量必须大于 0")
        @Schema(description = "计划数量", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal planQuantity;

        @Schema(description = "计划开始日", example = "2025-01-10")
        private LocalDate planStartDate;

        @Schema(description = "计划完成日（交期）", example = "2025-01-20")
        private LocalDate planEndDate;

        private String remark;
    }

    /* ==================== 工单 ==================== */

    @Data
    @Schema(description = "工单创建请求")
    public static class OrderCreateDTO {

        @Schema(description = "来源计划 ID（可直接创建工单时为 null）", example = "1")
        private Long planId;

        @NotNull(message = "产品 ID 不能为空")
        @Schema(description = "生产什么产品", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long productId;

        @NotNull(message = "计划数量不能为空")
        @DecimalMin(value = "0.0001", message = "计划数量必须大于 0")
        @Schema(description = "计划生产数量", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal planQuantity;

        @Schema(description = "计划开始时间", example = "2025-01-10 08:00:00")
        private LocalDateTime planStartTime;

        @Schema(description = "计划完成时间（交期）", example = "2025-01-20 18:00:00")
        private LocalDateTime planEndTime;

        private String remark;
    }

    @Data
    @Schema(description = "工单状态流转请求")
    public static class TransitDTO {

        @NotBlank(message = "目标状态不能为空")
        @Schema(description = "目标状态码 1-草稿 2-已审核 3-已下达 4-生产中 5-暂停 6-已完工 7-已关闭 8-已取消",
                example = "APPROVED", requiredMode = Schema.RequiredMode.REQUIRED)
        private String targetStatus;

        @Schema(description = "操作原因")
        private String reason;
    }

    @Data
    @Schema(description = "工单列表项")
    public static class OrderVO {
        private Long id;
        private String orderNo;
        private Long planId;
        private Long productId;
        private String productCode;
        private String productName;
        private String spec;
        private String unit;
        private Long bomId;
        private String bomVersion;
        private Long routeId;
        private String routeVersion;
        private BigDecimal planQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        private Integer progress;
        private Integer status;
        private String statusDesc;
        private LocalDateTime planStartTime;
        private LocalDateTime planEndTime;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualEndTime;
        private String remark;
        private LocalDateTime createTime;
    }

    @Data
    @Schema(description = "工单详情（含工序任务与状态历史）")
    public static class OrderDetailVO {
        private OrderVO order;
        private List<WorkTaskVO> tasks;
        private List<StatusLogVO> statusLogs;
    }

    @Data
    @Schema(description = "工单状态流转记录")
    public static class StatusLogVO {
        private Integer fromStatus;
        private String fromStatusDesc;
        private Integer toStatus;
        private String toStatusDesc;
        private String reason;
        private String operatorName;
        private LocalDateTime operateTime;
    }

    /* ==================== 工序任务 ==================== */

    @Data
    @Schema(description = "工序任务视图")
    public static class WorkTaskVO {
        private Long id;
        private Long orderId;
        private String orderNo;
        private Integer stepNo;
        private String stepName;
        private String workCenter;
        private BigDecimal standardHours;
        private BigDecimal planQuantity;
        private BigDecimal reportedQuantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        private BigDecimal actualHours;
        private Integer status;
        private String statusDesc;
        private Integer isInspection;
        private Long operatorId;
        private String operatorName;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    @Data
    @Schema(description = "工序派工请求")
    public static class DispatchDTO {
        @NotNull(message = "操作工 ID 不能为空")
        @Schema(description = "操作工用户 ID", example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long operatorId;

        @Schema(description = "操作工姓名", example = "王工")
        private String operatorName;

        @Schema(description = "工作中心，不传则保持原值", example = "装配一车间")
        private String workCenter;
    }

    /* ==================== 报工 ==================== */

    @Data
    @Schema(description = "生产报工请求")
    public static class ReportCreateDTO {

        @Schema(description = "报工单号（幂等键）。不传则服务端生成；同一单号重复提交只生效一次",
                example = "RP20250115001")
        private String reportNo;

        @NotNull(message = "工序任务 ID 不能为空")
        @Schema(description = "工序任务 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long taskId;

        @NotNull(message = "报工数量不能为空")
        @DecimalMin(value = "0.0001", message = "报工数量必须大于 0")
        @Schema(description = "本次报工数量", example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal reportQuantity;

        @NotNull(message = "合格数不能为空")
        @DecimalMin(value = "0", message = "合格数不能为负数")
        @Schema(description = "合格数", example = "19", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal qualifiedQuantity;

        @NotNull(message = "不良数不能为空")
        @DecimalMin(value = "0", message = "不良数不能为负数")
        @Schema(description = "不良数", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal defectQuantity;

        @Schema(description = "实际工时(小时)", example = "4.5")
        @DecimalMin(value = "0", message = "工时不能为负数")
        private BigDecimal actualHours;

        @Schema(description = "报工人 ID", example = "4")
        private Long operatorId;

        @Schema(description = "报工人姓名", example = "王工")
        private String operatorName;

        private String remark;
    }

    @Data
    @Schema(description = "报工结果")
    public static class ReportResultVO {
        private Long reportId;
        private String reportNo;
        private Long taskId;
        private BigDecimal reportedQuantity;
        private BigDecimal planQuantity;
        private Integer taskStatus;
        private String taskStatusDesc;
        private boolean taskCompleted;
        private String message;
    }

    /* ==================== 跨服务：物料需求 ==================== */

    @Data
    @Schema(description = "工单物料需求（BOM 展开 × 计划数量 × 损耗率，向上取整、同物料汇总）")
    public static class MaterialRequirementVO {
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String spec;
        private String unit;
        @Schema(description = "单位用量（汇总后）")
        private BigDecimal unitQuantity;
        @Schema(description = "损耗率（加权后）")
        private BigDecimal lossRate;
        @Schema(description = "需求数量 = 单位用量 × 计划数量 × (1+损耗率)，向上取整")
        private BigDecimal requiredQuantity;
    }
}
