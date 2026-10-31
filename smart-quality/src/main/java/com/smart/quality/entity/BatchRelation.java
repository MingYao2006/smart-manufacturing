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
 * 批次关系链。追溯图的 <b>边</b>：消耗了哪些批次、产出了哪个批次。
 *
 * <h3>为什么是【有向图】而不是【树】</h3>
 * <pre>
 *   一个物料批次 → 可能被多个工单消耗（1:N）
 *   一个产品批次 → 可能消耗多个物料批次（N:1）
 *   ⇒ 多对多的有向无环图（DAG）
 * </pre>
 *
 * <p>追溯就是图遍历：
 * <ul>
 *   <li><b>反向追溯</b>（成品→原料）：从 target 沿 source 方向 BFS →
 *       查出这个产品用了哪些料、哪个供应商 → 定位客诉根因</li>
 *   <li><b>正向追溯</b>（原料→成品）：从 source 沿 target 方向 BFS →
 *       查出这个料被哪些成品用了 → 精准召回，不用全部召回</li>
 * </ul>
 *
 * <p>🔑 {@code productionOrderId}：追溯结果要能定位"哪个工单出了问题"，
 * 方便召回和整改。
 */
@Data
@TableName("batch_relation")
public class BatchRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源批次（被消耗的物料批次） */
    private Long sourceBatchId;

    private String sourceBatchNo;

    /** 目标批次（产出的产品批次） */
    private Long targetBatchId;

    private String targetBatchNo;

    private Long productionOrderId;

    private String orderNo;

    /** 消耗数量 */
    private BigDecimal quantity;

    /** CONSUME-领料消耗 OUTPUT-完工产出 */
    private String relationType;

    /** 是否被退料冲正 */
    private Integer isReversed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
