package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.ProductionPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductionPlanMapper extends BaseMapper<ProductionPlan> {

    @Select("SELECT * FROM production_plan WHERE id = #{id} AND is_deleted = 0")
    ProductionPlan selectOneById(@Param("id") Long id);

    /**
     * 状态 CAS：草稿 → 已确认等。
     */
    @Update("UPDATE production_plan SET status = #{target}, update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{planId} AND status = #{expected} AND is_deleted = 0")
    int updateStatusCas(@Param("planId") Long planId,
                        @Param("target") int target,
                        @Param("expected") int expected,
                        @Param("operatorId") Long operatorId);

    @Select("SELECT * FROM production_plan WHERE product_id = #{productId} AND is_deleted = 0 "
            + "ORDER BY plan_end_date")
    List<ProductionPlan> selectByProductId(@Param("productId") Long productId);

    @Select("SELECT COUNT(*) FROM production_order WHERE plan_id = #{planId} AND is_deleted = 0")
    int countOrdersByPlan(@Param("planId") Long planId);
}
