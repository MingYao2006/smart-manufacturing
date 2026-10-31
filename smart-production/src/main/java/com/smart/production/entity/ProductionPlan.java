package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生产计划。计划部门"打算生产什么、什么时候要"，是工单的来源。
 *
 * <p>计划与工单是 1:N：实际生产中一个 1000 件计划常拆成多个批次（每次 200 件），
 * 因为设备/物料/人力限制。
 */
@Data
@TableName("production_plan")
public class ProductionPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planNo;

    private Long productId;

    /** 产品编码快照（反范式，避免跨库查） */
    private String productCode;

    private String productName;

    private BigDecimal planQuantity;

    private LocalDate planStartDate;

    /** 交期，排产依据，也是工单优先级判断标准 */
    private LocalDate planEndDate;

    /** 1-草稿 2-已确认 3-已转工单 4-已取消 */
    private Integer status;

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
