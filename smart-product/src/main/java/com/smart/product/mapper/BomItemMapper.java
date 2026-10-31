package com.smart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.product.entity.BomItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BomItemMapper extends BaseMapper<BomItem> {

    @Select("SELECT * FROM bom_item WHERE bom_id = #{bomId} ORDER BY seq_no, id")
    List<BomItem> selectByBomId(@Param("bomId") Long bomId);

    @Select("<script>SELECT * FROM bom_item WHERE bom_id IN "
            + "<foreach collection='bomIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY bom_id, seq_no</script>")
    List<BomItem> selectByBomIds(@Param("bomIds") List<Long> bomIds);

    @Select("SELECT COUNT(*) FROM bom_item WHERE bom_id = #{bomId}")
    int countByBomId(@Param("bomId") Long bomId);
}
