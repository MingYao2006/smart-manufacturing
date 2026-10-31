package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.InventoryTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存流水 Mapper。
 *
 * <p>⚠️ <b>只有 insert 和 select，绝不提供 update / delete 方法</b> ——
 * 账本只增不改，从 Mapper 层面就断了修改的可能。
 */
@Mapper
public interface InventoryTransactionMapper extends BaseMapper<InventoryTransaction> {

    @Select("SELECT * FROM inventory_transaction WHERE material_id = #{materialId} "
            + "ORDER BY id DESC LIMIT 1")
    InventoryTransaction selectLastByMaterial(@Param("materialId") Long materialId);

    @Select("SELECT * FROM inventory_transaction WHERE biz_no = #{bizNo} ORDER BY id")
    List<InventoryTransaction> selectByBizNo(@Param("bizNo") String bizNo);

    @Select("SELECT * FROM inventory_transaction WHERE material_id = #{materialId} "
            + "AND batch_no = #{batchNo} ORDER BY id")
    List<InventoryTransaction> selectByMaterialAndBatch(@Param("materialId") Long materialId,
                                                        @Param("batchNo") String batchNo);

    @Select("SELECT COUNT(*) FROM inventory_transaction")
    long countAll();
}
