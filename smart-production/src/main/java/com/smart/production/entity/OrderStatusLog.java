package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.production.statemachine.ProductionOrderStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单状态流转日志（只增不改）。
 *
 * <p>作用：谁、何时、从什么状态到什么状态、为什么。
 * 这是状态机"可追溯"的保证，出问题时能还原完整历史。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Integer fromStatus;

    private String fromStatusDesc;

    private Integer toStatus;

    private String toStatusDesc;

    private String reason;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operateTime;

    public static OrderStatusLog of(Long orderId, String orderNo,
                                    ProductionOrderStatus from, ProductionOrderStatus to,
                                    String reason, Long operatorId, String operatorName) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setOrderNo(orderNo);
        log.setFromStatus(from == null ? null : from.getCode());
        log.setFromStatusDesc(from == null ? "创建" : from.getDesc());
        log.setToStatus(to.getCode());
        log.setToStatusDesc(to.getDesc());
        log.setReason(reason);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setOperateTime(LocalDateTime.now());
        return log;
    }
}
