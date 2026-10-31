package com.smart.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 网关全局过滤器：JWT 鉴权 + 链路追踪 + 用户信息透传。
 *
 * <h3>为什么鉴权放在网关</h3>
 * 如果不放在网关，每个业务服务都要写一遍鉴权逻辑，
 * 一旦漏写一个接口就是越权漏洞。<b>统一入口 = 统一防线。</b>
 *
 * <h3>透传方式</h3>
 * 网关验签通过后，把 {@code X-User-Id / X-User-Name / X-User-Role} 写进请求头，
 * 下游服务直接读（见 smart-common 的 UserContextHolder），不重复解析 token。
 *
 * <p>⚠️ 安全前提：业务服务不能直接暴露给外网，否则可以伪造 X-User-Id 头。
 * 生产部署必须让业务服务只监听内网。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 免登录白名单：登录接口、各服务的文档与健康检查 */
    private static final List<String> WHITE_LIST = List.of(
            "/system/auth/login",
            "/", "/index.html", "/app.js", "/data.js", "/api.js", "/styles.css", "/favicon.svg",
            "/assets/**",
            "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-resources/**",
            "/favicon.ico", "/actuator/health", "/actuator/info",
            "/*/doc.html", "/*/webjars/**", "/*/v3/api-docs/**",
            "/*/actuator/health"
    );

    @Value("${smart.jwt.secret:smart-manufacturing-mes-jwt-secret-key-please-change-in-production-2025}")
    private String jwtSecret;

    @Value("${smart.jwt.issuer:smart-manufacturing}")
    private String issuer;

    @Value("${smart.gateway.auth-enabled:true}")
    private boolean authEnabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 链路追踪：生成/透传 traceId
        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);

        // 2. 白名单直接放行
        if (!authEnabled || isWhiteListed(path)) {
            ServerHttpRequest mutated = request.mutate()
                    .header("X-Trace-Id", traceId)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        // 3. 取 token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }
        if (token == null && request.getQueryParams().getFirst("token") != null) {
            token = request.getQueryParams().getFirst("token");
        }
        if (token == null || token.isBlank()) {
            return unauthorized(exchange, "未登录，请先调用 /system/auth/login 获取 token");
        }

        // 4. 验签 + 解析
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = String.valueOf(claims.get("userId"));
            String username = String.valueOf(claims.get("username"));
            String role = String.valueOf(claims.get("role"));

            ServerHttpRequest mutated = request.mutate()
                    .header("X-Trace-Id", traceId)
                    .header("X-User-Id", userId)
                    .header("X-User-Name", username)
                    .header("X-User-Role", role)
                    .build();
            log.debug("[网关鉴权通过] path={} userId={} role={}", path, userId, role);
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[网关鉴权失败] path={} reason={}", path, e.getMessage());
            return unauthorized(exchange, "登录已过期或凭证无效，请重新登录");
        } finally {
            MDC.remove("traceId");
        }
    }

    private boolean isWhiteListed(String path) {
        for (String pattern : WHITE_LIST) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回统一格式的 401 响应（与业务服务的 Result 结构保持一致）。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format(
                "{\"code\":40100,\"message\":\"%s\",\"success\":false,\"timestamp\":%d}",
                message, System.currentTimeMillis());
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
