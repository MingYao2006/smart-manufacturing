package com.smart.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.quality.entity.ProductBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductBatchMapper extends BaseMapper<ProductBatch> {

    @Select("SELECT * FROM product_batch WHERE batch_no = #{batchNo}")
    ProductBatch selectByBatchNo(@Param("batchNo") String batchNo);

    @Select("SELECT * FROM product_batch WHERE batch_type = #{batchType} ORDER BY id DESC")
    List<ProductBatch> selectByType(@Param("batchType") Integer batchType);

    @Select("SELECT * FROM product_batch WHERE production_order_id = #{orderId}")
    List<ProductBatch> selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM product_batch WHERE material_id = #{materialId} ORDER BY id DESC")
    List<ProductBatch> selectByMaterialId(@Param("materialId") Long materialId);

    @Select("SELECT * FROM product_batch WHERE supplier_id = #{supplierId} ORDER BY id DESC")
    List<ProductBatch> selectBySupplierId(@Param("supplierId") Long supplierId);
}
