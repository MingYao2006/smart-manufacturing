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
 * 物料库存。本项目的<b>并发控制核心表</b>。
 *
 * <h3>🔑 三量模型</h3>
 * <pre>
 *   quantity          现有量（仓库里实际有多少）
 *   reservedQuantity  预留量（被工单锁定但还没领走）
 *   可用量 = quantity - reservedQuantity   ← 计算值，【不存字段】
 * </pre>
 *
 * <p>为什么可用量不存字段：存了就会有"quantity - reserved ≠ available"的不一致，
 * 而这类不一致在生产环境极难排查。计算值永远不会自相矛盾。
 *
 * <p>为什么必须有 {@code uk_material_warehouse(material_id, warehouse_id)}：
 * 所有条件更新都依赖"单行记录"。若同物料同仓库有多条，
 * {@code UPDATE ... WHERE material_id=? AND warehouse_id=?} 会命中多行，
 * 影响行数判定（=1）就失效了。
 */
@Data
@TableName("material_inventory")
public class MaterialInventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;

    private String materialCode;

    private String materialName;

    private String unit;

    private Long warehouseId;

    /** 现有量 */
    private BigDecimal quantity;

    /** 预留量 */
    private BigDecimal reservedQuantity;

    private BigDecimal safetyStock;

    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 可用量 = 现有量 - 预留量（计算属性，不落库）。
     */
    public BigDecimal availableQuantity() {
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal r = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        return q.subtract(r);
    }
}
