package com.smart.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.inventory.entity.MaterialIssueItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MaterialIssueItemMapper extends BaseMapper<MaterialIssueItem> {

    @Select("SELECT * FROM material_issue_item WHERE issue_id = #{issueId}")
    List<MaterialIssueItem> selectByIssueId(@Param("issueId") Long issueId);

    @Select("SELECT * FROM material_issue_item WHERE batch_no = #{batchNo}")
    List<MaterialIssueItem> selectByBatchNo(@Param("batchNo") String batchNo);
}
