package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.PurchaseRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 进货审批单 Mapper。
 *
 * <p>审批的三个动作都用「条件更新」收口：判断与状态变更压进同一条 SQL，
 * 用影响行数作为幂等 / 并发信号，和库存三量模型的条件更新同一套思路。
 */
@Mapper
public interface PurchaseRequestMapper extends BaseMapper<PurchaseRequest> {

    @Select("SELECT * FROM purchase_request ORDER BY create_time DESC, id DESC")
    List<PurchaseRequest> selectAll();

    @Select("SELECT * FROM purchase_request WHERE status = #{status} ORDER BY create_time DESC, id DESC")
    List<PurchaseRequest> selectByStatus(@Param("status") int status);

    /**
     * 审批通过：只有「待审批」能流转到「已通过」。
     *
     * @return 1 = 本次审批生效（调用方负责入库）；0 = 已被处理（并发 / 重复点击），拒绝
     */
    @Update("UPDATE purchase_request "
            + "SET status = 2, approver_id = #{approverId}, approver_name = #{approverName}, "
            + "approver_role = #{approverRole}, approve_comment = #{comment}, "
            + "approve_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND status = 1")
    int approvePending(@Param("id") Long id,
                       @Param("approverId") Long approverId,
                       @Param("approverName") String approverName,
                       @Param("approverRole") String approverRole,
                       @Param("comment") String comment);

    /**
     * 驳回 / 撤销：同样只有「待审批」能流转。
     *
     * @return 1 = 生效；0 = 已被处理
     */
    @Update("UPDATE purchase_request "
            + "SET status = #{targetStatus}, approver_id = #{approverId}, "
            + "approver_name = #{approverName}, approver_role = #{approverRole}, "
            + "approve_comment = #{comment}, approve_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND status = 1")
    int closePending(@Param("id") Long id,
                     @Param("targetStatus") int targetStatus,
                     @Param("approverId") Long approverId,
                     @Param("approverName") String approverName,
                     @Param("approverRole") String approverRole,
                     @Param("comment") String comment);

    /** 回写审批通过时生成的入库流水 ID，串起「审批单 → 库存流水」审计链路 */
    @Update("UPDATE purchase_request SET inbound_tx_id = #{txId}, update_time = NOW() WHERE id = #{id}")
    int updateInboundTxId(@Param("id") Long id, @Param("txId") Long txId);
}
