package com.smart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.product.entity.ProcessStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProcessStepMapper extends BaseMapper<ProcessStep> {

    @Select("SELECT * FROM process_step WHERE route_id = #{routeId} ORDER BY step_no")
    List<ProcessStep> selectByRouteId(@Param("routeId") Long routeId);

    @Select("SELECT COUNT(*) FROM process_step WHERE route_id = #{routeId}")
    int countByRouteId(@Param("routeId") Long routeId);
}
