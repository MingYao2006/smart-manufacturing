package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.ProductionOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ProductionOrderMapper extends BaseMapper<ProductionOrder> {

    /**
     * 🔑 状态流转的 CAS 条件更新 —— 整个状态机并发安全的核心。
     *
     * <p>{@code WHERE id=? AND status=?}：
     * <ul>
     *   <li>两个线程同时下达同一工单，只有一个影响行数 = 1，另一个 = 0 → 抛"状态已变更"</li>
     *   <li>不需要加锁，数据库行锁天然保证互斥</li>
     * </ul>
     *
     * <p>影响行数判定必须严格 {@code == 1}：
     * 如果写成 {@code > 0}，在"同一条数据被匹配多次"（理论上不会但留个心眼）时判断就失效了。
     */
    @Update("UPDATE production_order SET status = #{target}, update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{orderId} AND status = #{expected} AND is_deleted = 0")
    int updateStatusCas(@Param("orderId") Long orderId,
                        @Param("target") int target,
                        @Param("expected") int expected,
                        @Param("operatorId") Long operatorId);

    /**
     * 记录实际开始时间（仅当尚未记录时写入，幂等）。
     */
    @Update("UPDATE production_order SET actual_start_time = COALESCE(actual_start_time, NOW()), "
            + "update_time = NOW() WHERE id = #{orderId} AND is_deleted = 0")
    int markActualStart(@Param("orderId") Long orderId);

    @Update("UPDATE production_order SET actual_end_time = NOW(), update_time = NOW() "
            + "WHERE id = #{orderId} AND is_deleted = 0")
    int markActualEnd(@Param("orderId") Long orderId);

    /**
     * 🔑 重算工单进度（幂等运算，重复执行结果相同）。
     *
     * <p>为什么不累加：消息是 at-least-once，重复消费会导致进度超过 100%。
     * 重算天然幂等，算多少次结果都一样 —— 这是优先选用的幂等模式。
     */
    @Update("UPDATE production_order SET completed_quantity = #{completed}, "
            + "qualified_quantity = #{qualified}, defect_quantity = #{defect}, "
            + "progress = #{progress}, update_time = NOW() "
            + "WHERE id = #{orderId} AND is_deleted = 0")
    int recalcProgress(@Param("orderId") Long orderId,
                       @Param("completed") BigDecimal completed,
                       @Param("qualified") BigDecimal qualified,
                       @Param("defect") BigDecimal defect,
                       @Param("progress") int progress);

    /**
     * 按状态统计（看板用）。
     */
    @Select("SELECT status, COUNT(*) cnt FROM production_order WHERE is_deleted = 0 GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus();

    @Select("SELECT * FROM production_order WHERE product_id = #{productId} AND is_deleted = 0")
    List<ProductionOrder> selectByProductId(@Param("productId") Long productId);

    /**
     * 扫描"所有工序已完成但工单未完工"的异常数据（MQ 丢消息时的兜底）。
     */
    @Select("SELECT o.* FROM production_order o "
            + "WHERE o.status = 4 AND o.is_deleted = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM work_task t WHERE t.order_id = o.id "
            + "                AND t.is_deleted = 0 AND t.status NOT IN (4, 5))")
    List<ProductionOrder> selectAllTasksCompletedButOrderNot();
}
