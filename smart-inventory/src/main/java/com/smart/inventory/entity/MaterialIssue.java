package com.smart.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领料单。一次领料动作的凭证，预留转为实际消耗。
 *
 * <p>为什么领料要独立成单而不是直接改库存：
 * <ol>
 *   <li><b>凭证</b>：领料是仓库与车间之间的物料转移，必须有单据</li>
 *   <li><b>批量</b>：一次领料可能涉及多个物料</li>
 *   <li><b>追溯</b>：出问题能查"谁在什么时候领了什么"</li>
 *   <li><b>幂等</b>：{@code issueNo} 唯一索引防重复领料</li>
 * </ol>
 */
@Data
@TableName("material_issue")
public class MaterialIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 领料单号（幂等键） */
    private String issueNo;

    private Long orderId;

    private String orderNo;

    private Long warehouseId;

    /** 1-领料 2-退料 */
    private Integer issueType;

    /** 1-草稿 2-已确认 3-已作废 */
    private Integer status;

    private BigDecimal totalQuantity;

    private LocalDateTime issueTime;

    private Long operatorId;

    private String operatorName;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
