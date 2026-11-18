package com.smart.common.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色 → 视图模块 / 操作权限的<b>唯一真源</b>。
 *
 * <p>前端侧栏菜单、按钮显隐的展示口径只认这里：由 smart-system 的
 * {@code GET /auth/permissions} 通过 {@code PermissionMatrixVO} 统一下发，
 * 前端不再各自硬编码角色矩阵。改角色可见范围时，只需改本类常量并让前端重新拉取接口。
 *
 * <h3>三道防线的分工（粒度不同，不要互相替代）</h3>
 * <ul>
 *   <li><b>本类</b>：角色 → 视图/权限的静态映射，只服务前端「能不能看见」的展示口径，
 *       由 HTTP 接口下发，纯配置、不含任何用户数据。</li>
 *   <li><b>网关 RoleAuthorizationFilter</b>：按「HTTP 方法 + 路径」做接口级 RBAC，
 *       判断「能不能调这个接口」，是真正的访问控制第一道防线。</li>
 *   <li><b>RoleGuard（本包）</b>：按「业务语义」在服务端方法内做角色/归属校验
 *       （如流转目标状态、是否派给本人），路径看不出来的在这里判，是第二道防线。</li>
 * </ul>
 *
 * <p>⚠️ 前端按角色隐藏按钮只是体验，直接调接口就能绕过；因此访问控制的真值在后面两道防线。
 * 改动接口（新增/调整 URL 或角色规则）时仍需同步核对网关与 RoleGuard 的配置，
 * 但「前端看到什么」这一展示口径只以本类为准。
 */
public final class RolePermissionMatrix {

    /** 全部视图模块，顺序与前端侧栏顺序一致 */
    public static final List<String> ALL_VIEWS = List.of(
            "today", "orders", "tasks", "materials", "basic", "trace", "quality", "purchase");

    /** 角色中文名，键顺序为 ADMIN, PLANNER, WAREHOUSE, OPERATOR, INSPECTOR */
    public static final Map<String, String> ROLE_LABELS = roleLabels();

    /** 角色 → 具体权限点；ADMIN 用 null 表示「全部权限」 */
    private static final Map<String, List<String>> PERMISSIONS = rolePermissions();

    /** 角色 → 可见视图；ADMIN 用 null 表示「全部模块」 */
    private static final Map<String, List<String>> VIEWS = roleViews();

    private RolePermissionMatrix() {
    }

    /**
     * 返回每个角色的描述 Map，供接口下发。
     *
     * <p>结构：
     * <pre>
     * {
     *   "ADMIN":   { "label":"系统管理员", "all":true,  "views":null, "permissions":null },
     *   "PLANNER": { "label":"生产计划员", "all":false, "views":[...], "permissions":[...] },
     *   ...
     * }
     * </pre>
     * ADMIN 的 views/permissions 为 Java {@code null}（序列化成 JSON null）表示全部，
     * 并带 {@code "all": true}；其余角色带具体 List 且 {@code "all": false}。
     * 用 {@link LinkedHashMap} 保证顺序 ADMIN, PLANNER, WAREHOUSE, OPERATOR, INSPECTOR。
     */
    public static Map<String, Map<String, Object>> asMap() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String role : ROLE_LABELS.keySet()) {
            boolean all = "ADMIN".equals(role);
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("label", ROLE_LABELS.get(role));
            desc.put("all", all);
            desc.put("views", all ? null : VIEWS.get(role));
            desc.put("permissions", all ? null : PERMISSIONS.get(role));
            result.put(role, desc);
        }
        return result;
    }

    private static Map<String, String> roleLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ADMIN", "系统管理员");
        m.put("PLANNER", "生产计划员");
        m.put("WAREHOUSE", "仓库管理员");
        m.put("OPERATOR", "车间操作工");
        m.put("INSPECTOR", "质检员");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, List<String>> rolePermissions() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("ADMIN", null);
        m.put("PLANNER", List.of(
                "order.create", "order.plan", "order.execute", "task.dispatch",
                "inv.reserve", "user.list", "purchase.approve"));
        m.put("WAREHOUSE", List.of(
                "inv.reserve", "inv.issue", "inv.reconcile", "inv.inbound", "purchase.create"));
        m.put("OPERATOR", List.of(
                "order.execute", "task.execute", "report.create"));
        // 质检员也要能执行派给自己的质检点工序（如「老化测试」），
        // 完成后再用 task.inspect 做放行 / 退回。
        m.put("INSPECTOR", List.of(
                "task.inspect", "inspection.create", "task.execute", "report.create"));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, List<String>> roleViews() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("ADMIN", null);
        m.put("PLANNER", List.of(
                "today", "orders", "tasks", "materials", "basic", "trace", "purchase"));
        m.put("WAREHOUSE", List.of(
                "today", "materials", "purchase"));
        m.put("OPERATOR", List.of(
                "today", "tasks", "orders"));
        m.put("INSPECTOR", List.of(
                "today", "quality", "orders"));
        return Collections.unmodifiableMap(m);
    }
}
