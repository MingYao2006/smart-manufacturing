package com.smart.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关接口级 RBAC 的规则表测试。
 *
 * <p>这里只测「该不该放行」，不测下游业务语义（工单流转到哪个状态才允许谁点，
 * 由 smart-production 的 ProductionOrderService 自己判，见 requireTransitionRole）。
 */
class RoleAuthorizationFilterTest {

    private RoleAuthorizationFilter filter;
    private AtomicBoolean forwarded;

    @BeforeEach
    void setUp() {
        filter = new RoleAuthorizationFilter();
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        forwarded = new AtomicBoolean(false);
    }

    /** 放行时 chain 会被调用；拦截时返回 403 且 chain 不执行 */
    private int dispatch(HttpMethod method, String path, String role) {
        MockServerHttpRequest request = role == null
                ? MockServerHttpRequest.method(method, path).build()
                : MockServerHttpRequest.method(method, path).header("X-User-Role", role).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, ex -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return status == null ? 0 : status.value();
    }

    @Test
    @DisplayName("角色命中规则则透传到下游服务")
    void allowedRoleIsForwarded() {
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/work-task/9/dispatch", "PLANNER"));
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/report", "OPERATOR"));
        assertEquals(0, dispatch(HttpMethod.POST, "/inventory-service/inventory/inbound", "WAREHOUSE"));
        assertEquals(0, dispatch(HttpMethod.POST, "/quality-service/inspection", "INSPECTOR"));
        assertTrue(forwarded.get());
    }

    @Test
    @DisplayName("ADMIN 不用出现在规则里也能放行")
    void adminBypassesRules() {
        assertEquals(0, dispatch(HttpMethod.POST, "/product-service/product", "ADMIN"));
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/order/repair-stuck", "ADMIN"));
        assertTrue(forwarded.get());
    }

    @Test
    @DisplayName("越权调用返回 403 + 统一响应体，不转发")
    void forbiddenRoleIsBlocked() {
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/work-task/9/dispatch", "OPERATOR"));
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/report", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.POST, "/inventory-service/inventory/inbound", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.POST, "/quality-service/inspection", "OPERATOR"));
        assertFalse(forwarded.get());
    }

    @Test
    @DisplayName("主数据维护与运维口子为空规则 = 只允许 ADMIN")
    void adminOnlyEndpoints() {
        assertEquals(403, dispatch(HttpMethod.POST, "/product-service/product", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.PUT, "/product-service/material/3", "WAREHOUSE"));
        assertEquals(403, dispatch(HttpMethod.DELETE, "/product-service/route/7", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/order/repair-stuck", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/mq/outbox/flush", "OPERATOR"));
        assertEquals(403, dispatch(HttpMethod.POST, "/system/auth/hash", "PLANNER"));
    }

    @Test
    @DisplayName("操作工名单只给计划员派工用")
    void userDirectoryIsPlannerOnly() {
        assertEquals(0, dispatch(HttpMethod.GET, "/system/user/list", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.GET, "/system/user/list", "OPERATOR"));
    }

    @Test
    @DisplayName("未命中规则的接口（查询类）对所有已登录角色开放")
    void unlistedEndpointsStayOpen() {
        assertEquals(0, dispatch(HttpMethod.GET, "/production-service/order/page", "OPERATOR"));
        assertEquals(0, dispatch(HttpMethod.GET, "/inventory-service/inventory/material/1", "INSPECTOR"));
        assertTrue(forwarded.get());
    }

    @Test
    @DisplayName("没有身份头（白名单接口或网关鉴权关闭）时不拦，交给 AuthGlobalFilter")
    void noRoleHeaderIsForwarded() {
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/report", null));
        assertEquals(0, dispatch(HttpMethod.POST, "/system/auth/login", null));
        assertTrue(forwarded.get());
    }

    @Test
    @DisplayName("auth-enabled=false 时整条授权链不生效（本机联调开关）")
    void disabledAuthLetsEverythingThrough() {
        ReflectionTestUtils.setField(filter, "authEnabled", false);
        assertEquals(0, dispatch(HttpMethod.POST, "/product-service/product", "OPERATOR"));
        assertTrue(forwarded.get());
    }

    @Test
    @DisplayName("工单流转的统一入口按方法+路径命中，具体目标状态的收口在服务层")
    void orderTransitIsCoarseGrainedAtGateway() {
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/order/5/transit", "OPERATOR"));
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/order/5/transit", "WAREHOUSE"));
        assertEquals(0, dispatch(HttpMethod.POST, "/production-service/order/5/approve", "PLANNER"));
        assertEquals(403, dispatch(HttpMethod.POST, "/production-service/order/5/approve", "OPERATOR"));
    }

    @Test
    @DisplayName("拦截响应体保持 Result 结构，前端才能统一提示")
    void blockedResponseKeepsResultShape() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/production-service/report").header("X-User-Role", "PLANNER").build());
        filter.filter(exchange, ex -> Mono.empty()).block();
        String body = exchange.getResponse().getBodyAsString().block();
        assertEquals(403, exchange.getResponse().getStatusCode().value());
        assertTrue(body.contains("\"code\":40300"), body);
        assertTrue(body.contains("\"success\":false"), body);
    }
}
