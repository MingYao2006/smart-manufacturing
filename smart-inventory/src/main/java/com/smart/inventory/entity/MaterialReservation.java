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
 * 物料预留。"预留与实扣两段式"的核心。
 *
 * <h3>状态机</h3>
 * <pre>
 *   RESERVED(1) ──部分领料──> PARTIAL_ISSUED(2) ──继续领──> PARTIAL_ISSUED
 *        │                            │
 *        └──────一次领完──────────────┴──> ISSUED(3)
 *        │                            │
 *        └────工单取消/过期───────────┴──> RELEASED(4)
 * </pre>
 *
 * <p>🔑 {@code issuedQuantity} 的存在意义：
 * 预留 100 领了 30，还剩 70 待领。领料时要校验
 * {@code 领用量 <= reservedQuantity - issuedQuantity}。
 * 没有这个字段就无法判断预留是否已用尽。
 *
 * <p>🔑 {@code expireTime} 是"预留泄漏"的兜底：
 * 工单取消/长期不生产时预留必须释放，否则库存被永久锁死。
 * 定时任务扫 {@code status IN (1,2) AND expireTime < NOW()} 释放。
 */
@Data
@TableName("material_reservation")
public class MaterialReservation {

    public static final int STATUS_RESERVED = 1;
    public static final int STATUS_PARTIAL_ISSUED = 2;
    public static final int STATUS_ISSUED = 3;
    public static final int STATUS_RELEASED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reservationNo;

    private Long orderId;

    private String orderNo;

    private Long materialId;

    private String materialCode;

    private String materialName;

    private Long warehouseId;

    /** 预留数量 */
    private BigDecimal reservedQuantity;

    /** 已领用数量 */
    private BigDecimal issuedQuantity;

    /** 1-已预留 2-部分领用 3-全部领用 4-已释放 */
    private Integer status;

    /** 预留过期时间，防预留泄漏 */
    private LocalDateTime expireTime;

    private LocalDateTime releasedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 剩余待领量 = 预留量 - 已领量。
     */
    public BigDecimal remainingQuantity() {
        BigDecimal r = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        BigDecimal i = issuedQuantity == null ? BigDecimal.ZERO : issuedQuantity;
        return r.subtract(i);
    }
}
