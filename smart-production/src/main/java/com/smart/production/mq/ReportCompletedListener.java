package com.smart.production.mq;

import com.smart.production.service.ProductionOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 报工完成事件消费者（第 5 周任务 5.5 / 5.6）。
 *
 * <h3>幂等策略：重算，而不是去重</h3>
 * {@link ProductionOrderService#recalculateProgress} 是<b>幂等运算</b>：
 * 进度由当前工序/报工数据<b>重新计算</b>得出，不是 {@code += 增量}。
 * 所以同一条消息重复消费 3 次，结果完全一致 —— 不需要 SETNX 去重。
 *
 * <p>这是本项目优先采用的幂等模式（模式 B），因为它：
 * <ul>
 *   <li>不依赖 Redis（少一个故障点）</li>
 *   <li>天然幂等，不存在"键过期后重复处理"的问题</li>
 *   <li>数据自愈：即使丢了消息，定时兜底扫描也能把进度算对</li>
 * </ul>
 *
 * <h3>失败处理</h3>
 * 抛异常 → Spring AMQP 自动重试（max-attempts=3）→ 耗尽后进死信队列（DLQ）。
 * 不吞异常：吞掉就永远没人知道失败了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "smart.mq.enabled", havingValue = "true", matchIfMissing = true)
public class ReportCompletedListener {

    private final ProductionOrderService orderService;

    @RabbitListener(queues = MqConstants.QUEUE_REPORT_COMPLETED, containerFactory = "manualAckContainerFactory")
    public void onReportCompleted(MqConstants.ReportCompletedEvent event, Map<String, Object> headers) {
        String traceId = headers == null ? null : String.valueOf(headers.get("traceId"));
        String oldTrace = MDC.get("traceId");
        if (traceId != null && !"null".equals(traceId)) {
            MDC.put("traceId", traceId);
        }
        try {
            log.info("[消费报工完成事件] orderId={} taskId={} qty={} msgId={}",
                    event.getOrderId(), event.getTaskId(), event.getReportQuantity(), event.getMessageId());
            // 幂等：重算，重复执行结果一致
            orderService.recalculateProgress(event.getOrderId());
        } finally {
            if (oldTrace != null) {
                MDC.put("traceId", oldTrace);
            } else {
                MDC.remove("traceId");
            }
        }
    }
}
