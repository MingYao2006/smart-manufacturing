package com.smart.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.quality.entity.QualityInspection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QualityInspectionMapper extends BaseMapper<QualityInspection> {

    @Select("SELECT * FROM quality_inspection WHERE inspection_no = #{no}")
    QualityInspection selectByNo(@Param("no") String inspectionNo);

    @Select("SELECT * FROM quality_inspection WHERE task_id = #{taskId} ORDER BY id DESC")
    List<QualityInspection> selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM quality_inspection WHERE order_id = #{orderId} ORDER BY id DESC")
    List<QualityInspection> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 查某工序最近一次已完成的质检结果（工序流转门禁校验用）。
     */
    @Select("SELECT * FROM quality_inspection WHERE task_id = #{taskId} AND status = 2 "
            + "ORDER BY id DESC LIMIT 1")
    QualityInspection selectLastFinishedByTaskId(@Param("taskId") Long taskId);
}
