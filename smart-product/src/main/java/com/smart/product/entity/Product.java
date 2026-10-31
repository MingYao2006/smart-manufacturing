package com.smart.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.common.entity.BaseRefEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 产品实体。定义"能生产什么"，是 BOM / 工艺路线 / 工单的挂载点。
 *
 * <p>注意：继承 {@link BaseRefEntity}（无 is_deleted），基础资料用物理删除。
 * 原因见 docs/02-数据库设计.md 第 6 条原则（逻辑删除与唯一索引冲突）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class Product extends BaseRefEntity {

    private String productCode;
    private String productName;
    private String spec;
    private String unit;
    private String category;
    private BigDecimal safetyStock;

    /** 1-启用 0-停用 */
    private Integer status;

    private String remark;
}
