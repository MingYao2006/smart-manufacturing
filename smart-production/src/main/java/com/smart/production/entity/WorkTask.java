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
 * 工序任务。工单下达后按工艺路线展开的可执行任务单元，工人实际操作的对象。
 *
 * <p>🔑 {@code reportedQuantity} 存在表里而不是每次 {@code SUM(报工表)}：
 * 超报校验需要原子性。如果每次查 SUM 再判断，两个并发请求会同时通过校验。
 * 存字段 + 条件更新 {@code WHERE reported_quantity + ? <= plan_quantity} 才能保证并发安全。
 * 这是"冗余换并发安全"的典型取舍。
 *
 * <p>🔑 {@code uk_order_step(order_id, step_no)} 是防重复生成工序任务的最后一道防线：
 * 工单重复下达或 MQ 消息重投时，没有它会生成两套任务 → 报工翻倍。
 */
@Data
@TableName("work_task")
public class WorkTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long productId;

    /** 工序号（快照），决定执行顺序 */
    private Integer stepNo;

    private String stepName;

    private String workCenter;

    private BigDecimal standardHours;

    /** 计划数量（快照自工单） */
    private BigDecimal planQuantity;

    /** 累计已报工数量，用于超报校验 */
    private BigDecimal reportedQuantity;

    /** 累计合格数 */
    private BigDecimal qualifiedQuantity;

    /** 累计不良数 */
    private BigDecimal defectQuantity;

    /** 实际工时（报工累加） */
    private BigDecimal actualHours;

    /** 状态，见 WorkTaskStatus */
    private Integer status;

    /** 是否质检点（快照） */
    private Integer isInspection;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

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
