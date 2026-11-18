package com.smart.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 网关全局过滤器二：接口级角色授权（RBAC）。
 *
 * <p>与 {@link AuthGlobalFilter} 的分工：那个过滤器只管<b>你是谁</b>（验签 JWT、透传
 * {@code X-User-Role}），这个只管<b>你能不能调这个接口</b>。两层都必须有——
 * 前端按角色隐藏按钮只是体验，直接调接口就能绕过。
 *
 * <h3>两道防线的粒度不同</h3>
 * <ul>
 *   <li>网关：按<b>方法 + 路径</b>拦，一处集中配置，新增接口漏配也不会放开。</li>
 *   <li>服务：按<b>业务语义</b>拦，路径看不出来的这里判。
 *       例如工单流转都走 {@code POST /order/{id}/transit}，
 *       但"审核/下达"只允许计划员、"开工/完工"操作工也能点，区别在请求体的 targetStatus，
 *       见 smart-production 的 ProductionOrderService#requireTransitionRole；
 *       "操作工只能动派给自己的工序"也要读库才知道，见 WorkTaskService#requireExecutor。</li>
 * </ul>
 *
 * <p>ADMIN 一律放行，不用在每条规则里重复声明。
 * 没有 {@code X-User-Role} 头（网关鉴权被关闭或白名单接口）时不拦，
 * 口径与 smart-common 的 RoleGuard 保持一致。
 */
@Slf4j
@Component
public class RoleAuthorizationFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final String ADMIN = "ADMIN";

    /** 一条规则 = 方法 + 路径通配 + 除 ADMIN 外允许的角色 */
    private record Rule(HttpMethod method, String pattern, Set<String> roles) {
    }

    private static final List<Rule> RULES = List.of(
            // ---------- 生产：工单 ----------
            new Rule(HttpMethod.POST, "/production-service/order", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/order/*/transit", Set.of("PLANNER", "OPERATOR")),
            new Rule(HttpMethod.POST, "/production-service/order/*/approve", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/order/*/release", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/order/*/cancel", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/order/*/recalculate", Set.of("PLANNER")),
            // 修数接口属于运维，不给业务角色
            new Rule(HttpMethod.POST, "/production-service/order/repair-stuck", Set.of()),

            // ---------- 生产：工序与报工 ----------
            // 工序执行放行「操作工 + 质检员」：质检点工序（如老化测试）常由计划员
            // 直接派给质检员，只放行 OPERATOR 会派了没人能执行。
            // 「只能动派给本人的工序」由 smart-production 的 WorkTaskService#requireExecutor 判，
            // 放行 / 退回仍只有质检员能做（pass-inspection / reject-inspection）。
            new Rule(HttpMethod.POST, "/production-service/work-task/*/dispatch", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/start", Set.of("OPERATOR", "INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/pause", Set.of("OPERATOR", "INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/resume", Set.of("OPERATOR", "INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/complete", Set.of("OPERATOR", "INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/pass-inspection", Set.of("INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/work-task/*/reject-inspection", Set.of("INSPECTOR")),
            new Rule(HttpMethod.POST, "/production-service/report", Set.of("OPERATOR", "INSPECTOR")),

            // ---------- 生产：计划单 ----------
            new Rule(HttpMethod.POST, "/production-service/plan", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/plan/*/confirm", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/production-service/plan/*/convert", Set.of("PLANNER")),

            // ---------- 生产：MQ 运维口子 ----------
            new Rule(HttpMethod.POST, "/production-service/mq/**", Set.of()),

            // ---------- 基础资料：只有管理员能改主数据 ----------
            // 与前端 basic.write（仅 ADMIN）保持一致：网关这一层原本是 Set.of()（对所有已登录角色放行），
            // 属于越权口子，这里收口为 ADMIN，防止接口绕过前端隐藏的按钮。
            new Rule(HttpMethod.POST, "/product-service/product", Set.of("ADMIN")),
            new Rule(HttpMethod.PUT, "/product-service/product/*", Set.of("ADMIN")),
            new Rule(HttpMethod.DELETE, "/product-service/product/*", Set.of("ADMIN")),
            new Rule(HttpMethod.POST, "/product-service/material", Set.of("ADMIN")),
            new Rule(HttpMethod.PUT, "/product-service/material/*", Set.of("ADMIN")),
            new Rule(HttpMethod.DELETE, "/product-service/material/*", Set.of("ADMIN")),
            new Rule(HttpMethod.POST, "/product-service/bom", Set.of("ADMIN")),
            new Rule(HttpMethod.POST, "/product-service/bom/*/activate", Set.of("ADMIN")),
            new Rule(HttpMethod.DELETE, "/product-service/bom/*", Set.of("ADMIN")),
            new Rule(HttpMethod.POST, "/product-service/route", Set.of("ADMIN")),
            new Rule(HttpMethod.POST, "/product-service/route/*/activate", Set.of("ADMIN")),
            new Rule(HttpMethod.DELETE, "/product-service/route/*", Set.of("ADMIN")),

            // ---------- 库存 ----------
            new Rule(HttpMethod.POST, "/inventory-service/inventory/inbound", Set.of("WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/inventory/reconcile", Set.of("WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/reservation/reserve", Set.of("PLANNER", "WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/reservation/release/*", Set.of("PLANNER", "WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/issue", Set.of("WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/issue/return", Set.of("WAREHOUSE")),

            // ---------- 库存：进货审批单（仓管申请，管理员/计划员或签） ----------
            // 提交 / 撤销只允许仓库管理员；审批（通过/驳回）管理员或计划员任一人即可，ADMIN 统一放行。
            new Rule(HttpMethod.POST, "/inventory-service/purchase", Set.of("WAREHOUSE")),
            new Rule(HttpMethod.POST, "/inventory-service/purchase/*/approve", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/inventory-service/purchase/*/reject", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/inventory-service/purchase/*/cancel", Set.of("WAREHOUSE")),

            // ---------- 质量 ----------
            new Rule(HttpMethod.POST, "/quality-service/inspection", Set.of("INSPECTOR")),
            new Rule(HttpMethod.POST, "/quality-service/batch/*", Set.of("INSPECTOR", "WAREHOUSE")),

            // ---------- 系统 ----------
            // 操作工名单只给派工用（计划员派工时选择操作工）
            new Rule(HttpMethod.GET, "/system/user/list", Set.of("PLANNER")),
            new Rule(HttpMethod.POST, "/system/auth/hash", Set.of())
    );

    @Value("${smart.gateway.auth-enabled:true}")
    private boolean authEnabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!authEnabled) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String role = request.getHeaders().getFirst("X-User-Role");
        // 白名单接口（登录、文档）没有身份头，交给 AuthGlobalFilter 处理
        if (role == null || role.isBlank()) {
            return chain.filter(exchange);
        }

        Rule rule = matchRule(request.getMethod(), request.getURI().getPath());
        if (rule == null || ADMIN.equals(role) || rule.roles().contains(role)) {
            return chain.filter(exchange);
        }

        log.warn("[越权拦截] path={} method={} role={} 需要={}",
                request.getURI().getPath(), request.getMethod(), role, rule.roles());
        return forbidden(exchange, role, rule);
    }

    /**
     * 取第一条命中的规则；没有命中表示该接口对所有已登录角色开放（主要是查询）。
     */
    private Rule matchRule(HttpMethod method, String path) {
        for (Rule rule : RULES) {
            if (rule.method() == method && MATCHER.match(rule.pattern(), path)) {
                return rule;
            }
        }
        return null;
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String role, Rule rule) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String message = String.format("当前角色（%s）没有该操作的权限，请切换账号或联系系统管理员分配角色。", role);
        String body = String.format(
                "{\"code\":40300,\"message\":\"%s\",\"success\":false,\"timestamp\":%d}",
                message, System.currentTimeMillis());
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // 在 AuthGlobalFilter(-100) 之后，确保已经拿到网关注入的身份头
        return -99;
    }
}
