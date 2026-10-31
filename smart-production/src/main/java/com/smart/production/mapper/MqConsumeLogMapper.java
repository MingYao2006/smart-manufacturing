package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /**
     * 尝试占位消费幂等键。返回 1 = 首次处理；返回 0 = 已处理过（唯一索引冲突被 IGNORE）。
     *
     * <p>⚠️ 必须在<b>业务事务内</b>调用：处理失败回滚时，这条幂等记录也会一起回滚，
     * 这样重试时不会被自己挡住（"幂等键只能挡重复，不能挡死重试"）。
     */
    @Insert("INSERT IGNORE INTO mq_consume_log (consume_key, topic, msg_id, result, create_time) "
            + "VALUES (#{consumeKey}, #{topic}, #{msgId}, #{result}, NOW())")
    int tryConsume(@Param("consumeKey") String consumeKey,
                   @Param("topic") String topic,
                   @Param("msgId") String msgId,
                   @Param("result") String result);
}
