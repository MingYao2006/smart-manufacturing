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
 * 工序任务状态机（第 5 周任务 5.2）。
 *
 * <p>迁移表：
 * <pre>
 *   PENDING        → IN_PROGRESS, SKIPPED
 *   IN_PROGRESS    → PAUSED, COMPLETED, WAIT_INSPECTION
 *   PAUSED         → IN_PROGRESS
 *   WAIT_INSPECTION→ COMPLETED（质检合格）, IN_PROGRESS（质检不合格，返工）
 *   COMPLETED      → （终态）
 *   SKIPPED        → （终态）
 * </pre>
 *
 * <p>为什么要有 {@code WAIT_INSPECTION}：
 * {@code is_inspection=1} 的工序报工完成后不是直接 COMPLETED，
 * 而是先等质检。合格才放行到下一道，不合格退回 IN_PROGRESS 返工。
 * 如果只有 COMPLETED 一个终态，就无法表达"工序做完了但还没放行"这个状态，
 * 导致质检门禁形同虚设。
 *
 * <p>为什么要有 {@code PAUSED}：实际生产中设备故障、换料、交接班都会暂停。
 * 没有暂停状态，工人只能"假装没开始"或"假装已完成"，数据就失真了。
 */
@Getter
public enum WorkTaskStatus {

    PENDING(1, "待开始"),
    IN_PROGRESS(2, "进行中"),
    PAUSED(3, "暂停"),
    COMPLETED(4, "已完成"),
    SKIPPED(5, "已跳过"),
    WAIT_INSPECTION(6, "待质检");

    private static final Map<WorkTaskStatus, Set<WorkTaskStatus>> TRANSITIONS;

    static {
        Map<WorkTaskStatus, Set<WorkTaskStatus>> m = new EnumMap<>(WorkTaskStatus.class);
        m.put(PENDING, EnumSet.of(IN_PROGRESS, SKIPPED));
        m.put(IN_PROGRESS, EnumSet.of(PAUSED, COMPLETED, WAIT_INSPECTION));
        m.put(PAUSED, EnumSet.of(IN_PROGRESS));
        m.put(WAIT_INSPECTION, EnumSet.of(COMPLETED, IN_PROGRESS));
        m.put(COMPLETED, EnumSet.noneOf(WorkTaskStatus.class));
        m.put(SKIPPED, EnumSet.noneOf(WorkTaskStatus.class));

        Map<WorkTaskStatus, Set<WorkTaskStatus>> ro = new EnumMap<>(WorkTaskStatus.class);
        m.forEach((k, v) -> ro.put(k, Collections.unmodifiableSet(v)));
        TRANSITIONS = Collections.unmodifiableMap(ro);
    }

    private final int code;
    private final String desc;

    WorkTaskStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WorkTaskStatus of(Integer code) {
        if (code == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工序任务状态为空");
        }
        for (WorkTaskStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "未知的工序任务状态: " + code);
    }

    public static String descOf(Integer code) {
        if (code == null) {
            return "未知";
        }
        for (WorkTaskStatus s : values()) {
            if (s.code == code) {
                return s.desc;
            }
        }
        return "未知(" + code + ")";
    }

    public boolean canTransitTo(WorkTaskStatus target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public void checkTransitTo(WorkTaskStatus target) {
        if (!canTransitTo(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    String.format("工序状态不允许从[%s]流转到[%s]",
                            this.desc, target == null ? "null" : target.desc));
        }
    }

    public Set<WorkTaskStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
