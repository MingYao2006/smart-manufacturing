package com.smart.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.quality.entity.QualityRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QualityRecordMapper extends BaseMapper<QualityRecord> {

    @Select("SELECT * FROM quality_record WHERE inspection_id = #{inspectionId}")
    List<QualityRecord> selectByInspectionId(@Param("inspectionId") Long inspectionId);

    /**
     * 不良代码统计（"本月尺寸超差占 40%" 这类分析的数据来源）。
     */
    @Select("SELECT defect_code AS defectCode, defect_name AS defectName, "
            + "COUNT(*) AS times, COALESCE(SUM(defect_quantity),0) AS totalQty "
            + "FROM quality_record WHERE is_qualified = 0 AND defect_code IS NOT NULL "
            + "GROUP BY defect_code, defect_name ORDER BY times DESC")
    List<Map<String, Object>> defectStatistics();
}
