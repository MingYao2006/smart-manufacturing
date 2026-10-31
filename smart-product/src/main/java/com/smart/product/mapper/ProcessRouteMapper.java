package com.smart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.product.entity.ProcessRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ProcessRouteMapper extends BaseMapper<ProcessRoute> {

    @Select("SELECT * FROM process_route WHERE product_id = #{productId} AND status = 2 ORDER BY id DESC LIMIT 1")
    ProcessRoute selectActiveByProductId(@Param("productId") Long productId);

    @Update("UPDATE process_route SET status = 3, update_time = NOW() "
            + "WHERE product_id = #{productId} AND status = 2 AND id <> #{excludeId}")
    int deactivateOthers(@Param("productId") Long productId, @Param("excludeId") Long excludeId);

    @Update("UPDATE process_route SET status = 2, effective_date = COALESCE(#{effectiveDate}, CURDATE()), "
            + "update_time = NOW() WHERE id = #{id} AND status = 1")
    int activateCas(@Param("id") Long id, @Param("effectiveDate") LocalDate effectiveDate);

    @Select("SELECT COUNT(*) FROM process_route WHERE product_id = #{productId} AND status <> 1")
    int countReleasedVersions(@Param("productId") Long productId);

    @Select("SELECT * FROM process_route WHERE product_id = #{productId} ORDER BY id DESC")
    List<ProcessRoute> selectByProductId(@Param("productId") Long productId);
}
