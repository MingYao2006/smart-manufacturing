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
 * 质检单。工序流转的<b>门禁</b>。
 *
 * <h3>为什么 result 是四态而不是二态</h3>
 * <ul>
 *   <li><b>合格</b>：正常放行</li>
 *   <li><b>让步接收</b>：轻微不良但客户同意放行（真实业务中很常见）</li>
 *   <li><b>返工</b>：可修复，退回工序重新加工</li>
 *   <li><b>不合格</b>：不可修复，报废或触发异常处理</li>
 * </ul>
 * 只有"合格/不合格"二元状态无法表达这些业务分支。
 * <b>知道"让步接收"这个词，比会写 CRUD 更能体现制造业业务理解。</b>
 */
@Data
@TableName("quality_inspection")
public class QualityInspection {

    public static final int TYPE_INCOMING = 1;
    public static final int TYPE_FIRST = 2;
    public static final int TYPE_PATROL = 3;
    public static final int TYPE_FINAL = 4;
    public static final int TYPE_PROCESS = 5;

    public static final int RESULT_PASS = 1;
    public static final int RESULT_FAIL = 2;
    public static final int RESULT_CONCESSION = 3;
    public static final int RESULT_REWORK = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inspectionNo;

    /** 1-来料检 2-首检 3-巡检 4-完工检 5-工序检 */
    private Integer inspectionType;

    private Long orderId;

    private String orderNo;

    private Long taskId;

    private Integer stepNo;

    private String stepName;

    private String batchNo;

    private BigDecimal inspectionQuantity;

    private BigDecimal qualifiedQuantity;

    private BigDecimal defectQuantity;

    /** 1-合格 2-不合格 3-让步接收 4-返工 */
    private Integer result;

    /** 1-待检 2-已完成 */
    private Integer status;

    private Long inspectorId;

    private String inspectorName;

    private LocalDateTime inspectionTime;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public static String resultDesc(Integer r) {
        if (r == null) {
            return "待判定";
        }
        return switch (r) {
            case RESULT_PASS -> "合格";
            case RESULT_FAIL -> "不合格";
            case RESULT_CONCESSION -> "让步接收";
            case RESULT_REWORK -> "返工";
            default -> "未知(" + r + ")";
        };
    }

    public static String typeDesc(Integer t) {
        if (t == null) {
            return "未知";
        }
        return switch (t) {
            case TYPE_INCOMING -> "来料检";
            case TYPE_FIRST -> "首检";
            case TYPE_PATROL -> "巡检";
            case TYPE_FINAL -> "完工检";
            case TYPE_PROCESS -> "工序检";
            default -> "未知(" + t + ")";
        };
    }
}
