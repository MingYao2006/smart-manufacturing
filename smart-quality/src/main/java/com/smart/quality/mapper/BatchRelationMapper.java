package com.smart.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.quality.entity.BatchRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BatchRelationMapper extends BaseMapper<BatchRelation> {

    /**
     * 反向追溯用：查以 target 为目标的所有边（谁产出了这个批次）。
     */
    @Select("SELECT * FROM batch_relation WHERE target_batch_id = #{targetBatchId} AND is_reversed = 0")
    List<BatchRelation> selectByTarget(@Param("targetBatchId") Long targetBatchId);

    /**
     * 正向追溯用：查以 source 为源的所有边（这个批次被谁消耗）。
     */
    @Select("SELECT * FROM batch_relation WHERE source_batch_id = #{sourceBatchId} AND is_reversed = 0")
    List<BatchRelation> selectBySource(@Param("sourceBatchId") Long sourceBatchId);

    @Select("SELECT * FROM batch_relation WHERE production_order_id = #{orderId}")
    List<BatchRelation> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 批量查询（BFS 逐层展开时减少查询次数，避免 N+1）。
     */
    @Select("<script>SELECT * FROM batch_relation WHERE target_batch_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND is_reversed = 0</script>")
    List<BatchRelation> selectByTargets(@Param("ids") List<Long> targetBatchIds);

    @Select("<script>SELECT * FROM batch_relation WHERE source_batch_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND is_reversed = 0</script>")
    List<BatchRelation> selectBySources(@Param("ids") List<Long> sourceBatchIds);

    /**
     * 一次性查出全图边（小数据量时用于内存遍历，避免递归 N+1 查询）。
     */
    @Select("SELECT * FROM batch_relation WHERE is_reversed = 0")
    List<BatchRelation> selectAllActive();

    @Select("SELECT COUNT(*) FROM batch_relation WHERE source_batch_id = #{sourceId} "
            + "AND target_batch_id = #{targetId} AND production_order_id = #{orderId}")
    int countRelation(@Param("sourceId") Long sourceId,
                      @Param("targetId") Long targetId,
                      @Param("orderId") Long orderId);
}
