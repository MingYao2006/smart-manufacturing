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
 * BOM 主表。定义"一个产品由哪些物料按什么用量组成"。
 *
 * <p>🔑 版本化对象，这是 MES 的核心约束：
 * <ul>
 *   <li>同一产品同时只能有一个 {@code status=ACTIVE(2)} 的版本</li>
 *   <li>已生效的 BOM 不允许修改明细，要改必须新建版本</li>
 *   <li>生产工单会锁定 bom_id，历史工单的物料需求才能追溯</li>
 * </ul>
 */
@Data
@TableName("bom")
public class Bom implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String bomVersion;

    /** 1-草稿 2-生效 3-失效，见 BomStatus */
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
