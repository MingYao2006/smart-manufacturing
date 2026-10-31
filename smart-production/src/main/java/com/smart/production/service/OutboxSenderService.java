package com.smart.production.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.production.entity.MqOutbox;
import com.smart.production.mapper.MqOutboxMapper;
import com.smart.production.mq.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表投递服务（第 5 周任务 5.6 —— 消息可靠性，面试必问）。
 *
 * <h3>为什么需要 outbox</h3>
 * 直接 {@code rabbitTemplate.send()} 的问题：
 * <pre>
 *   ① 事务提交成功 → ② 发 MQ 前进程崩溃  → 消息永久丢失 → 工单永远不完工（业务事故）
 * </pre>
 * 解决：把"要发的消息"作为一条记录<b>和业务数据在同一事务里</b>写入 outbox 表。
 * 事务提交即代表消息一定会被投递（由本类的定时任务补偿）。
 *
 * <h3>为什么必须等 publisher confirm</h3>
 * {@code rabbitTemplate.send()} 只是把字节写进 <b>TCP 缓冲区</b>，
 * 不代表 Broker 收到了。只有 confirm 的 ack 才能证明消息进入了 Broker。
 * 所以这里用 {@code CorrelationData.getFuture()} 同步等待 confirm。
 *
 * <h3>路由失败的隐蔽坑</h3>
 * 消息到了交换机但没有匹配的队列时，Broker 的 confirm 依然返回 <b>ack</b>（它确实收到了），
 * 但消息被<b>丢弃</b>了。如果不处理 returns 回调，outbox 会被标记"已投递"，
 * 而实际没有任何消费者收到 → 消息静默丢失。所以必须开启 mandatory 并处理退回。
 *
 * <p>本类实现 {@link MessagePublisher} 接口：业务层依赖抽象而非实现，
 * 单元测试可用内存 Fake 验证"报工成功必须写 outbox"（见第 7 周测试要求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxSenderService implements MessagePublisher {

    private final MqOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${smart.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${smart.mq.max-retry:5}")
    private int maxRetry;

    @Value("${smart.mq.confirm-timeout-seconds:5}")
    private long confirmTimeoutSeconds;

    /**
     * 在业务事务内写入 outbox（由调用方的 @Transactional 保证原子性）。
     *
     * <p>用 INSERT IGNORE + 唯一索引保证同一条业务事件只写一条（幂等）。
     */
    @Override
    public void enqueue(String bizKey, String exchangeName, String routingKey, Object payload) {
        try {
            MqOutbox outbox = new MqOutbox();
            outbox.setBizKey(bizKey);
            outbox.setExchangeName(exchangeName);
            outbox.setRoutingKey(routingKey);
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            int n = outboxMapper.insertIgnore(outbox);
            if (n > 0) {
                log.debug("[outbox 入队] bizKey={} routingKey={}", bizKey, routingKey);
            } else {
                log.debug("[outbox 入队] 已存在，跳过 bizKey={}", bizKey);
            }
        } catch (Exception e) {
            // ⚠️ 不能让 outbox 写失败导致业务回滚：业务数据一致性优先，
            // 消息丢了由定时兜底扫描（repairStuckOrders）修复
            log.error("[outbox 入队失败] bizKey={}", bizKey, e);
        }
    }

    /**
     * 定时投递待发送消息。
     *
     * <p>{@code fixedDelay=3000}：上一次执行结束后 3 秒再跑，避免堆积。
     * 多实例部署时应加分布式锁选主，本项目单机演示未加。
     */
    @Scheduled(fixedDelayString = "${smart.mq.outbox-delay-ms:3000}",
            initialDelayString = "${smart.mq.outbox-initial-delay-ms:10000}")
    public void deliverPending() {
        if (!mqEnabled) {
            return;
        }
        List<MqOutbox> pending;
        try {
            pending = outboxMapper.selectPending(50);
        } catch (Exception e) {
            log.warn("[outbox 扫描失败] {}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        log.debug("[outbox 投递] 待投递 {} 条", pending.size());
        for (MqOutbox msg : pending) {
            sendOne(msg);
        }
    }

    /**
     * 投递单条消息并等待 confirm。
     */
    private void sendOne(MqOutbox msg) {
        try {
            Message message = MessageBuilder
                    .withBody(msg.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(msg.getBizKey())
                    .setHeader("outboxId", msg.getId())
                    .setHeader("bizKey", msg.getBizKey())
                    .build();

            CorrelationData correlation = new CorrelationData(String.valueOf(msg.getId()));
            rabbitTemplate.send(msg.getExchangeName(), msg.getRoutingKey(), message, correlation);

            // 🔑 必须等 confirm：send 只是写进 TCP 缓冲，不代表 Broker 收到了
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutSeconds, TimeUnit.SECONDS);

            if (confirm.isAck()) {
                outboxMapper.markSent(msg.getId());
                log.info("[outbox 投递成功] id={} bizKey={} routingKey={}",
                        msg.getId(), msg.getBizKey(), msg.getRoutingKey());
            } else {
                String reason = confirm.getReason();
                log.warn("[outbox 投递被 nack] id={} reason={}", msg.getId(), reason);
                outboxMapper.markRetry(msg.getId(), "nack: " + reason, maxRetry);
            }
        } catch (Exception e) {
            // MQ 不可用 / 超时：保留记录，指数退避重试
            log.warn("[outbox 投递异常] id={} bizKey={} err={}",
                    msg.getId(), msg.getBizKey(), e.getMessage());
            try {
                outboxMapper.markRetry(msg.getId(), truncate(e.getMessage()), maxRetry);
            } catch (Exception ex) {
                log.error("[outbox 标记重试失败] id={}", msg.getId(), ex);
            }
        }
    }

    /**
     * 告警：投递失败（终态）的消息数量。
     *
     * <p>"所有失败都要可观测" —— 不能静默。生产环境这里应接钉钉/邮件告警。
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void alertFailedOutbox() {
        try {
            long failed = outboxMapper.countFailed();
            long pending = outboxMapper.countPending();
            if (failed > 0) {
                log.error("[告警] 有 {} 条消息投递失败（终态），需人工介入；待投递 {} 条", failed, pending);
            } else if (pending > 0) {
                log.info("[outbox 状态] 待投递 {} 条，无失败", pending);
            }
        } catch (Exception e) {
            log.debug("[outbox 告警检查跳过] {}", e.getMessage());
        }
    }

    /**
     * 手动补偿：把失败的消息重新置为待投递。
     */
    public int requeueFailed() {
        List<MqOutbox> failed = outboxMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MqOutbox>()
                        .eq(MqOutbox::getStatus, MqOutbox.STATUS_FAILED));
        for (MqOutbox m : failed) {
            m.setStatus(MqOutbox.STATUS_PENDING);
            m.setRetryCount(0);
            m.setNextRetryTime(null);
            outboxMapper.updateById(m);
        }
        log.info("[outbox 手动补偿] 重置 {} 条失败消息", failed.size());
        return failed.size();
    }

    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("pending", outboxMapper.countPending());
        m.put("failed", outboxMapper.countFailed());
        m.put("mqEnabled", mqEnabled);
        return m;
    }

    /**
     * 直接投递报工完成事件（不经 outbox，用于测试/重放）。
     */
    public void sendDirect(MqConstants.ReportCompletedEvent event) {
        rabbitTemplate.convertAndSend(MqConstants.EXCHANGE, MqConstants.RK_REPORT_COMPLETED, event);
        log.info("[直接投递] msgId={} orderId={}", event.getMessageId(), event.getOrderId());
    }

    private String truncate(String s) {
        if (s == null) {
            return "unknown";
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
