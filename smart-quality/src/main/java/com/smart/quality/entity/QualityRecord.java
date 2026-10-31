package com.smart.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质检明细。每个检验项的实测值，质量数据的原子单位。<b>只增不改</b>。
 *
 * <p>为什么检验项独立成表：一个质检单有多个检验项（外观/尺寸/性能），
 * 不良代码需要聚合统计（"本月尺寸超差占 40%"）。塞进主表就无法做多维分析。
 *
 * <p>🔑 {@code defectCode} 存<b>代码</b>而非自由文本：
 * 统计分析需要可聚合的代码，这是质量管理的标准做法。
 */
@Data
@TableName("quality_record")
public class QualityRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inspectionId;

    private String itemName;

    private String standardValue;

    private String actualValue;

    /** 1-合格 0-不合格 */
    private Integer isQualified;

    private String defectCode;

    private String defectName;

    private BigDecimal defectQuantity;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
