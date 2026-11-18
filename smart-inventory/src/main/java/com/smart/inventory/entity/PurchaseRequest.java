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
 * 进货审批单（补料申请）。
 *
 * <p>业务链路：仓管发现库存不够 → 自己提交进货审批单 → 管理员 / 计划员<b>或签</b>审批
 * （任意一人通过即生效）→ 审批通过时在同一事务内自动入库 → 库存 quantity / 可用量变化
 * → 库存态势（基于可用量与安全库存计算）随之刷新。
 *
 * <h3>状态机</h3>
 * <pre>
 *   1 待审批 ──通过──▶ 2 已通过（已入库，终态）
 *            ──驳回──▶ 3 已驳回（终态）
 *            ──撤销──▶ 4 已撤销（终态）
 * </pre>
 *
 * <h3>并发防线</h3>
 * 审批动作走条件更新 {@code UPDATE ... SET status=2 WHERE id=? AND status=1}，
 * 只有第一个请求能把 1→2；影响行数=0 说明单据已被处理（并发或重复点击），直接拒绝，
 * 从而保证「审批通过」与「自动入库」只发生一次，不会重复加库存。
 */
@Data
@TableName("purchase_request")
public class PurchaseRequest {

    /** 待审批 */
    public static final int STATUS_PENDING = 1;
    /** 已通过（已入库） */
    public static final int STATUS_APPROVED = 2;
    /** 已驳回 */
    public static final int STATUS_REJECTED = 3;
    /** 已撤销 */
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 进货审批单号 */
    private String requestNo;

    private Long materialId;

    private String materialCode;

    private String materialName;

    private String unit;

    private Long warehouseId;

    /** 申请进货数量 */
    private BigDecimal quantity;

    /** 入库批次号（为空时审批通过由服务端生成） */
    private String batchNo;

    /** 申请原因 */
    private String reason;

    private String supplierName;

    /** 1-待审批 2-已通过 3-已驳回 4-已撤销 */
    private Integer status;

    private Long applicantId;

    private String applicantName;

    private String applicantRole;

    private Long approverId;

    private String approverName;

    private String approverRole;

    private String approveComment;

    private LocalDateTime approveTime;

    /** 审批通过时写入的库存流水 ID（审计链路） */
    private Long inboundTxId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public static String statusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case STATUS_PENDING -> "待审批";
            case STATUS_APPROVED -> "已通过·已入库";
            case STATUS_REJECTED -> "已驳回";
            case STATUS_CANCELLED -> "已撤销";
            default -> "未知(" + status + ")";
        };
    }
}
