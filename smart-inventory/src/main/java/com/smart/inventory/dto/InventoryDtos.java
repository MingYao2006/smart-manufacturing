package com.smart.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存相关的 DTO / VO。
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /* ==================== 物料需求（来自生产服务） ==================== */

    @Data
    @Schema(description = "物料需求项（由生产服务按 BOM 算出后传给库存服务）")
    public static class RequirementItem {

        @NotNull(message = "物料 ID 不能为空")
        private Long materialId;

        private String materialCode;

        private String materialName;

        private String unit;

        @NotNull(message = "需求数量不能为空")
        @DecimalMin(value = "0.0001", message = "需求数量必须大于 0")
        private BigDecimal requiredQuantity;

        private Integer isBatchManaged;
    }

    /* ==================== 预留 ==================== */

    @Data
    @Schema(description = "物料预留请求（一个工单的全部物料，同一事务内完成）")
    public static class ReserveRequest {

        @NotNull(message = "工单 ID 不能为空")
        @Schema(description = "工单 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long orderId;

        @Schema(description = "工单号", example = "WO20250115001")
        private String orderNo;

        @Schema(description = "仓库 ID，不传默认 1")
        private Long warehouseId;

        @Schema(description = "预留有效天数，默认 7 天", example = "7")
        private Integer expireDays;

        @NotEmpty(message = "预留物料不能为空")
        @Size(max = 200, message = "单次预留物料不能超过 200 项")
        @Valid
        @Schema(description = "需要预留的物料清单（由生产服务计算 BOM 需求后传入）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private List<RequirementItem> items;
    }

    @Data
    @Schema(description = "预留结果")
    public static class ReserveResultVO {
        private Long orderId;
        private int reservedCount;
        private String message;
    }

    @Data
    @Schema(description = "预留记录视图")
    public static class ReservationVO {
        private Long id;
        private String reservationNo;
        private Long orderId;
        private String orderNo;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private BigDecimal reservedQuantity;
        private BigDecimal issuedQuantity;
        private BigDecimal remainingQuantity;
        private Integer status;
        private String statusDesc;
        private java.time.LocalDateTime expireTime;
    }

    /* ==================== 领料 / 退料 ==================== */

    @Data
    @Schema(description = "领料请求")
    public static class IssueRequest {

        @Schema(description = "领料单号（幂等键）。不传则服务端生成；同号重复提交只生效一次",
                example = "IS20250115001")
        private String issueNo;

        @NotNull(message = "工单 ID 不能为空")
        @Schema(description = "工单 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long orderId;

        @Schema(description = "工单号", example = "WO20250115001")
        private String orderNo;

        @Schema(description = "仓库 ID，不传默认 1")
        private Long warehouseId;

        @Schema(description = "领料人 ID", example = "3")
        private Long operatorId;

        @Schema(description = "领料人姓名", example = "李仓管")
        private String operatorName;

        private String remark;

        @NotEmpty(message = "领料明细不能为空")
        @Valid
        @Schema(description = "领料明细", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<IssueItemDTO> items;
    }

    @Data
    @Schema(description = "领料明细项")
    public static class IssueItemDTO {

        @NotNull(message = "物料 ID 不能为空")
        private Long materialId;

        @NotNull(message = "领用数量不能为空")
        @DecimalMin(value = "0.0001", message = "领用数量必须大于 0")
        @Schema(description = "领用数量", example = "102", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal quantity;

        @Schema(description = "批次号（批次管理物料必填），追溯关键", example = "MB20250101001")
        private String batchNo;
    }

    @Data
    @Schema(description = "领料结果")
    public static class IssueResultVO {
        private Long issueId;
        private String issueNo;
        private Long orderId;
        private int itemCount;
        private BigDecimal totalQuantity;
        private boolean idempotent;
        private String message;
    }

    @Data
    @Schema(description = "退料请求")
    public static class ReturnRequest {

        @Schema(description = "退料单号（幂等键）")
        private String returnNo;

        @NotNull(message = "工单 ID 不能为空")
        private Long orderId;

        private String orderNo;

        private Long warehouseId;

        private Long operatorId;

        private String operatorName;

        private String remark;

        @NotEmpty(message = "退料明细不能为空")
        @Valid
        private List<IssueItemDTO> items;
    }

    /* ==================== 入库 ==================== */

    @Data
    @Schema(description = "物料入库请求（含批次，追溯起点）")
    public static class InboundRequest {

        @NotNull(message = "物料 ID 不能为空")
        private Long materialId;

        @NotNull(message = "入库数量不能为空")
        @DecimalMin(value = "0.0001", message = "入库数量必须大于 0")
        private BigDecimal quantity;

        @Schema(description = "批次号，不传则自动生成 MB+日期+序列")
        private String batchNo;

        @Schema(description = "供应商 ID（物料批次的追溯源头）")
        private Long supplierId;

        @Schema(description = "供应商名称")
        private String supplierName;

        private Long warehouseId;

        private Long operatorId;

        private String operatorName;

        private String remark;
    }

    /* ==================== 库存查询 ==================== */

    @Data
    @Schema(description = "库存视图（含三量）")
    public static class InventoryVO {
        private Long id;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        private Long warehouseId;
        @Schema(description = "现有量")
        private BigDecimal quantity;
        @Schema(description = "预留量")
        private BigDecimal reservedQuantity;
        @Schema(description = "可用量 = 现有量 - 预留量（计算值，不落库）")
        private BigDecimal availableQuantity;
        private BigDecimal safetyStock;
        @Schema(description = "是否低于安全库存")
        private boolean belowSafetyStock;
    }

    @Data
    @Schema(description = "齐套分析结果")
    public static class KittingVO {
        private Long orderId;
        private boolean enough;
        private List<KittingItemVO> items;
    }

    @Data
    @Schema(description = "齐套分析单项")
    public static class KittingItemVO {
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        @Schema(description = "需求数量")
        private BigDecimal requiredQuantity;
        @Schema(description = "现有量")
        private BigDecimal quantity;
        @Schema(description = "预留量")
        private BigDecimal reservedQuantity;
        @Schema(description = "可用量")
        private BigDecimal availableQuantity;
        @Schema(description = "是否充足")
        private boolean enough;
        @Schema(description = "缺口数量（充足时为 0）")
        private BigDecimal shortage;
    }
}
