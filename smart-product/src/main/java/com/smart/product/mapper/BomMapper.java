package com.smart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.product.entity.Bom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BomMapper extends BaseMapper<Bom> {

    /**
     * 查某产品当前生效的 BOM（只应有 0 或 1 条）。
     */
    @Select("SELECT * FROM bom WHERE product_id = #{productId} AND status = 2 ORDER BY id DESC LIMIT 1")
    Bom selectActiveByProductId(@Param("productId") Long productId);

    /**
     * 把同产品的其他生效版本置为失效（生效新版本时调用，与下面两条必须在同一事务）。
     */
    @Update("UPDATE bom SET status = 3, update_time = NOW() "
            + "WHERE product_id = #{productId} AND status = 2 AND id <> #{excludeId}")
    int deactivateOthers(@Param("productId") Long productId, @Param("excludeId") Long excludeId);

    /**
     * CAS 置为生效：只有当前是草稿才允许生效，防止并发下两个版本同时生效。
     */
    @Update("UPDATE bom SET status = 2, effective_date = COALESCE(#{effectiveDate}, CURDATE()), "
            + "update_time = NOW() WHERE id = #{id} AND status = 1")
    int activateCas(@Param("id") Long id, @Param("effectiveDate") java.time.LocalDate effectiveDate);

    /**
     * 统计某产品下非草稿（已生效/已失效）的版本数，用于自动生成版本号。
     */
    @Select("SELECT COUNT(*) FROM bom WHERE product_id = #{productId} AND status <> 1")
    int countReleasedVersions(@Param("productId") Long productId);

    @Select("SELECT * FROM bom WHERE product_id = #{productId} ORDER BY id DESC")
    List<Bom> selectByProductId(@Param("productId") Long productId);
}
