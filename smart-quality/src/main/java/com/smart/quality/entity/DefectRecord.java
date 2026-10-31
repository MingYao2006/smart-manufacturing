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
 * 不良品记录。报工不良数与质检不良项的统一出口，供质量分析。
 */
@Data
@TableName("defect_record")
public class DefectRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String recordNo;

    /** REPORT-报工 INSPECTION-质检 */
    private String sourceType;

    private Long sourceId;

    private Long orderId;

    private String orderNo;

    private Long taskId;

    private String stepName;

    private String batchNo;

    /** 不良代码（可聚合统计） */
    private String defectCode;

    private String defectName;

    private BigDecimal defectQuantity;

    /** 1-返工 2-让步接收 3-报废 4-待处理 */
    private Integer handleType;

    /** 0-待处理 1-已处理 */
    private Integer handleStatus;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
