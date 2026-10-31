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
 * BOM 明细。记录每一行——用什么物料、用多少、损耗多少。
 *
 * <p>🔑 {@code lossRate} 是 MES 与电商最直观的差异：
 * 电商"买 1 件商品"就是 1 件；制造业"生产 1 个产品"可能需要 1.02 份原料。
 * <pre>需求量 = 单位用量 × 计划数量 × (1 + 损耗率)</pre>
 * 没有这个字段，物料需求会算少，导致生产中途缺料停线。
 */
@Data
@TableName("bom_item")
public class BomItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bomId;

    private Long materialId;

    /** 单位用量（生产 1 个产品需要多少该物料），支持小数 */
    private BigDecimal quantity;

    /** 损耗率 0.02 = 2% */
    private BigDecimal lossRate;

    private Integer seqNo;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
