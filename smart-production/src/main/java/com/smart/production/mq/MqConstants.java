package com.smart.production.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MQ 拓扑常量（交换机/队列/路由键）。
 *
 * <p>集中定义，避免各处的字符串写错导致"路由失败"这种最难排查的问题。
 */
public final class MqConstants {

    private MqConstants() {
    }

    /** 生产域主交换机 */
    public static final String EXCHANGE = "production.exchange";

    /** 报工完成事件路由键 */
    public static final String RK_REPORT_COMPLETED = "report.completed";

    /** 报工完成队列 */
    public static final String QUEUE_REPORT_COMPLETED = "production.report.completed.queue";

    /** 工单完工事件路由键（预留） */
    public static final String RK_ORDER_COMPLETED = "order.completed";
    public static final String QUEUE_ORDER_COMPLETED = "production.order.completed.queue";

    /** 死信交换机与队列 */
    public static final String DLX_EXCHANGE = "production.dlx";
    public static final String QUEUE_DLQ = "production.dead-letter.queue";
    public static final String RK_DLQ = "dead.letter";

    /**
     * 报工完成事件（消息体）。
     *
     * <p>消费者用 {@code orderId} 重算进度。
     * 注意消息体只带<b>标识</b>不带"增量"：增量累加无法幂等，重算才可以。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportCompletedEvent {

        private Long reportId;
        private String reportNo;
        private Long orderId;
        private String orderNo;
        private Long taskId;
        private Integer stepNo;
        private BigDecimal reportQuantity;
        private BigDecimal qualifiedQuantity;
        private BigDecimal defectQuantity;
        private LocalDateTime reportTime;
        /** 消息唯一 ID，用于消费幂等与日志追踪 */
        private String messageId;
    }
}
