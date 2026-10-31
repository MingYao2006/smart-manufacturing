package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.MaterialInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 库存 Mapper —— <b>本项目的并发控制核心</b>。
 *
 * <p>所有"判断 + 更新"都压进同一条 SQL，用影响行数作为成功/失败信号。
 * 详见 docs/07-库存并发控制方案.md。
 */
@Mapper
public interface MaterialInventoryMapper extends BaseMapper<MaterialInventory> {

    /* ==================== 🔑 核心：条件更新（CAS） ==================== */

    /**
     * 🔑 预留：<b>只动 reserved_quantity，不动 quantity</b>（物料还在仓库）。
     *
     * <p>为什么把 {@code quantity - reserved_quantity >= #{qty}} 写进 WHERE：
     * <ul>
     *   <li>判断与更新是<b>同一条 SQL</b>，中间没有窗口期</li>
     *   <li>数据库行锁天然保证互斥，不需要额外加锁</li>
     *   <li>影响行数直接给出成功/失败信号，不需要重试循环</li>
     * </ul>
     *
     * <p>对比 Redis 锁：加锁→查库存→判断→更新→解锁，锁内查到的库存可能已过期；
     * 还有锁超时、主从切换丢锁、锁续期等问题。库存这种"必须强一致 + 单行更新"的场景，
     * 条件更新是更优解 —— <b>Redis 锁不是不能用，而是这里用了反而更差</b>。
     *
     * @return 1 = 成功；0 = 可用量不足（或记录不存在）
     */
    @Update("UPDATE material_inventory "
            + "SET reserved_quantity = reserved_quantity + #{qty}, update_time = NOW() "
            + "WHERE material_id = #{materialId} AND warehouse_id = #{warehouseId} "
            + "AND quantity - reserved_quantity >= #{qty}")
    int reserveStock(@Param("materialId") Long materialId,
                     @Param("warehouseId") Long warehouseId,
                     @Param("qty") BigDecimal qty);

    /**
     * 🔑 领料：<b>同时减 quantity 和 reserved_quantity</b>。
     *
     * <p>领料是"预留转实际消耗"：物料离开仓库所以 quantity 要减，
     * 同时这个预留已被消费所以 reserved 也要减。
     *
     * <p>⚠️ 必须校验 {@code reserved_quantity >= qty}：
     * 防止领了没预留的物料（绕过预留直接领货 = 库存管控失效）。
     */
    @Update("UPDATE material_inventory "
            + "SET quantity = quantity - #{qty}, "
            + "reserved_quantity = reserved_quantity - #{qty}, update_time = NOW() "
            + "WHERE material_id = #{materialId} AND warehouse_id = #{warehouseId} "
            + "AND quantity >= #{qty} AND reserved_quantity >= #{qty}")
    int issueStock(@Param("materialId") Long materialId,
                   @Param("warehouseId") Long warehouseId,
                   @Param("qty") BigDecimal qty);

    /**
     * 释放预留（工单取消 / 预留过期）：只减 reserved_quantity。
     */
    @Update("UPDATE material_inventory "
            + "SET reserved_quantity = reserved_quantity - #{qty}, update_time = NOW() "
            + "WHERE material_id = #{materialId} AND warehouse_id = #{warehouseId} "
            + "AND reserved_quantity >= #{qty}")
    int releaseStock(@Param("materialId") Long materialId,
                     @Param("warehouseId") Long warehouseId,
                     @Param("qty") BigDecimal qty);

    /**
     * 入库：只加 quantity。
     */
    @Update("UPDATE material_inventory "
            + "SET quantity = quantity + #{qty}, update_time = NOW() "
            + "WHERE material_id = #{materialId} AND warehouse_id = #{warehouseId}")
    int inboundStock(@Param("materialId") Long materialId,
                     @Param("warehouseId") Long warehouseId,
                     @Param("qty") BigDecimal qty);

    /**
     * 退料：加回 quantity（退料后物料回到仓库，但不再占用预留）。
     */
    @Update("UPDATE material_inventory "
            + "SET quantity = quantity + #{qty}, update_time = NOW() "
            + "WHERE material_id = #{materialId} AND warehouse_id = #{warehouseId}")
    int returnStock(@Param("materialId") Long materialId,
                    @Param("warehouseId") Long warehouseId,
                    @Param("qty") BigDecimal qty);

    /* ==================== 查询 ==================== */

    @Select("SELECT * FROM material_inventory WHERE material_id = #{materialId} "
            + "AND warehouse_id = #{warehouseId}")
    MaterialInventory selectOne(@Param("materialId") Long materialId,
                                @Param("warehouseId") Long warehouseId);

    @Select("<script>SELECT * FROM material_inventory WHERE warehouse_id = #{warehouseId} "
            + "AND material_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<MaterialInventory> selectByMaterialIds(@Param("warehouseId") Long warehouseId,
                                                @Param("ids") List<Long> materialIds);

    @Select("SELECT * FROM material_inventory ORDER BY material_id")
    List<MaterialInventory> selectAll();

    /**
     * 低库存预警：可用量 < 安全库存。
     */
    @Select("SELECT * FROM material_inventory "
            + "WHERE quantity - reserved_quantity < safety_stock ORDER BY material_id")
    List<MaterialInventory> selectBelowSafetyStock();

    /**
     * 对账用：按流水重算的现有量（入库+退料 - 出库）与预留量。
     *
     * <p>流水类型：
     * 1-入库(+quantity) 2-出库(-quantity) 3-预留(+reserved) 4-释放预留(-reserved)
     * 5-退料(+quantity) 6-盘点调整 7-预留占用冲正
     *
     * <p>注意流水表里 quantity 字段是"变动量"（带符号），这里直接 SUM 即可。
     */
    @Select("SELECT material_id AS materialId, warehouse_id AS warehouseId, "
            + "COALESCE(SUM(quantity), 0) AS ledgerQty, "
            + "COALESCE(SUM(CASE WHEN transaction_type = 3 THEN ABS(quantity) "
            + "                  WHEN transaction_type IN (4, 7) THEN -ABS(quantity) ELSE 0 END), 0) AS ledgerReserved "
            + "FROM inventory_transaction GROUP BY material_id, warehouse_id")
    List<Map<String, Object>> sumLedger();

    /**
     * 强行修正数量（对账发现不一致时，以流水为准修正）。
     */
    @Update("UPDATE material_inventory SET quantity = #{qty}, update_time = NOW() WHERE id = #{id}")
    int fixQuantity(@Param("id") Long id, @Param("qty") BigDecimal qty);

    @Update("UPDATE material_inventory SET reserved_quantity = #{qty}, update_time = NOW() WHERE id = #{id}")
    int fixReserved(@Param("id") Long id, @Param("qty") BigDecimal qty);
}
