package com.smart.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量与追溯相关的 DTO / VO。
 */
public final class QualityDtos {

    private QualityDtos() {
    }

    /* ==================== 批次 ==================== */

    @Data
    @Schema(description = "物料批次创建请求（入库时生成，追溯链路的起点）")
    public static class MaterialBatchCreateDTO {
        @NotNull(message = "物料 ID 不能为空")
        private Long materialId;
        private String materialCode;
        private String materialName;
        @NotNull(message = "批次数量不能为空")
        private BigDecimal quantity;
        @Schema(description = "批次号，不传自动生成 MB+日期+序列")
        private String batchNo;
        @Schema(description = "供应商 ID（追溯源头）")
        private Long supplierId;
        private String supplierName;
        private Integer qualityStatus;
        private String remark;
    }

    @Data
    @Schema(description = "产品批次创建请求（工单完工时生成）")
    public static class ProductBatchCreateDTO {
        @NotNull(message = "产品 ID 不能为空")
        private Long productId;
        private String productCode;
        private String productName;
        @NotNull(message = "工单 ID 不能为空")
        private Long productionOrderId;
        private String orderNo;
        @NotNull(message = "批次数量不能为空")
        private BigDecimal quantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        @Schema(description = "批次号，不传自动生成 PB+日期+序列")
        private String batchNo;
        private String remark;
    }

    @Data
    @Schema(description = "批次视图")
    public static class BatchVO {
        private Long id;
        private String batchNo;
        private Integer batchType;
        private String batchTypeDesc;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private Long productId;
        private String productCode;
        private String productName;
        private Long productionOrderId;
        private String orderNo;
        private BigDecimal quantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        private Long supplierId;
        private String supplierName;
        private java.time.LocalDateTime inboundTime;
        private Integer qualityStatus;
        private Integer status;
    }

    /* ==================== 批次关系（追溯边） ==================== */

    @Data
    @Schema(description = "批次关系创建请求（领料消耗 / 完工产出）")
    public static class RelationCreateDTO {
        @NotBlank(message = "源批次号不能为空")
        @Schema(description = "源批次号（被消耗的物料批次）", example = "MB20250101001")
        private String sourceBatchNo;

        @Schema(description = "目标批次号（产出的产品批次）；领料阶段产品批次还未生成，可为空并稍后回填")
        private String targetBatchNo;

        @Schema(description = "工单 ID")
        private Long productionOrderId;

        private String orderNo;

        @Schema(description = "消耗数量")
        private BigDecimal quantity;

        @Schema(description = "关系类型 CONSUME-领料消耗 OUTPUT-完工产出", example = "CONSUME")
        private String relationType = "CONSUME";
    }

    /* ==================== 追溯结果（树形） ==================== */

    @Data
    @Schema(description = "追溯节点（树形结构，前端可展开）")
    public static class TraceNode {
        @Schema(description = "层级，根为 0")
        private int level;
        private Long batchId;
        private String batchNo;
        @Schema(description = "1-物料批次 2-产品批次")
        private Integer batchType;
        private String batchTypeDesc;
        private String code;
        private String name;
        @Schema(description = "批次数量")
        private BigDecimal batchQuantity;
        @Schema(description = "沿本路径累计的消耗量")
        private BigDecimal edgeQuantity;
        private Long supplierId;
        private String supplierName;
        private Long productionOrderId;
        private String orderNo;
        private java.time.LocalDateTime inboundTime;
        private Integer qualityStatus;
        @Schema(description = "是否有下游/上游可继续展开")
        private boolean hasChildren;
        private List<TraceNode> children;
    }

    @Data
    @Schema(description = "追溯结果")
    public static class TraceResultVO {
        @Schema(description = "追溯方向 BACKWARD-反向(成品→原料) FORWARD-正向(原料→成品)")
        private String direction;
        @Schema(description = "起点批次")
        private BatchVO root;
        @Schema(description = "追溯树")
        private List<TraceNode> tree;
        @Schema(description = "涉及的批次总数（去重后）")
        private int totalNodes;
        @Schema(description = "最大深度")
        private int maxDepth;
        @Schema(description = "是否因达到深度上限而截断")
        private boolean truncated;
        @Schema(description = "提示信息")
        private String message;
    }

    /* ==================== 质检 ==================== */

    @Data
    @Schema(description = "质检单创建请求")
    public static class InspectionCreateDTO {
        @NotNull(message = "质检类型不能为空")
        @Schema(description = "1-来料检 2-首检 3-巡检 4-完工检 5-工序检", example = "5")
        private Integer inspectionType;

        @Schema(description = "工单 ID")
        private Long orderId;

        private String orderNo;

        @Schema(description = "工序任务 ID（工序检时填）")
        private Long taskId;

        private Integer stepNo;

        private String stepName;

        private String batchNo;

        @NotNull(message = "检验数量不能为空")
        private BigDecimal inspectionQuantity;

        @NotNull(message = "合格数不能为空")
        private BigDecimal qualifiedQuantity;

        @NotNull(message = "不良数不能为空")
        private BigDecimal defectQuantity;

        @Schema(description = "判定结果 1-合格 2-不合格 3-让步接收 4-返工", example = "1")
        private Integer result;

        private Long inspectorId;

        private String inspectorName;

        private String remark;

        @Schema(description = "检验项明细")
        private List<RecordCreateDTO> records;
    }

    @Data
    @Schema(description = "检验项明细")
    public static class RecordCreateDTO {
        @NotBlank(message = "检验项名称不能为空")
        private String itemName;
        private String standardValue;
        private String actualValue;
        private Integer isQualified;
        private String defectCode;
        private String defectName;
        private BigDecimal defectQuantity;
        private String remark;
    }

    @Data
    @Schema(description = "质检单视图")
    public static class InspectionVO {
        private Long id;
        private String inspectionNo;
        private Integer inspectionType;
        private String inspectionTypeDesc;
        private Long orderId;
        private String orderNo;
        private Long taskId;
        private Integer stepNo;
        private String stepName;
        private String batchNo;
        private BigDecimal inspectionQuantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        private Integer result;
        private String resultDesc;
        private Integer status;
        private String inspectorName;
        private java.time.LocalDateTime inspectionTime;
        private String remark;
        private List<RecordVO> records;
    }

    @Data
    @Schema(description = "检验项视图")
    public static class RecordVO {
        private Long id;
        private String itemName;
        private String standardValue;
        private String actualValue;
        private Integer isQualified;
        private String defectCode;
        private String defectName;
        private BigDecimal defectQuantity;
        private String remark;
    }
}
