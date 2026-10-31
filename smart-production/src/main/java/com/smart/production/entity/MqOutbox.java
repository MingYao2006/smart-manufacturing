package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（outbox）。
 *
 * <h3>解决什么问题</h3>
 * "业务数据提交成功 → 发 MQ 前宕机" 会导致消息永久丢失。
 * outbox 把"消息"当成一条<b>和业务数据同事务</b>写入的记录，
 * 事务提交即代表"消息一定会被投递"（由定时任务补偿）。
 *
 * <h3>状态机</h3>
 * <pre>
 *   0 待投递 → 1 已投递（收到 publisher confirm 的 ack）
 *   0 待投递 → 2 投递失败（重试耗尽，终态，需要人工介入/告警）
 *   0 待投递 → 3 路由失败（Broker 收到了但没路由到队列，重置回 0 重试）
 * </pre>
 *
 * <p>⚠️ 为什么必须等 publisher confirm：
 * {@code rabbitTemplate.send()} 只是把字节写进 TCP 缓冲，不代表 Broker 收到了。
 * 只有 confirm 的 ack 才能证明消息进了 Broker。
 */
@Data
@TableName("mq_outbox")
public class MqOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_RETURNED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务幂等键，如 report:123:order-progress */
    private String bizKey;

    private String exchangeName;

    private String routingKey;

    private String payload;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime sentTime;
}
