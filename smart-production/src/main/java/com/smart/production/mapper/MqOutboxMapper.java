package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.MqOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MqOutboxMapper extends BaseMapper<MqOutbox> {

    /**
     * 业务事务内写 outbox。
     *
     * <p>用 {@code INSERT IGNORE} + 唯一索引 {@code uk_outbox_biz(biz_key, exchange_name)}：
     * 同一条业务事件重复写入不会报错也不会产生重复消息（幂等）。
     */
    @Insert("INSERT IGNORE INTO mq_outbox (biz_key, exchange_name, routing_key, payload, status, "
            + "retry_count, next_retry_time, create_time, update_time) VALUES "
            + "(#{bizKey}, #{exchangeName}, #{routingKey}, #{payload}, 0, 0, NOW(), NOW(), NOW())")
    int insertIgnore(MqOutbox outbox);

    /**
     * 取待投递消息（定时任务用），只取到了重试时间的。
     */
    @Select("SELECT * FROM mq_outbox WHERE status = 0 AND (next_retry_time IS NULL OR next_retry_time <= NOW()) "
            + "ORDER BY id LIMIT #{limit}")
    List<MqOutbox> selectPending(@Param("limit") int limit);

    @Update("UPDATE mq_outbox SET status = 1, sent_time = NOW(), update_time = NOW() WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 投递失败：重试次数 +1，按指数退避设置下次重试时间；超过上限则置为终态失败。
     */
    @Update("UPDATE mq_outbox SET status = IF(retry_count + 1 >= #{maxRetry}, 2, 0), "
            + "retry_count = retry_count + 1, "
            + "next_retry_time = DATE_ADD(NOW(), INTERVAL LEAST(POW(2, retry_count) , 300) SECOND), "
            + "last_error = #{error}, update_time = NOW() WHERE id = #{id}")
    int markRetry(@Param("id") Long id,
                  @Param("error") String error,
                  @Param("maxRetry") int maxRetry);

    /**
     * 路由失败（Broker 收到但没队列接收）：确认消息被退回，重置为待投递。
     *
     * <p>关键：Broker 对这种情况的 confirm 是 <b>ack</b>（它确实收到了），
     * 如果不处理 returns 回调，outbox 会被标记为"已投递"但实际没人接收 → 消息静默丢失。
     */
    @Update("UPDATE mq_outbox SET status = 0, retry_count = retry_count + 1, "
            + "next_retry_time = DATE_ADD(NOW(), INTERVAL 30 SECOND), "
            + "last_error = #{error}, update_time = NOW() WHERE id = #{id} AND status = 1")
    int markReturned(@Param("id") Long id, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM mq_outbox WHERE status = 2")
    long countFailed();

    @Select("SELECT COUNT(*) FROM mq_outbox WHERE status = 0")
    long countPending();
}
