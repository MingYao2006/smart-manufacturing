package com.smart.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领料批次关系 outbox —— <b>与领料扣减同事务写入</b>的待发消息。
 *
 * <h3>解决什么问题</h3>
 * 领料是 smart-inventory 的本地事务，而"建一条追溯边"在 smart-quality。
 * 若在领料里直接 HTTP 调质量服务，一旦超时/对方宕机，就是
 * <b>"料发了本子没写"</b>：库存扣了，追溯链永久断裂（事后补不回来）。
 *
 * <p>做法：把"某工单领了某批次料"作为一条记录，和扣库存、写流水放在同一个
 * 本地事务里。事务一提交，这条消息必然在库里；进程崩溃重启后定时扫描仍能补发。
 * 于是领料与"记下来"变成<b>最终一致</b>。
 *
 * <h3>target 为什么为空</h3>
 * 领料发生在生产之前，产品批次要等完工才生成，所以边里的 target 本来就是空的，
 * <b>不是漏写</b>。完工生成产品批次后，按 {@code orderId} 把空 target 的边统一回填。
 * 数据已在库里，晚一点补也不会丢，所以回填同样可以走异步。
 */
@Data
@TableName("material_issue_outbox")
public class MaterialIssueOutbox {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：{@code orderId:batchNo}，同一工单同一批次只发一次 */
    private String bizKey;

    private Long orderId;

    private String orderNo;

    private String issueNo;

    private Long materialId;

    private String materialCode;

    /** 领用的物料批次号，追溯链路起点 */
    private String batchNo;

    private BigDecimal quantity;

    /** 待投递消息体 JSON */
    private String payload;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime sentTime;
}
