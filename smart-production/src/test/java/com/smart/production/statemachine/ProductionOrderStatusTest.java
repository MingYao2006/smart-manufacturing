package com.smart.production.statemachine;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机测试（第 3 周任务 3.6 / 第 7 周任务 7.1）。
 *
 * <p>验收标准：
 * <ul>
 *   <li>覆盖 <b>100% 的合法迁移</b></li>
 *   <li>覆盖 <b>至少 10 条非法迁移</b></li>
 *   <li>终态不可再流转</li>
 * </ul>
 *
 * <p>这是纯内存逻辑测试，不需要 Spring 容器和数据库 —— 快且稳定。
 */
@DisplayName("工单状态机")
class ProductionOrderStatusTest {

    /* ==================== 合法迁移（全覆盖） ==================== */

    @ParameterizedTest(name = "{0} → {1} 应该允许")
    @CsvSource({
            "DRAFT,       APPROVED",
            "DRAFT,       CANCELLED",
            "APPROVED,    RELEASED",
            "APPROVED,    CANCELLED",
            "RELEASED,    IN_PROGRESS",
            "RELEASED,    CANCELLED",
            "IN_PROGRESS, PAUSED",
            "IN_PROGRESS, COMPLETED",
            "IN_PROGRESS, CANCELLED",
            "PAUSED,      IN_PROGRESS",
            "PAUSED,      CANCELLED",
            "COMPLETED,   CLOSED"
    })
    @DisplayName("合法迁移：覆盖迁移表中的每一条边")
    void testLegalTransitions(ProductionOrderStatus from, ProductionOrderStatus to) {
        assertTrue(from.canTransitTo(to),
                String.format("期望允许 %s → %s", from.getDesc(), to.getDesc()));
        // checkTransitTo 不应抛异常
        from.checkTransitTo(to);
    }

    @Test
    @DisplayName("合法迁移总数应恰好为 12 条（迁移表的边数）")
    void testTotalLegalTransitionsCount() {
        int total = 0;
        for (ProductionOrderStatus s : ProductionOrderStatus.values()) {
            total += s.allowedTargets().size();
        }
        assertEquals(12, total, "迁移表边数变化时请同步更新本测试与文档");
    }

    /* ==================== 非法迁移（≥10 条） ==================== */

    @ParameterizedTest(name = "{0} → {1} 应该被拒绝")
    @CsvSource({
            // 跳过审核直接下达
            "DRAFT,       RELEASED",
            // 跳过审核直接开工
            "DRAFT,       IN_PROGRESS",
            // 跳过审核直接完工
            "DRAFT,       COMPLETED",
            // 已审核不能直接开工（必须先下达）
            "APPROVED,    IN_PROGRESS",
            // 已审核不能直接完工
            "APPROVED,    COMPLETED",
            // 已下达不能直接完工（必须先开工）
            "RELEASED,    COMPLETED",
            // 已下达不能直接暂停
            "RELEASED,    PAUSED",
            // 生产中不能回退到已下达
            "IN_PROGRESS, RELEASED",
            // 生产中不能回退到草稿
            "IN_PROGRESS, DRAFT",
            // 暂停中不能直接完工
            "PAUSED,      COMPLETED",
            // 完工后不能取消
            "COMPLETED,   CANCELLED",
            // 完工后不能回到生产中
            "COMPLETED,   IN_PROGRESS",
            // 终态 CLOSED 不能流转到任何状态
            "CLOSED,      DRAFT",
            "CLOSED,      IN_PROGRESS",
            // 终态 CANCELLED 不能流转到任何状态
            "CANCELLED,   APPROVED",
            "CANCELLED,   IN_PROGRESS",
            // 不能自流转
            "DRAFT,       DRAFT",
            "IN_PROGRESS, IN_PROGRESS"
    })
    @DisplayName("非法迁移：必须被拦截并抛业务异常")
    void testIllegalTransitions(ProductionOrderStatus from, ProductionOrderStatus to) {
        assertFalse(from.canTransitTo(to),
                String.format("期望拒绝 %s → %s", from.getDesc(), to.getDesc()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> from.checkTransitTo(to));
        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), ex.getCode(),
                "非法迁移应返回 STATE_TRANSITION_DENIED(40901)");
        assertTrue(ex.getMessage().contains("不允许"),
                "异常信息应说明状态不允许流转，实际: " + ex.getMessage());
    }

    /* ==================== 终态与边界 ==================== */

    @Test
    @DisplayName("CLOSED / CANCELLED 是终态，没有任何出边")
    void testTerminalStates() {
        assertTrue(ProductionOrderStatus.CLOSED.isTerminal());
        assertTrue(ProductionOrderStatus.CANCELLED.isTerminal());
        assertEquals(Set.of(), ProductionOrderStatus.CLOSED.allowedTargets());
        assertEquals(Set.of(), ProductionOrderStatus.CANCELLED.allowedTargets());
    }

    @Test
    @DisplayName("非终态必须至少有一条出边（不存在死胡同）")
    void testNonTerminalStatesHaveExits() {
        for (ProductionOrderStatus s : ProductionOrderStatus.values()) {
            if (s == ProductionOrderStatus.CLOSED || s == ProductionOrderStatus.CANCELLED) {
                continue;
            }
            assertFalse(s.allowedTargets().isEmpty(),
                    s.getDesc() + " 不应是死胡同");
        }
    }

    @Test
    @DisplayName("null 目标状态应被拒绝而不是抛 NPE")
    void testNullTarget() {
        assertFalse(ProductionOrderStatus.DRAFT.canTransitTo(null));
        assertThrows(BusinessException.class,
                () -> ProductionOrderStatus.DRAFT.checkTransitTo(null));
    }

    @Test
    @DisplayName("状态码与枚举的双向映射")
    void testCodeMapping() {
        for (ProductionOrderStatus s : ProductionOrderStatus.values()) {
            assertEquals(s, ProductionOrderStatus.of(s.getCode()));
            assertEquals(s.getDesc(), ProductionOrderStatus.descOf(s.getCode()));
        }
        // 非法状态码
        assertThrows(BusinessException.class, () -> ProductionOrderStatus.of(999));
        assertThrows(BusinessException.class, () -> ProductionOrderStatus.of(null));
    }

    @Test
    @DisplayName("每个状态的状态码唯一")
    void testUniqueCodes() {
        Set<Integer> codes = new java.util.HashSet<>();
        for (ProductionOrderStatus s : ProductionOrderStatus.values()) {
            assertTrue(codes.add(s.getCode()), "状态码重复: " + s.getCode());
            assertNotNull(s.getDesc());
        }
    }
}
