package com.smart.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工艺路线主表。定义"这个产品要经过哪些工序"。与 BOM 并列的"另一份配方"。
 *
 * <p>为什么要版本化：工艺改进（增加检验、调整顺序）很常见，
 * 老工单必须按老工艺执行，否则历史数据无法解释。
 */
@Data
@TableName("process_route")
public class ProcessRoute implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String routeVersion;

    /** 1-草稿 2-生效 3-失效 */
    private Integer status;

    private LocalDate effectiveDate;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
