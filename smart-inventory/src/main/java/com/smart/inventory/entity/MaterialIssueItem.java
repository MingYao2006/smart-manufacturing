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
 * 领料明细。
 *
 * <p>⚠️ <b>这张表是批次追溯的关键</b>：它记录"领了哪个批次"。
 * 原表清单里没有这张表，但没有它，正向/反向追溯都做不了 ——
 * 因为一次领料可能领多个物料、多个批次，主表无法表达这种多对多关系。
 */
@Data
@TableName("material_issue_item")
public class MaterialIssueItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long issueId;

    private Long materialId;

    private String materialCode;

    private String materialName;

    /** 🔑 领用的批次号（追溯关键） */
    private String batchNo;

    private BigDecimal quantity;

    private Long reservationId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
