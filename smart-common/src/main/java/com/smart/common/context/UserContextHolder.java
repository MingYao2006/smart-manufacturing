package com.smart.common.context;

import com.smart.common.constant.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录用户上下文。
 *
 * <p>网关校验 JWT 后把用户信息写进请求头，业务服务直接读，不重复解析 token。
 */
@Slf4j
public final class UserContextHolder {

    private UserContextHolder() {
    }

    public static Long currentUserId() {
        String v = header(CommonConstants.HEADER_USER_ID);
        if (v == null || v.isBlank()) {
            return CommonConstants.SYSTEM_USER_ID;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return CommonConstants.SYSTEM_USER_ID;
        }
    }

    public static String currentUserName() {
        String v = header(CommonConstants.HEADER_USER_NAME);
        return (v == null || v.isBlank()) ? "system" : v;
    }

    public static String currentRole() {
        String v = header(CommonConstants.HEADER_USER_ROLE);
        return (v == null || v.isBlank()) ? "SYSTEM" : v;
    }

    private static String header(String name) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request == null ? null : request.getHeader(name);
    }
}
