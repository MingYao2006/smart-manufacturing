package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.MaterialReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MaterialReservationMapper extends BaseMapper<MaterialReservation> {

    @Select("SELECT * FROM material_reservation WHERE order_id = #{orderId} ORDER BY material_id")
    List<MaterialReservation> selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM material_reservation WHERE order_id = #{orderId} AND material_id = #{materialId}")
    MaterialReservation selectByOrderAndMaterial(@Param("orderId") Long orderId,
                                                 @Param("materialId") Long materialId);

    @Select("SELECT COUNT(*) FROM material_reservation WHERE order_id = #{orderId}")
    int countByOrderId(@Param("orderId") Long orderId);

    /**
     * 🔑 累加已领数量 —— 条件更新，防并发领料超量。
     *
     * <p>{@code issued_quantity + #{qty} <= reserved_quantity} 写进 WHERE：
     * 两个线程同时领料，只有一个能通过校验，另一个影响行数 = 0。
     */
    @Update("UPDATE material_reservation "
            + "SET issued_quantity = issued_quantity + #{qty}, update_time = NOW(), "
            + "    status = CASE WHEN issued_quantity + #{qty} >= reserved_quantity THEN 3 ELSE 2 END "
            + "WHERE id = #{id} AND status IN (1, 2) "
            + "AND issued_quantity + #{qty} <= reserved_quantity")
    int addIssuedQuantity(@Param("id") Long id, @Param("qty") BigDecimal qty);

    @Update("UPDATE material_reservation SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") int status);

    @Update("UPDATE material_reservation SET status = 4, released_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND status IN (1, 2)")
    int markReleased(@Param("id") Long id);

    /**
     * 查过期的预留（定时任务释放，防预留泄漏）。
     */
    @Select("SELECT * FROM material_reservation WHERE status IN (1, 2) "
            + "AND expire_time IS NOT NULL AND expire_time < #{now} LIMIT #{limit}")
    List<MaterialReservation> selectExpired(@Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    /**
     * 对账用：按预留表推算每个物料的"应预留量"。
     */
    @Select("SELECT material_id AS materialId, warehouse_id AS warehouseId, "
            + "COALESCE(SUM(reserved_quantity - issued_quantity), 0) AS expectedReserved "
            + "FROM material_reservation WHERE status IN (1, 2) "
            + "GROUP BY material_id, warehouse_id")
    List<java.util.Map<String, Object>> sumActiveReserved();
}
