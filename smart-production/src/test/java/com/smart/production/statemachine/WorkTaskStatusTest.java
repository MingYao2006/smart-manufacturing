package com.smart.production.statemachine;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序任务状态机测试（第 5 周任务 5.2 / 第 7 周任务 7.1）。
 *
 * <p>重点验证质检门禁相关的状态迁移：
 * <ul>
 *   <li>{@code IN_PROGRESS → WAIT_INSPECTION}（报工完成且是质检点）</li>
 *   <li>{@code WAIT_INSPECTION → COMPLETED}（质检合格，放行）</li>
 *   <li>{@code WAIT_INSPECTION → IN_PROGRESS}（质检不合格，返工）</li>
 * </ul>
 */
@DisplayName("工序任务状态机")
class WorkTaskStatusTest {

    @ParameterizedTest(name = "{0} → {1} 应该允许")
    @CsvSource({
            "PENDING,         IN_PROGRESS",
            "PENDING,         SKIPPED",
            "IN_PROGRESS,     PAUSED",
            "IN_PROGRESS,     COMPLETED",
            "IN_PROGRESS,     WAIT_INSPECTION",
            "PAUSED,          IN_PROGRESS",
            "WAIT_INSPECTION, COMPLETED",
            "WAIT_INSPECTION, IN_PROGRESS"
    })
    @DisplayName("合法迁移")
    void testLegalTransitions(WorkTaskStatus from, WorkTaskStatus to) {
        assertTrue(from.canTransitTo(to),
                String.format("期望允许 %s → %s", from.getDesc(), to.getDesc()));
        from.checkTransitTo(to);
    }

    @Test
    @DisplayName("合法迁移总数应恰好为 8 条")
    void testTotalLegalTransitionsCount() {
        int total = 0;
        for (WorkTaskStatus s : WorkTaskStatus.values()) {
            total += s.allowedTargets().size();
        }
        assertEquals(8, total);
    }

    @ParameterizedTest(name = "{0} → {1} 应该被拒绝")
    @CsvSource({
            // 未开始不能直接完成
            "PENDING,         COMPLETED",
            // 未开始不能直接暂停
            "PENDING,         PAUSED",
            // 未开始不能直接进待质检
            "PENDING,         WAIT_INSPECTION",
            // 暂停中不能直接完成（必须先恢复）
            "PAUSED,          COMPLETED",
            // 已完成是终态
            "COMPLETED,       IN_PROGRESS",
            "COMPLETED,       PENDING",
            // 已跳过是终态
            "SKIPPED,         IN_PROGRESS",
            "SKIPPED,         COMPLETED",
            // 不能回退到待开始
            "IN_PROGRESS,     PENDING",
            "WAIT_INSPECTION, PENDING",
            // 不能跳过质检直接变成"待质检"（已经是了）
            "WAIT_INSPECTION, WAIT_INSPECTION",
            // 自流转
            "PENDING,         PENDING"
    })
    @DisplayName("非法迁移：必须被拦截")
    void testIllegalTransitions(WorkTaskStatus from, WorkTaskStatus to) {
        assertFalse(from.canTransitTo(to));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> from.checkTransitTo(to));
        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("COMPLETED / SKIPPED 是终态")
    void testTerminalStates() {
        assertTrue(WorkTaskStatus.COMPLETED.isTerminal());
        assertTrue(WorkTaskStatus.SKIPPED.isTerminal());
        assertFalse(WorkTaskStatus.WAIT_INSPECTION.isTerminal(),
                "待质检不是终态：质检后要能放行或退回返工");
    }

    @Test
    @DisplayName("WAIT_INSPECTION 必须有两条出边（放行 + 返工）")
    void testWaitInspectionHasTwoExits() {
        Set<WorkTaskStatus> targets = WorkTaskStatus.WAIT_INSPECTION.allowedTargets();
        assertEquals(2, targets.size());
        assertTrue(targets.contains(WorkTaskStatus.COMPLETED), "应该能放行");
        assertTrue(targets.contains(WorkTaskStatus.IN_PROGRESS), "应该能退回返工");
    }

    @Test
    @DisplayName("状态码唯一且可双向映射")
    void testCodeMapping() {
        Set<Integer> codes = new HashSet<>();
        for (WorkTaskStatus s : WorkTaskStatus.values()) {
            assertTrue(codes.add(s.getCode()), "状态码重复: " + s.getCode());
            assertEquals(s, WorkTaskStatus.of(s.getCode()));
            assertEquals(s.getDesc(), WorkTaskStatus.descOf(s.getCode()));
        }
        assertThrows(BusinessException.class, () -> WorkTaskStatus.of(999));
    }
}
