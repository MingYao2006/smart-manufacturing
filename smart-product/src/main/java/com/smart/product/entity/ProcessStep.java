package com.smart.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工序步骤。工艺路线的每一道工序，是 work_task 的模板。
 *
 * <p>🔑 {@code stepNo} 用 10/20/30 而不是 1/2/3：
 * 制造业惯例，留出插入空间。要在两道工序间加新工序，用 15 即可，
 * 不用重新编号所有后续工序。
 *
 * <p>🔑 {@code isInspection} 是核心字段：把"工序流转"和"质量检验"耦合起来。
 * 为 1 的工序完成后不是直接进下一道，而是先等质检合格。这个字段决定工作流分支。
 */
@Data
@TableName("process_step")
public class ProcessStep implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeId;

    /** 工序号 10/20/30，决定执行顺序 */
    private Integer stepNo;

    private String stepName;

    /** 工作中心（车间/产线），用于派工和产能统计 */
    private String workCenter;

    private BigDecimal standardHours;

    /** 是否质检点 1-是 0-否 */
    private Integer isInspection;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
