package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.MaterialIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MaterialIssueMapper extends BaseMapper<MaterialIssue> {

    @Select("SELECT COUNT(*) FROM material_issue WHERE issue_no = #{issueNo}")
    int countByIssueNo(@Param("issueNo") String issueNo);

    @Select("SELECT * FROM material_issue WHERE issue_no = #{issueNo}")
    MaterialIssue selectByIssueNo(@Param("issueNo") String issueNo);

    @Select("SELECT * FROM material_issue WHERE order_id = #{orderId} ORDER BY id DESC")
    List<MaterialIssue> selectByOrderId(@Param("orderId") Long orderId);
}
