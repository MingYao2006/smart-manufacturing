package com.smart.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次表。统一管理物料批次与产品批次，是追溯图的 <b>节点</b>。
 *
 * <h3>为什么物料批次和产品批次用一张表 + batchType</h3>
 * <ul>
 *   <li>结构高度相似（批次号/数量/状态）</li>
 *   <li>追溯时节点类型必须统一，BFS 才能通用</li>
 *   <li>代价：某些字段对某类型为 null（supplierId 只对物料批次有意义）</li>
 * </ul>
 * 这是<b>有意的取舍</b>：表少、代码统一；如果分开建表，追溯代码要写两套。
 *
 * <p>🔑 {@code supplierId} 是物料批次的<b>追溯源头</b>。
 * 没有它，追溯到物料批次就断了 —— 而质量事故的终点往往就是"哪个供应商的哪批料"。
 */
@Data
@TableName("product_batch")
public class ProductBatch {

    public static final int TYPE_MATERIAL = 1;
    public static final int TYPE_PRODUCT = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批次号（唯一）MB…(物料) / PB…(产品) */
    private String batchNo;

    /** 1-物料批次 2-产品批次 */
    private Integer batchType;

    private Long materialId;

    private String materialCode;

    private String materialName;

    private Long productId;

    private String productCode;

    private String productName;

    /** 产品批次关联工单 */
    private Long productionOrderId;

    private String orderNo;

    private BigDecimal quantity;

    private BigDecimal qualifiedQuantity;

    private BigDecimal defectQuantity;

    /** 🔑 供应商（物料批次的追溯源头） */
    private Long supplierId;

    private String supplierName;

    private LocalDateTime inboundTime;

    /** 0-未检 1-合格 2-不合格 3-让步接收 */
    private Integer qualityStatus;

    /** 1-在库 2-已消耗 3-已用完 4-已报废 */
    private Integer status;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
