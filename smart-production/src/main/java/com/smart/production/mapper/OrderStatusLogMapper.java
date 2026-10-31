package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.OrderStatusLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {

    @Select("SELECT * FROM order_status_log WHERE order_id = #{orderId} ORDER BY operate_time, id")
    List<OrderStatusLog> selectByOrderId(@Param("orderId") Long orderId);
}
