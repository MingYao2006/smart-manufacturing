package com.smart.production.controller;

import com.smart.common.result.Result;
import com.smart.production.mq.MqConstants;
import com.smart.production.service.OutboxSenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * outbox 消息运维接口（第 5 周任务 5.6 / 第 7 周消息可靠性测试用）。
 *
 * <p>提供了"手动投放同一条消息 N 次"的能力，用于验证消费幂等：
 * 投 3 次，工单进度只应更新一次（因为进度是重算而非累加）。
 */
@Tag(name = "14-消息可靠性运维")
@RestController
@RequestMapping("/mq")
@RequiredArgsConstructor
public class MqOpsController {

    private final OutboxSenderService outboxSender;

    @Operation(summary = "查看 outbox 状态（待投递/投递失败数量）")
    @GetMapping("/outbox/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(outboxSender.stats());
    }

    @Operation(summary = "手动补偿：把投递失败的消息重置为待投递")
    @PostMapping("/outbox/requeue-failed")
    public Result<Integer> requeueFailed() {
        return Result.ok(outboxSender.requeueFailed(), "已重置为待投递");
    }

    @Operation(summary = "手动触发一次 outbox 投递扫描")
    @PostMapping("/outbox/flush")
    public Result<Void> flush() {
        outboxSender.deliverPending();
        return Result.ok(null, "已触发投递");
    }

    @Operation(summary = "🔑 重复投递报工完成事件（验证消费幂等）",
            description = "同一事件投递 N 次，工单进度应只按最终数据算一次（重算幂等）")
    @PostMapping("/test/replay-report-event")
    public Result<String> replay(@RequestBody ReplayRequest req) {
        int times = req.getTimes() == null ? 3 : req.getTimes();
        String msgId = UUID.randomUUID().toString();
        for (int i = 0; i < times; i++) {
            MqConstants.ReportCompletedEvent event = new MqConstants.ReportCompletedEvent();
            event.setReportId(req.getReportId());
            event.setReportNo(req.getReportNo());
            event.setOrderId(req.getOrderId());
            event.setTaskId(req.getTaskId());
            event.setStepNo(req.getStepNo());
            event.setReportQuantity(req.getReportQuantity());
            event.setQualifiedQuantity(req.getQualifiedQuantity());
            event.setDefectQuantity(java.math.BigDecimal.ZERO);
            event.setReportTime(LocalDateTime.now());
            // 故意用同一个 messageId：模拟 MQ 重复投递同一条消息
            event.setMessageId(msgId);
            outboxSender.sendDirect(event);
        }
        return Result.ok("已投递 " + times + " 次，messageId=" + msgId);
    }

    /**
     * 重放请求。
     */
    @lombok.Data
    public static class ReplayRequest {
        private Long reportId;
        private String reportNo;
        private Long orderId;
        private Long taskId;
        private Integer stepNo;
        private java.math.BigDecimal reportQuantity;
        private java.math.BigDecimal qualifiedQuantity;
        /** 投递次数，默认 3 */
        private Integer times;
    }
}
