package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费幂等记录。
 *
 * <p>用于"有副作用"的消费场景（不能靠重算实现幂等的那一类）。
 * 相比 Redis SETNX，数据库唯一索引不会因 Redis 丢 key / 过期而失效。
 *
 * <p>⚠️ 关键原则：<b>幂等键只能挡住"该跳过的重复"，不能挡死"该重试的失败"。</b>
 * 所以处理失败时必须删掉这条记录（或用事务保证未提交就不会留下记录）。
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String consumeKey;

    private String topic;

    private String msgId;

    private String result;

    private LocalDateTime createTime;
}
