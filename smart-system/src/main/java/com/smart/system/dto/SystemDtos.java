package com.smart.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 系统服务 DTO / VO。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    @Data
    @Schema(description = "登录请求")
    public static class LoginRequest {

        @NotBlank(message = "用户名不能为空")
        @Schema(description = "用户名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
        private String username;

        @NotBlank(message = "密码不能为空")
        @Schema(description = "密码（明文传输前应走 HTTPS，服务端用 BCrypt 校验）",
                example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
        private String password;
    }

    @Data
    @Schema(description = "登录结果")
    public static class LoginVO {
        @Schema(description = "JWT token，后续请求放在 Authorization: Bearer {token}")
        private String token;
        @Schema(description = "有效期（分钟）")
        private long expiresInMinutes;
        private UserVO user;
    }

    @Data
    @Schema(description = "用户信息")
    public static class UserVO {
        private Long id;
        private String username;
        private String realName;
        private String phone;
        private String email;
        private Integer status;
        @Schema(description = "角色编码列表", example = "[\"ADMIN\"]")
        private List<String> roles;
    }
}
