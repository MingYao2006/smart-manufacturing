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
 * 库存流水（账本）。<b>只增不改不删</b>。
 *
 * <h3>为什么记 before/after 而不只记变动量</h3>
 * <ol>
 *   <li><b>对账</b>：末条流水的 after 应等于库存表当前 quantity，不等就是异常</li>
 *   <li><b>排查并发 bug</b>：如果两条流水的 before 相同，说明有并发覆盖问题</li>
 *   <li><b>审计</b>：任何时刻的库存状态都可以从流水重建</li>
 * </ol>
 *
 * <p>为什么 {@code batchNo} 是追溯链路的起点：
 * 物料入库生成批次 → 领料记录领了哪个批次 → 完工生成产品批次 → batch_relation 连起来。
 * 没有它，追溯就断了。
 *
 * <p>为什么不许 UPDATE/DELETE：账本一旦可改，审计价值就没了。
 * 发现错误要用"反向冲正"（再记一条相反方向的流水），而不是修改历史。
 */
@Data
@TableName("inventory_transaction")
public class InventoryTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String transactionNo;

    private Long materialId;

    private String materialCode;

    private Long warehouseId;

    /** 批次号（批次管理物料必填），追溯起点 */
    private String batchNo;

    /** 1-入库 2-出库 3-预留 4-释放预留 5-退料 6-盘点调整 */
    private Integer transactionType;

    /** 变动量（正负号表示方向） */
    private BigDecimal quantity;

    private BigDecimal beforeQuantity;

    private BigDecimal afterQuantity;

    private BigDecimal beforeReserved;

    private BigDecimal afterReserved;

    private String bizType;

    private String bizNo;

    private Long operatorId;

    private String operatorName;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
