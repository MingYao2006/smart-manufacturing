package com.smart.system.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.system.dto.SystemDtos.LoginRequest;
import com.smart.system.dto.SystemDtos.LoginVO;
import com.smart.system.entity.SysUser;
import com.smart.system.mapper.SysUserMapper;
import com.smart.system.support.Stub;
import com.smart.system.support.Stub.Recorder;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证服务测试（system-service）。
 *
 * <h3>验证点</h3>
 * <ol>
 *   <li>正确密码 → 登录成功并签发 JWT</li>
 *   <li>错误密码 → 拒绝，且<b>不区分</b>"用户不存在/密码错误"（防用户名枚举）</li>
 *   <li>账号停用 → 拒绝登录</li>
 *   <li>JWT 可被正确解析回用户身份（网关鉴权依赖这一点）</li>
 *   <li>篡改的 token 验签失败</li>
 * </ol>
 */
@DisplayName("认证服务：JWT 登录")
class AuthServiceTest {

    private static final String SECRET =
            "smart-manufacturing-mes-jwt-secret-key-please-change-in-production-2025";
    private static final String RAW_PASSWORD = "123456";
    /** BCrypt("123456")，与 sql/05_schema_system.sql 初始化数据一致 */
    private static final String BCRYPT_123456 =
            "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi";

    private AuthService authService;
    private SysUser user;

    @BeforeEach
    void setUp() {
        user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(BCRYPT_123456);
        user.setRealName("系统管理员");
        user.setStatus(1);
    }

    private void buildService(SysUser foundUser, List<String> roles) {
        Recorder<SysUserMapper> mapper = Recorder.of(SysUserMapper.class,
                Stub.rule("selectByUsername", 1, inv -> foundUser),
                Stub.rule("selectRoleCodes", 1, inv -> roles),
                Stub.rule("selectById", 1, inv -> foundUser),
                Stub.rule("selectList", 1, inv -> foundUser == null ? List.of() : List.of(foundUser)));
        authService = new AuthService(mapper.proxy());
        ReflectionTestUtils.setField(authService, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(authService, "expireMinutes", 480L);
        ReflectionTestUtils.setField(authService, "issuer", "smart-manufacturing");
    }

    private LoginRequest loginReq(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    /* ==================== 登录成功 ==================== */

    @Test
    @DisplayName("正确密码 → 登录成功，返回 token 与角色")
    void testLoginSuccess() {
        buildService(user, List.of("ADMIN"));

        LoginVO vo = authService.login(loginReq("admin", RAW_PASSWORD));

        assertNotNull(vo.getToken(), "必须返回 JWT");
        assertTrue(vo.getToken().split("\\.").length == 3,
                "JWT 应由 3 段组成（header.payload.signature）");
        assertEquals(480, vo.getExpiresInMinutes());
        assertNotNull(vo.getUser());
        assertEquals("admin", vo.getUser().getUsername());
        assertEquals("系统管理员", vo.getUser().getRealName());
        assertEquals(List.of("ADMIN"), vo.getUser().getRoles());
    }

    @Test
    @DisplayName("签发的 JWT 能被解析回正确的用户身份（网关鉴权依赖）")
    void testParseTokenRoundTrip() {
        buildService(user, List.of("PLANNER"));

        LoginVO vo = authService.login(loginReq("admin", RAW_PASSWORD));
        Claims claims = authService.parseToken(vo.getToken());

        assertEquals("1", claims.getSubject(), "sub 应是用户 ID");
        assertEquals(1, ((Number) claims.get("userId")).intValue());
        assertEquals("admin", claims.get("username"));
        assertEquals("系统管理员", claims.get("realName"));
        assertEquals("PLANNER", claims.get("role"), "主角色需与登录返回一致");
        assertEquals("smart-manufacturing", claims.getIssuer());
        assertNotNull(claims.getExpiration(), "必须有过期时间");
        assertTrue(claims.getExpiration().after(new java.util.Date()),
                "过期时间应在未来");
    }

    /* ==================== 登录失败 ==================== */

    @Test
    @DisplayName("密码错误 → 拒绝，提示「用户名或密码错误」（不暴露具体原因）")
    void testLoginWrongPassword() {
        buildService(user, List.of("ADMIN"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginReq("admin", "wrong-password")));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    @DisplayName("用户不存在 → 与密码错误返回<b>完全相同</b>的提示（防用户名枚举）")
    void testLoginUserNotFoundSameMessage() {
        buildService(null, List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginReq("ghost", "123456")));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage(),
                "不能提示「用户不存在」，否则攻击者可枚举用户名");
    }

    @Test
    @DisplayName("账号停用 → 拒绝登录（FORBIDDEN）")
    void testLoginDisabledAccount() {
        user.setStatus(0);
        buildService(user, List.of("ADMIN"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginReq("admin", RAW_PASSWORD)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("停用"));
    }

    /* ==================== Token 校验 ==================== */

    @Test
    @DisplayName("篡改的 token → 验签失败（UNAUTHORIZED）")
    void testTamperedTokenRejected() {
        buildService(user, List.of("ADMIN"));
        LoginVO vo = authService.login(loginReq("admin", RAW_PASSWORD));

        String[] parts = vo.getToken().split("\\.");
        // 篡改 payload（把 userId 改成 999），签名不再匹配
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"userId\":999,\"sub\":\"999\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.parseToken(tampered));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("非法格式 token → 抛 UNAUTHORIZED 而不是 500")
    void testMalformedToken() {
        buildService(user, List.of("ADMIN"));
        assertThrows(BusinessException.class, () -> authService.parseToken("not-a-jwt"));
        assertThrows(BusinessException.class, () -> authService.parseToken(""));
        assertThrows(BusinessException.class, () -> authService.parseToken("a.b.c"));
    }

    @Test
    @DisplayName("BCrypt 哈希：同一密码两次加密结果不同（自带随机盐），但都能校验通过")
    void testBcryptHash() {
        buildService(user, List.of("ADMIN"));

        String h1 = authService.hashPassword("123456");
        String h2 = authService.hashPassword("123456");

        assertNotNull(h1);
        assertTrue(h1.startsWith("$2a$") || h1.startsWith("$2b$"),
                "应为 BCrypt 格式，实际: " + h1);
        assertTrue(!h1.equals(h2), "BCrypt 自带随机盐，两次哈希不应相同");
        // 都能匹配
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456", h1));
        assertTrue(encoder.matches("123456", h2));
        assertTrue(!encoder.matches("wrong", h1));
    }

    @Test
    @DisplayName("初始化数据的 BCrypt 哈希确实对应密码 123456")
    void testSeedPasswordMatches() {
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456", BCRYPT_123456),
                "sql 初始化数据中的哈希必须与文档声明的一致（演示账号可用）");
    }
}
