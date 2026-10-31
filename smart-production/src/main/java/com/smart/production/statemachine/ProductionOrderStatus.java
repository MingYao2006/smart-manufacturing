package com.smart.production.statemachine;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 生产工单状态机（第 3 周任务 3.4，本周最重要，面试高频）。
 *
 * <h3>为什么用"枚举 + 迁移表"而不是 if-else</h3>
 * if-else 写法下，加一个状态要改 5 处（每个 if 分支），而且容易漏。
 * 迁移表把"合法迁移"集中在一处声明，加状态只改这里 + 副作用 switch 一处。
 *
 * <h3>迁移表</h3>
 * <pre>
 *   DRAFT       → APPROVED, CANCELLED
 *   APPROVED    → RELEASED, CANCELLED
 *   RELEASED    → IN_PROGRESS, CANCELLED   （仅未开工）
 *   IN_PROGRESS → PAUSED, COMPLETED, CANCELLED
 *   PAUSED      → IN_PROGRESS, CANCELLED
 *   COMPLETED   → CLOSED
 *   CLOSED      → （终态）
 *   CANCELLED   → （终态）
 * </pre>
 *
 * <h3>并发安全（关键）</h3>
 * 光有内存判断不够 —— 工单状态会被多个入口并发修改（人工操作、报工消息、定时任务）。
 * 必须配合数据库 <b>CAS 条件更新</b>：{@code UPDATE ... WHERE id=? AND status=?}，
 * 影响行数 = 1 才算成功。这和电商项目订单状态踩过的坑完全一样。
 */
@Getter
public enum ProductionOrderStatus {

    DRAFT(1, "草稿"),
    APPROVED(2, "已审核"),
    RELEASED(3, "已下达"),
    IN_PROGRESS(4, "生产中"),
    PAUSED(5, "暂停"),
    COMPLETED(6, "已完工"),
    CLOSED(7, "已关闭"),
    CANCELLED(8, "已取消");

    /**
     * 合法迁移表：状态 → 允许的目标状态集合。
     *
     * <p>用 EnumMap + EnumSet，避免误改（不可变包装）。
     */
    private static final Map<ProductionOrderStatus, Set<ProductionOrderStatus>> TRANSITIONS;

    static {
        Map<ProductionOrderStatus, Set<ProductionOrderStatus>> m = new EnumMap<>(ProductionOrderStatus.class);
        m.put(DRAFT, EnumSet.of(APPROVED, CANCELLED));
        m.put(APPROVED, EnumSet.of(RELEASED, CANCELLED));
        m.put(RELEASED, EnumSet.of(IN_PROGRESS, CANCELLED));
        m.put(IN_PROGRESS, EnumSet.of(PAUSED, COMPLETED, CANCELLED));
        m.put(PAUSED, EnumSet.of(IN_PROGRESS, CANCELLED));
        m.put(COMPLETED, EnumSet.of(CLOSED));
        m.put(CLOSED, EnumSet.noneOf(ProductionOrderStatus.class));
        m.put(CANCELLED, EnumSet.noneOf(ProductionOrderStatus.class));

        Map<ProductionOrderStatus, Set<ProductionOrderStatus>> ro = new EnumMap<>(ProductionOrderStatus.class);
        m.forEach((k, v) -> ro.put(k, Collections.unmodifiableSet(v)));
        TRANSITIONS = Collections.unmodifiableMap(ro);
    }

    private final int code;
    private final String desc;

    ProductionOrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProductionOrderStatus of(Integer code) {
        if (code == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工单状态为空");
        }
        for (ProductionOrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "未知的工单状态: " + code);
    }

    public static String descOf(Integer code) {
        if (code == null) {
            return "未知";
        }
        for (ProductionOrderStatus s : values()) {
            if (s.code == code) {
                return s.desc;
            }
        }
        return "未知(" + code + ")";
    }

    /**
     * 是否允许从本状态迁移到目标状态。
     */
    public boolean canTransitTo(ProductionOrderStatus target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * 校验迁移合法性，非法则抛业务异常。
     *
     * <p>注意：这只是"快速失败"的内存判断，
     * <b>真正的并发防线是数据库 CAS</b>（见 ProductionOrderService.transit）。
     */
    public void checkTransitTo(ProductionOrderStatus target) {
        if (!canTransitTo(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    String.format("工单状态不允许从[%s]流转到[%s]", this.desc, target == null ? "null" : target.desc));
        }
    }

    public Set<ProductionOrderStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    /**
     * 是否为终态（不可再流转）。
     */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
