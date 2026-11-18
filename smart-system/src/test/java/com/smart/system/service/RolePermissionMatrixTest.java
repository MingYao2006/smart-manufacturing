package com.smart.system.service;

import com.smart.common.auth.RolePermissionMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色权限矩阵（唯一真源）的形状测试：
 * 保证下发给前端的结构不被改动破坏（ADMIN=全部、各角色视图与前端口径一致）。
 */
@DisplayName("角色权限矩阵：唯一真源")
class RolePermissionMatrixTest {

    @Test
    @DisplayName("asMap 含 5 个角色，顺序为 ADMIN/PLANNER/WAREHOUSE/OPERATOR/INSPECTOR")
    void fiveRolesInOrder() {
        Map<String, Map<String, Object>> roles = RolePermissionMatrix.asMap();
        assertEquals(5, roles.size());
        assertEquals(
                List.of("ADMIN", "PLANNER", "WAREHOUSE", "OPERATOR", "INSPECTOR"),
                List.copyOf(roles.keySet()));
    }

    @Test
    @DisplayName("ADMIN 的 views/permissions 为 null（表示全部）且 all=true")
    void adminMeansAll() {
        Map<String, Object> admin = RolePermissionMatrix.asMap().get("ADMIN");
        assertEquals("系统管理员", admin.get("label"));
        assertEquals(Boolean.TRUE, admin.get("all"));
        assertNull(admin.get("views"));
        assertNull(admin.get("permissions"));
    }

    @Test
    @DisplayName("WAREHOUSE 的 views 恰为 [today, materials, purchase]，且 all=false")
    void warehouseViews() {
        Map<String, Object> warehouse = RolePermissionMatrix.asMap().get("WAREHOUSE");
        assertEquals("仓库管理员", warehouse.get("label"));
        assertEquals(Boolean.FALSE, warehouse.get("all"));
        assertEquals(List.of("today", "materials", "purchase"), warehouse.get("views"));
    }

    @Test
    @DisplayName("ALL_VIEWS 与 ROLE_LABELS 均非空")
    void constantsNotEmpty() {
        assertEquals(8, RolePermissionMatrix.ALL_VIEWS.size());
        assertTrue(RolePermissionMatrix.ROLE_LABELS.containsKey("ADMIN"));
    }

    @Test
    @DisplayName("INSPECTOR 除质检权限外还能执行派给自己的质检点工序")
    @SuppressWarnings("unchecked")
    void inspectorCanExecuteAssignedInspectionTask() {
        Map<String, Object> inspector = RolePermissionMatrix.asMap().get("INSPECTOR");
        assertEquals(Boolean.FALSE, inspector.get("all"));
        List<String> permissions = (List<String>) inspector.get("permissions");
        // 质检点工序（如老化测试）会派给质检员，因此必须能开始/完成工序与报工
        assertTrue(permissions.contains("task.execute"), String.valueOf(permissions));
        assertTrue(permissions.contains("report.create"), String.valueOf(permissions));
        // 质检放行/退回仍专属于质检员
        assertTrue(permissions.contains("task.inspect"), String.valueOf(permissions));
        assertTrue(permissions.contains("inspection.create"), String.valueOf(permissions));
    }
}
