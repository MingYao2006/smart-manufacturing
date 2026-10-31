package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产工单实体。整个系统的核心单据，所有执行动作都挂在工单下。
 *
 * <p>🔑 最重要的设计：{@code bomId} 与 {@code routeId} 是<b>创建时锁定</b>的版本快照。
 * 之后 BOM 升级到 V2，这个工单仍然用 V1 计算物料需求。
 * 这是制造业铁律 —— 不这样做，历史工单的物料需求就无法追溯。
 *
 * <p>其余 {@code productCode / productName / spec / unit / bomVersion / routeVersion}
 * 都是<b>有意反范式</b>的快照字段：跨服务查询成本高，且历史单据不能被主数据变更影响。
 */
@Data
@TableName("production_order")
public class ProductionOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long planId;

    private Long productId;

    /** 产品编码快照 */
    private String productCode;

    /** 产品名称快照 */
    private String productName;

    private String spec;

    private String unit;

    /** 🔑 锁定的 BOM 版本 ID */
    private Long bomId;

    /** 锁定的 BOM 版本号快照 */
    private String bomVersion;

    /** 🔑 锁定的工艺路线版本 ID */
    private Long routeId;

    /** 锁定的工艺路线版本号快照 */
    private String routeVersion;

    private BigDecimal planQuantity;

    /** 已完工数量（由报工重算驱动，不手工维护） */
    private BigDecimal completedQuantity;

    /** 合格数量（由报工重算驱动） */
    private BigDecimal qualifiedQuantity;

    /** 不良数量（由报工重算驱动） */
    private BigDecimal defectQuantity;

    /** 进度百分比 0-100，按已完成工序数重算 */
    private Integer progress;

    /** 状态，见 ProductionOrderStatus */
    private Integer status;

    private LocalDateTime planStartTime;

    private LocalDateTime planEndTime;

    private LocalDateTime actualStartTime;

    private LocalDateTime actualEndTime;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic(value = "0", delval = "1")
    @TableField(select = false)
    private Integer isDeleted;
}
