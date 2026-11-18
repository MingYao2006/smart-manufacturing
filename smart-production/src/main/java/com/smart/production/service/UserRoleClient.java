package com.smart.production.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * 访问系统服务（smart-system）获取用户角色的客户端。
 *
 * <p>派工（dispatch）需要知道「被委派的人是什么角色」：普通工序只能派给车间操作工，
 * 质检点工序只能派给质检员。这个判断必须在<b>服务端</b>做（见 {@code RoleGuard} 的设计说明），
 * 所以生产服务需要能反查出目标用户的角色。
 *
 * <p>沿用 {@link ProductClient} 的 {@link RestClient} + 可配置 baseUrl 思路：
 * 服务发现不可用时也能直连本地端口，保证「开箱即跑」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRoleClient implements UserRoleLookup {

    private final ObjectMapper objectMapper;

    @Value("${smart.service.system.base-url:http://127.0.0.1:9001}")
    private String baseUrl;

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 按用户 id 查角色编码列表（如 ["OPERATOR"]、["INSPECTOR"]、["ADMIN"]）。
     *
     * @return 该用户的角色集合；用户不存在 / 服务不可用时抛 {@link BusinessException}
     */
    @Override
    public List<String> getRoles(Long userId) {
        JsonNode data = call("/user/" + userId);
        if (data == null || data.isNull()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "派工目标用户不存在: " + userId);
        }
        JsonNode roles = data.path("roles");
        if (roles.isMissingNode() || !roles.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode r : roles) {
            result.add(r.asText());
        }
        return result;
    }

    /* ==================== 内部：统一解析返回体 ==================== */

    private JsonNode call(String path) {
        JsonNode body = doGet(path);
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            String msg = body.path("message").asText("系统服务返回错误");
            throw new BusinessException(code, msg);
        }
        return body.path("data");
    }

    private JsonNode doGet(String path) {
        try {
            String raw = restClient().get().uri(path).retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "系统服务返回空响应: " + path);
            }
            return objectMapper.readTree(raw);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[跨服务调用失败] system{}", path, e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "系统服务不可用（" + baseUrl + "），请确认 smart-system 已启动");
        }
    }
}
