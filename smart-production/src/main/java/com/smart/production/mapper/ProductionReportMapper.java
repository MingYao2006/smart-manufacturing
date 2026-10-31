package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.ProductionReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductionReportMapper extends BaseMapper<ProductionReport> {

    @Select("SELECT * FROM production_report WHERE task_id = #{taskId} ORDER BY report_time DESC")
    List<ProductionReport> selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM production_report WHERE order_id = #{orderId} ORDER BY report_time DESC")
    List<ProductionReport> selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT COUNT(*) FROM production_report WHERE report_no = #{reportNo}")
    int countByReportNo(@Param("reportNo") String reportNo);
}
