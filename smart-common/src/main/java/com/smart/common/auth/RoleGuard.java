package com.smart.common.auth;

import com.smart.common.constant.CommonConstants;
import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 角色闸门：服务端权限校验的唯一入口。
 *
 * <p>角色来自网关透传的 {@code X-User-Role}（JWT 里的 role 声明），
 * 所以校验必须放在<b>服务端</b>——前端按角色隐藏按钮只是体验，
 * 直接调接口就能绕过，见 {@link com.smart.common.context.UserContextHolder}。
 *
 * <h3>两种放行的情况（都不是漏洞，是边界）</h3>
 * <ol>
 *   <li><b>没有 HTTP 请求上下文</b>：定时任务、单元测试、MQ 消费者也会调这些服务方法，
 *       它们没有"登录用户"，不能按角色拒。</li>
 *   <li><b>请求头里没有角色</b>：网关把 {@code smart.gateway.auth-enabled} 关成 false
 *       时不再注入身份（本机演示用）。此时整个鉴权链是打开的，角色校验跟着不生效，
 *       日志里会打一条 WARN 提醒。</li>
 * </ol>
 * 生产部署必须保证业务服务只监听内网且 auth-enabled=true，否则身份可被伪造
 * （前提说明见 gateway 的 AuthGlobalFilter 类注释）。
 */
@Slf4j
public final class RoleGuard {

    public static final String ADMIN = "ADMIN";
    public static final String PLANNER = "PLANNER";
    public static final String WAREHOUSE = "WAREHOUSE";
    public static final String OPERATOR = "OPERATOR";
    public static final String INSPECTOR = "INSPECTOR";

    /** UserContextHolder 在拿不到请求头时的兜底角色，等价于"没有身份" */
    private static final String NO_IDENTITY = "SYSTEM";

    private RoleGuard() {
    }

    /** 当前角色；无请求上下文或网关未注入身份时返回 null（表示不校验） */
    public static String currentRoleOrNull() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        String role = UserContextHolder.currentRole();
        if (role == null || role.isBlank() || NO_IDENTITY.equals(role)) {
            log.debug("[角色校验] 请求未携带 {}，跳过角色校验（网关鉴权已关闭？）",
                    CommonConstants.HEADER_USER_ROLE);
            return null;
        }
        return role;
    }

    /** 当前登录用户 id；无上下文时为 {@link CommonConstants#SYSTEM_USER_ID} */
    public static Long currentUserId() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return CommonConstants.SYSTEM_USER_ID;
        }
        return UserContextHolder.currentUserId();
    }

    /** 是否有可判定的登录身份（决定归属校验要不要做） */
    public static boolean hasIdentity() {
        return currentRoleOrNull() != null;
    }

    /**
     * 要求当前角色在允许列表内；ADMIN 始终放行，不用在每个调用点重复声明。
     *
     * @param message     拒绝时给用户的提示（会带上当前角色的中文名）
     * @param allowedRoles 除 ADMIN 之外允许的角色；不传即表示只有系统管理员可做
     */
    public static void require(String message, String... allowedRoles) {
        String role = currentRoleOrNull();
        if (role == null || ADMIN.equals(role)) {
            return;
        }
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN,
                message + "（当前角色：" + roleLabel(role) + "）");
    }

    /**
     * 要求"是本人"。用于操作工只能处理派给自己工序、只能报自己的工这类口径。
     *
     * <p>没有登录身份时放行（定时任务/演示环境）；ADMIN 由 {@link #require} 先行放行，
     * 这里只在确实拿到了身份时才比对。
     */
    public static void requireSelf(Long ownerUserId, String message) {
        String role = currentRoleOrNull();
        if (role == null || ADMIN.equals(role)) {
            return;
        }
        Long me = currentUserId();
        if (me == null || CommonConstants.SYSTEM_USER_ID == me) {
            return;
        }
        if (ownerUserId == null || !ownerUserId.equals(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    message + "（当前角色：" + roleLabel(role) + "）");
        }
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case PLANNER -> "生产计划员";
            case WAREHOUSE -> "仓库管理员";
            case OPERATOR -> "车间操作工";
            case INSPECTOR -> "质检员";
            case ADMIN -> "系统管理员";
            default -> role;
        };
    }
}
