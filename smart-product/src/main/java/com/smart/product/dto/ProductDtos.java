package com.smart.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 基础资料相关的 DTO / VO 集合。
 *
 * <p>放在一个文件里是为了减少文件数量（本项目按技术专题组织，不按实体组织）。
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /* ==================== 产品 ==================== */

    @Data
    @Schema(description = "产品创建/更新请求")
    public static class ProductSaveDTO {

        @Schema(description = "产品编码，唯一", example = "PRD-DB-X3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "产品编码不能为空")
        @Size(max = 64, message = "产品编码不能超过 64 字符")
        private String productCode;

        @Schema(description = "产品名称", example = "智能电表 X3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "产品名称不能为空")
        private String productName;

        @Schema(description = "规格型号", example = "220V/5(80)A")
        private String spec;

        @Schema(description = "计量单位", example = "台", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "计量单位不能为空")
        private String unit;

        @Schema(description = "产品分类（成品/半成品）", example = "成品")
        private String category;

        @Schema(description = "安全库存", example = "20")
        @DecimalMin(value = "0", message = "安全库存不能为负数")
        private BigDecimal safetyStock = BigDecimal.ZERO;

        @Schema(description = "状态 1-启用 0-停用", example = "1")
        private Integer status = 1;

        @Schema(description = "备注")
        private String remark;
    }

    /* ==================== 物料 ==================== */

    @Data
    @Schema(description = "物料创建/更新请求")
    public static class MaterialSaveDTO {

        @Schema(description = "物料编码，唯一", example = "MAT-MCU-02", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "物料编码不能为空")
        private String materialCode;

        @Schema(description = "物料名称", example = "计量芯片 MCU-B", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "物料名称不能为空")
        private String materialName;

        private String spec;

        @Schema(description = "单位", example = "颗", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "单位不能为空")
        private String unit;

        @Schema(description = "类型 RAW-原料 AUX-辅料 PACK-包装", example = "RAW")
        private String materialType = "RAW";

        @DecimalMin(value = "0", message = "安全库存不能为负数")
        private BigDecimal safetyStock = BigDecimal.ZERO;

        @Schema(description = "是否批次管理 1-是 0-否", example = "1")
        private Integer isBatchManaged = 1;

        @Schema(description = "状态 1-启用 0-停用", example = "1")
        private Integer status = 1;

        private String remark;
    }

    /* ==================== BOM ==================== */

    @Data
    @Schema(description = "BOM 创建请求（含明细，创建后为草稿）")
    public static class BomCreateDTO {

        @Schema(description = "产品 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "产品 ID 不能为空")
        private Long productId;

        @Schema(description = "版本号，为空时自动生成 V1/V2…", example = "V2")
        private String bomVersion;

        @Schema(description = "变更说明（为什么升版本）")
        private String remark;

        @Schema(description = "BOM 明细", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "BOM 明细不能为空")
        @Size(min = 1, message = "BOM 至少需要一条明细")
        @Valid
        private List<BomItemDTO> items;
    }

    @Data
    @Schema(description = "BOM 明细行")
    public static class BomItemDTO {

        @Schema(description = "物料 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "物料 ID 不能为空")
        private Long materialId;

        @Schema(description = "单位用量", example = "1.0000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "单位用量不能为空")
        @DecimalMin(value = "0.0001", message = "单位用量必须大于 0")
        private BigDecimal quantity;

        @Schema(description = "损耗率 0.02 = 2%", example = "0.02")
        @DecimalMin(value = "0", message = "损耗率不能为负数")
        private BigDecimal lossRate = BigDecimal.ZERO;

        @Schema(description = "序号", example = "1")
        private Integer seqNo;

        private String remark;
    }

    @Data
    @Schema(description = "BOM 明细视图（含物料快照信息）")
    public static class BomItemVO {
        private Long id;
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String materialSpec;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal lossRate;
        private Integer seqNo;
        private String remark;
    }

    @Data
    @Schema(description = "BOM 详情")
    public static class BomVO {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private String bomVersion;
        private Integer status;
        private String statusDesc;
        private LocalDate effectiveDate;
        private String remark;
        private List<BomItemVO> items;
    }

    /* ==================== BOM 树 ==================== */

    @Data
    @Schema(description = "BOM 树节点（多层展开）")
    public static class BomTreeNode {
        @Schema(description = "层级，根为 1")
        private int level;
        @Schema(description = "节点类型 PRODUCT-产品（可继续展开） MATERIAL-物料（叶子）")
        private String nodeType;
        @Schema(description = "产品 ID（半成品节点有值）")
        private Long productId;
        @Schema(description = "物料 ID（物料节点有值）")
        private Long materialId;
        private String code;
        private String name;
        private String spec;
        private String unit;
        @Schema(description = "本层单位用量")
        private BigDecimal quantity;
        @Schema(description = "本层损耗率")
        private BigDecimal lossRate;
        @Schema(description = "折算到根的累计用量 = 父路径用量乘积")
        private BigDecimal totalQuantity;
        @Schema(description = "是否为半成品（还有下级 BOM）")
        private boolean hasChildren;
        private List<BomTreeNode> children;
    }

    /* ==================== 工艺路线 ==================== */

    @Data
    @Schema(description = "工艺路线创建请求（含工序）")
    public static class RouteCreateDTO {

        @NotNull(message = "产品 ID 不能为空")
        private Long productId;

        @Schema(description = "版本号，为空时自动生成")
        private String routeVersion;

        private String remark;

        @NotNull(message = "工序列表不能为空")
        @Size(min = 1, message = "工艺路线至少需要一道工序")
        @Valid
        private List<StepDTO> steps;
    }

    @Data
    @Schema(description = "工序定义")
    public static class StepDTO {

        @Schema(description = "工序号，建议 10/20/30 留间隔", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "工序号不能为空")
        private Integer stepNo;

        @Schema(description = "工序名称", example = "外壳组装", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "工序名称不能为空")
        private String stepName;

        @Schema(description = "工作中心", example = "装配一车间")
        private String workCenter;

        @Schema(description = "标准工时(小时)", example = "0.25")
        @DecimalMin(value = "0", message = "标准工时不能为负数")
        private BigDecimal standardHours = BigDecimal.ZERO;

        @Schema(description = "是否质检点 1-是 0-否", example = "0")
        private Integer isInspection = 0;

        private String description;
    }

    @Data
    @Schema(description = "工艺路线详情")
    public static class RouteVO {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private String routeVersion;
        private Integer status;
        private String statusDesc;
        private LocalDate effectiveDate;
        private String remark;
        private List<StepVO> steps;
    }

    @Data
    @Schema(description = "工序视图")
    public static class StepVO {
        private Long id;
        private Integer stepNo;
        private String stepName;
        private String workCenter;
        private BigDecimal standardHours;
        private Integer isInspection;
        private String description;
    }

    /* ==================== 供其他服务（Feign）调用的轻量视图 ==================== */

    /**
     * BOM 需求素材：生产服务用它计算工单物料需求。
     */
    @Data
    @Schema(description = "BOM 物料需求素材（跨服务）")
    public static class BomRequirementItem {
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String spec;
        private String unit;
        @Schema(description = "单位用量")
        private BigDecimal quantity;
        @Schema(description = "损耗率")
        private BigDecimal lossRate;
        private Integer isBatchManaged;
    }

    @Data
    @Schema(description = "产品基础信息（跨服务）")
    public static class ProductBrief {
        private Long id;
        private String productCode;
        private String productName;
        private String spec;
        private String unit;
        private String category;
        private Integer status;
    }
}
