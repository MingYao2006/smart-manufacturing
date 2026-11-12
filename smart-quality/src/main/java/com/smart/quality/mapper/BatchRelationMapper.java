package com.smart.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.quality.entity.BatchRelation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BatchRelationMapper extends BaseMapper<BatchRelation> {

    /**
     * 🔑 幂等插入：{@code uk_relation_consume(production_order_id, source_batch_no, relation_type)}
     * 保证"同一工单 + 同一批次"只记一条边。
     *
     * <p>outbox 是 at-least-once 投递（可能重投），多实例定时任务也可能重复投递。
     * 用 {@code INSERT IGNORE} + 唯一索引在数据库层硬拦，比 Redis SETNX 更可靠
     * （不会因为 key 过期或 Redis 丢数据而失效）。
     *
     * @return 1 = 首次插入；0 = 已存在（重复投递，幂等跳过）
     */
    @Insert("INSERT IGNORE INTO batch_relation "
            + "(source_batch_id, source_batch_no, target_batch_id, target_batch_no, "
            + " production_order_id, order_no, quantity, relation_type, is_reversed, create_time) "
            + "VALUES (#{sourceBatchId}, #{sourceBatchNo}, #{targetBatchId}, #{targetBatchNo}, "
            + "        #{productionOrderId}, #{orderNo}, #{quantity}, #{relationType}, 0, NOW())")
    int insertIgnore(BatchRelation rel);

    @Select("SELECT * FROM batch_relation WHERE production_order_id = #{orderId} "
            + "AND source_batch_no = #{sourceBatchNo} AND relation_type = #{relationType} "
            + "AND is_reversed = 0 LIMIT 1")
    BatchRelation selectByBizKey(@Param("orderId") Long orderId,
                                 @Param("sourceBatchNo") String sourceBatchNo,
                                 @Param("relationType") String relationType);

    /**
     * 完工回填：按工单号把该工单下所有空 target 的边统一指向产品批次。
     *
     * <p>只更新 {@code target_batch_id IS NULL} 的行 —— 已回填的边不会被覆盖，
     * 所以重复执行（异步重投/人工重放）是幂等的。
     */
    @Update("UPDATE batch_relation SET target_batch_id = #{targetId}, target_batch_no = #{targetBatchNo} "
            + "WHERE production_order_id = #{orderId} AND target_batch_id IS NULL "
            + "AND relation_type = 'CONSUME' AND is_reversed = 0")
    int bindOrderToProductBatch(@Param("orderId") Long orderId,
                                @Param("targetId") Long targetId,
                                @Param("targetBatchNo") String targetBatchNo);

    /**
     * 定时回填用：找出"仍有空 target 边"的工单 ID（去重）。
     * 产品批次是否存在由服务层用 {@code ProductBatchMapper.selectByOrderId} 判断。
     */
    @Select("SELECT DISTINCT production_order_id AS orderId FROM batch_relation "
            + "WHERE target_batch_id IS NULL AND relation_type = 'CONSUME' "
            + "  AND is_reversed = 0 AND production_order_id IS NOT NULL "
            + "LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectPendingBindOrderIds(@Param("limit") int limit);

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
