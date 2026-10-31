package com.smart.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产报工。每次报工的<b>明细账</b>（work_task 是汇总账）。
 *
 * <p>为什么报工要单独一张表而不是直接改任务数量：
 * 有明细才能追溯"谁在什么时候报了多少"，出问题能定位到具体某次报工；
 * 汇总账由明细汇总得出，数据自洽。
 *
 * <p>🔑 {@code reportNo} 是幂等键：用户双击/网络重试会产生重复提交，
 * 没有它"点两次"就会数量翻倍。
 */
@Data
@TableName("production_report")
public class ProductionReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报工单号（幂等键） */
    private String reportNo;

    private Long orderId;

    private String orderNo;

    private Long taskId;

    private Integer stepNo;

    private String stepName;

    /** 本次报工数量 */
    private BigDecimal reportQuantity;

    /** 合格数 */
    private BigDecimal qualifiedQuantity;

    /** 不良数（合格+不良=报工） */
    private BigDecimal defectQuantity;

    private BigDecimal actualHours;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime reportTime;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
