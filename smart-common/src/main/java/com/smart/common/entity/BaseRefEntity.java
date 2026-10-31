package com.smart.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 基础资料基类（无逻辑删除字段）。
 *
 * <p>为什么基础资料用物理删除：
 * 需求文档 3.0 原则 6 —— 逻辑删除与唯一索引冲突。
 * 产品编码 uk_product_code 如果在删除后仍占位，重建同编码会报唯一键冲突；
 * 把 is_deleted 加入唯一索引又会导致"删除两次就冲突"。
 * 基础资料（产品/物料）没有历史单据依赖其软删除语义（单据里存的是快照 + id），
 * 因此直接物理删除，由外键业务校验（存在工单引用时禁止删除）兜底。
 */
@Data
public abstract class BaseRefEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
}
