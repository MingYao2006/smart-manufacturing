package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.MaterialIssueOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 领料批次关系 outbox Mapper。
 *
 * <p>与 {@code mq_outbox}（smart-production）同一套思路，但独立表：
 * 领料域的消息不依赖 RabbitMQ，直接由定时任务 HTTP 投递给质量服务，
 * 这样即使 MQ 没起来，追溯链照样能建。
 */
@Mapper
public interface MaterialIssueOutboxMapper extends BaseMapper<MaterialIssueOutbox> {

    /**
     * 在领料事务内写入。{@code INSERT IGNORE} + {@code uk_outbox_biz} 保证
     * 同一工单同一批次重复领料也只产生一条待发消息（幂等）。
     */
    @Insert("INSERT IGNORE INTO material_issue_outbox "
            + "(biz_key, order_id, order_no, issue_no, material_id, material_code, batch_no, quantity, "
            + " payload, status, retry_count, next_retry_time, create_time, update_time) VALUES "
            + "(#{bizKey}, #{orderId}, #{orderNo}, #{issueNo}, #{materialId}, #{materialCode}, #{batchNo}, "
            + " #{quantity}, #{payload}, 0, 0, NOW(), NOW(), NOW())")
    int insertIgnore(MaterialIssueOutbox outbox);

    @Select("SELECT * FROM material_issue_outbox WHERE status = 0 "
            + "AND (next_retry_time IS NULL OR next_retry_time <= NOW()) "
            + "ORDER BY id LIMIT #{limit}")
    List<MaterialIssueOutbox> selectPending(@Param("limit") int limit);

    @Update("UPDATE material_issue_outbox SET status = 1, sent_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 投递失败：重试次数 +1，指数退避；超过上限置为终态失败并告警。
     */
    @Update("UPDATE material_issue_outbox "
            + "SET status = IF(retry_count + 1 >= #{maxRetry}, 2, 0), "
            + "    retry_count = retry_count + 1, "
            + "    next_retry_time = DATE_ADD(NOW(), INTERVAL LEAST(POW(2, retry_count), 300) SECOND), "
            + "    last_error = #{error}, update_time = NOW() WHERE id = #{id}")
    int markRetry(@Param("id") Long id,
                  @Param("error") String error,
                  @Param("maxRetry") int maxRetry);

    /**
     * 对账用：找出"流水里有批次号、outbox 里却没有"的领料流水，用于补录。
     *
     * <p>只查出库(type=2)且带批次号的流水。若某条流水没有对应 outbox 记录，
     * 说明当时漏写（或有人手工改库），必须补录，否则追溯链断了事后补不回来。
     */
    @Select("SELECT t.id AS tx_id, t.biz_no AS issue_no, t.material_id, t.material_code, "
            + "       t.batch_no, ABS(t.quantity) AS quantity, m.order_id, m.order_no "
            + "FROM inventory_transaction t "
            + "LEFT JOIN material_issue m ON m.issue_no = t.biz_no "
            + "LEFT JOIN material_issue_outbox o ON o.biz_key = CONCAT(m.order_id, ':', t.batch_no) "
            + "WHERE t.transaction_type = 2 AND t.batch_no IS NOT NULL AND t.batch_no <> '' "
            + "  AND o.id IS NULL AND m.order_id IS NOT NULL "
            + "ORDER BY t.id LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectMissingRelations(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM material_issue_outbox WHERE status = 0")
    long countPending();

    @Select("SELECT COUNT(*) FROM material_issue_outbox WHERE status = 2")
    long countFailed();
}
