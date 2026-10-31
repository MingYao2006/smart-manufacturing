package com.smart.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.production.entity.WorkTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface WorkTaskMapper extends BaseMapper<WorkTask> {

    /**
     * 🔑 工序状态 CAS 流转。两个人同时点"开始"，只有一个成功。
     */
    @Update("UPDATE work_task SET status = #{target}, update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{taskId} AND status = #{expected} AND is_deleted = 0")
    int updateStatusCas(@Param("taskId") Long taskId,
                        @Param("target") int target,
                        @Param("expected") int expected,
                        @Param("operatorId") Long operatorId);

    /**
     * 开始工序：CAS 置为进行中，并记录开始时间（首次）。
     */
    @Update("UPDATE work_task SET status = 2, start_time = COALESCE(start_time, NOW()), "
            + "update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{taskId} AND status = #{expected} AND is_deleted = 0")
    int startTask(@Param("taskId") Long taskId,
                  @Param("expected") int expected,
                  @Param("operatorId") Long operatorId);

    /**
     * 完成工序：CAS 置为指定状态（COMPLETED 或 WAIT_INSPECTION）并记录结束时间。
     */
    @Update("UPDATE work_task SET status = #{target}, end_time = NOW(), "
            + "update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{taskId} AND status = #{expected} AND is_deleted = 0")
    int finishTask(@Param("taskId") Long taskId,
                   @Param("target") int target,
                   @Param("expected") int expected,
                   @Param("operatorId") Long operatorId);

    /**
     * 派工：指定操作工 / 工作中心。
     */
    @Update("UPDATE work_task SET operator_id = #{operatorId}, operator_name = #{operatorName}, "
            + "work_center = COALESCE(#{workCenter}, work_center), update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{taskId} AND is_deleted = 0")
    int dispatch(@Param("taskId") Long taskId,
                 @Param("operatorId") Long operatorId,
                 @Param("operatorName") String operatorName,
                 @Param("workCenter") String workCenter);

    /**
     * 🔑 报工累加 —— 超报校验的条件更新（本周最容易做错的地方）。
     *
     * <p>把"判断 + 更新"压进同一条 SQL：
     * <pre>
     *   SET reported_quantity = reported_quantity + ?
     *   WHERE status = IN_PROGRESS
     *     AND reported_quantity + ? <= plan_quantity
     * </pre>
     *
     * <p>如果写成"先 SELECT SUM 再判断再 UPDATE"，两个工位同时报工就会都通过校验 → 超报。
     * 影响行数 = 0 说明超报或状态不对，必须抛业务异常。
     */
    @Update("UPDATE work_task SET reported_quantity = reported_quantity + #{qty}, "
            + "qualified_quantity = qualified_quantity + #{qualified}, "
            + "defect_quantity = defect_quantity + #{defect}, "
            + "actual_hours = actual_hours + #{hours}, "
            + "update_by = #{operatorId}, update_time = NOW() "
            + "WHERE id = #{taskId} AND status = 2 AND is_deleted = 0 "
            + "AND reported_quantity + #{qty} <= plan_quantity")
    int addReportedQuantity(@Param("taskId") Long taskId,
                            @Param("qty") BigDecimal qty,
                            @Param("qualified") BigDecimal qualified,
                            @Param("defect") BigDecimal defect,
                            @Param("hours") BigDecimal hours,
                            @Param("operatorId") Long operatorId);

    @Select("SELECT * FROM work_task WHERE order_id = #{orderId} AND is_deleted = 0 ORDER BY step_no")
    List<WorkTask> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 查前一道工序（step_no 小于当前的最大值）。
     *
     * <p>工序顺序约束的实现方式：不能只看"前一工序号 -10"，
     * 因为工序号可能不连续（10/20/30，插入后可能 15）。
     * 必须取"所有 step_no 小于当前的最大那条"。
     */
    @Select("SELECT * FROM work_task WHERE order_id = #{orderId} AND step_no < #{stepNo} "
            + "AND is_deleted = 0 ORDER BY step_no DESC LIMIT 1")
    WorkTask selectPreviousStep(@Param("orderId") Long orderId, @Param("stepNo") Integer stepNo);

    @Select("SELECT COUNT(*) FROM work_task WHERE order_id = #{orderId} AND is_deleted = 0")
    int countByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT COUNT(*) FROM work_task WHERE order_id = #{orderId} AND is_deleted = 0 "
            + "AND status IN (4, 5)")
    int countFinishedByOrderId(@Param("orderId") Long orderId);

    /**
     * 汇总工单下所有工序的报工量（用于进度重算 —— 重算而非累加，天然幂等）。
     */
    @Select("SELECT COALESCE(SUM(reported_quantity),0) FROM work_task "
            + "WHERE order_id = #{orderId} AND is_deleted = 0")
    BigDecimal sumReportedQuantity(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(SUM(qualified_quantity),0) FROM work_task "
            + "WHERE order_id = #{orderId} AND is_deleted = 0")
    BigDecimal sumQualifiedQuantity(@Param("orderId") Long orderId);

    @Select("SELECT COALESCE(SUM(defect_quantity),0) FROM work_task "
            + "WHERE order_id = #{orderId} AND is_deleted = 0")
    BigDecimal sumDefectQuantity(@Param("orderId") Long orderId);
}
