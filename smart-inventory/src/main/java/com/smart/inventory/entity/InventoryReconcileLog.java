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
 * 库存对账日志。对账是分布式方案的最后防线。
 */
@Data
@TableName("inventory_reconcile_log")
public class InventoryReconcileLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;

    private Long warehouseId;

    private BigDecimal inventoryQty;

    private BigDecimal ledgerQty;

    private BigDecimal diff;

    private BigDecimal inventoryReserved;

    private BigDecimal expectedReserved;

    private String abnormalType;

    private Integer handleStatus;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
