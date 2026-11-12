package com.smart.common.auth;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色闸门的边界测试：哪些情况必须拒，哪些情况必须放行。
 *
 * <p>放行分支尤其重要——定时任务、MQ 消费者、单元测试都没有 HTTP 上下文，
 * 一旦在这里误拒，业务流就会在非登录场景下整条断掉。
 */
class RoleGuardTest {

    private void loginAs(String role, Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        if (userId != null) {
            request.addHeader("X-User-Id", String.valueOf(userId));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("没有 HTTP 上下文（定时任务/单测/MQ 消费）时不做角色校验")
    void skipWhenNoRequestContext() {
        assertFalse(RoleGuard.hasIdentity());
        assertDoesNotThrow(() -> RoleGuard.require("仅管理员", RoleGuard.ADMIN));
        assertDoesNotThrow(() -> RoleGuard.requireSelf(999L, "只能操作本人的"));
    }

    @Test
    @DisplayName("请求头没带角色（网关鉴权已关闭）时跳过校验")
    void skipWhenRoleHeaderMissing() {
        loginAs(null, 1L);
        assertFalse(RoleGuard.hasIdentity());
        assertDoesNotThrow(() -> RoleGuard.require("仅管理员"));
    }

    @Test
    @DisplayName("ADMIN 隐式放行，无需在每个调用点声明")
    void adminAlwaysPasses() {
        loginAs(RoleGuard.ADMIN, 1L);
        assertDoesNotThrow(() -> RoleGuard.require("仅管理员"));
        assertDoesNotThrow(() -> RoleGuard.requireSelf(999L, "只能操作本人的"));
    }

    @Test
    @DisplayName("角色命中允许列表则放行")
    void allowedRolePasses() {
        loginAs(RoleGuard.PLANNER, 2L);
        assertTrue(RoleGuard.hasIdentity());
        assertDoesNotThrow(() -> RoleGuard.require("派工只允许计划员", RoleGuard.PLANNER));
        assertDoesNotThrow(() -> RoleGuard.require("开工只允许计划员或操作工",
                RoleGuard.PLANNER, RoleGuard.OPERATOR));
    }

    @Test
    @DisplayName("角色不在允许列表 → 40300，提示带上当前角色中文名")
    void deniedRoleThrowsForbidden() {
        loginAs(RoleGuard.WAREHOUSE, 3L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> RoleGuard.require("派工只允许计划员", RoleGuard.PLANNER));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("仓库管理员"), e.getMessage());
    }

    @Test
    @DisplayName("允许列表为空 = 只有 ADMIN 能做")
    void emptyAllowListMeansAdminOnly() {
        loginAs(RoleGuard.INSPECTOR, 4L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> RoleGuard.require("基础资料维护只允许系统管理员执行"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("质检员"), e.getMessage());
    }

    @Test
    @DisplayName("归属校验：只能操作派给本人的记录")
    void requireSelfComparesUserId() {
        loginAs(RoleGuard.OPERATOR, 11L);
        assertDoesNotThrow(() -> RoleGuard.requireSelf(11L, "只能操作派给本人的工序"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> RoleGuard.requireSelf(12L, "只能操作派给本人的工序"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("车间操作工"), e.getMessage());

        // 未派工（负责人为空）同样拒绝：避免操作工抢未分配工序的口径被绕过
        assertThrows(BusinessException.class,
                () -> RoleGuard.requireSelf(null, "只能操作派给本人的工序"));
    }
}
