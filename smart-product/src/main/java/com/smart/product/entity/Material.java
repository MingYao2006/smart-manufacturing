package com.smart.product.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.common.entity.BaseRefEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 物料实体。定义"用什么生产"，是库存与领料的主体。
 *
 * <p>关键字段 {@code isBatchManaged}：不是所有物料都需要批次追溯。
 * 螺丝钉按批次追溯没有意义，但芯片必须。这个字段决定领料时是否强制选批次，
 * 也决定追溯链路的完整性。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("material")
public class Material extends BaseRefEntity {

    private String materialCode;
    private String materialName;
    private String spec;
    private String unit;

    /** RAW-原料 AUX-辅料 PACK-包装 */
    private String materialType;

    private BigDecimal safetyStock;

    /** 是否批次管理 1-是 0-否 */
    private Integer isBatchManaged;

    /** 1-启用 0-停用 */
    private Integer status;

    private String remark;
}
