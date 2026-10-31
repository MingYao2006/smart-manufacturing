package com.smart.system.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.system.dto.SystemDtos.LoginRequest;
import com.smart.system.dto.SystemDtos.LoginVO;
import com.smart.system.dto.SystemDtos.UserVO;
import com.smart.system.entity.SysUser;
import com.smart.system.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 认证服务（JWT 登录）。
 *
 * <h3>为什么用 JWT 而不是 Session</h3>
 * 微服务下网关需要<b>无状态</b>地校验身份。
 * Session 需要共享存储（Redis）或粘性会话，而 JWT 把身份信息签名后交给客户端，
 * 网关只需用同一密钥验签，无需访问任何存储。
 *
 * <h3>安全要点</h3>
 * <ol>
 *   <li>密码用 <b>BCrypt</b> 存储（自带盐、慢哈希，抵御彩虹表）</li>
 *   <li>JWT 用 <b>HMAC-SHA256</b> 签名，密钥不能写死在代码里（这里用配置项）</li>
 *   <li>token 设置过期时间，防止永久有效</li>
 *   <li>登录失败不区分"用户不存在"和"密码错误"（防用户名枚举）</li>
 * </ol>
 */
@Slf4j
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${smart.jwt.secret:smart-manufacturing-mes-jwt-secret-key-please-change-in-production-2025}")
    private String jwtSecret;

    @Value("${smart.jwt.expire-minutes:480}")
    private long expireMinutes;

    @Value("${smart.jwt.issuer:smart-manufacturing}")
    private String issuer;

    public AuthService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 登录：校验用户名密码，签发 JWT。
     */
    public LoginVO login(LoginRequest req) {
        SysUser user = userMapper.selectByUsername(req.getUsername());
        // ⚠️ 统一提示"用户名或密码错误"，不区分哪种错误（防用户名枚举攻击）
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("[登录失败] username={} 原因={}", req.getUsername(),
                    user == null ? "用户不存在" : "密码错误");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }

        List<String> roleCodes = userMapper.selectRoleCodes(user.getId());
        String primaryRole = roleCodes.isEmpty() ? "OPERATOR" : roleCodes.get(0);

        String token = buildToken(user, primaryRole, roleCodes);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresInMinutes(expireMinutes);
        vo.setUser(toUserVO(user, roleCodes));
        log.info("[登录成功] username={} roles={}", user.getUsername(), roleCodes);
        return vo;
    }

    /**
     * 签发 JWT。
     *
     * <p>payload 里放 userId / username / role，网关验签后可直接转发给下游，
     * 下游服务无需再查数据库。
     */
    private String buildToken(SysUser user, String primaryRole, List<String> roleCodes) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMinutes * 60 * 1000);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(issuer)
                .claim("userId", user.getId())
                .claim("username", user.getUsername())
                .claim("realName", user.getRealName())
                .claim("role", primaryRole)
                .claim("roles", roleCodes)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    /**
     * 解析并校验 JWT（供网关/下游服务使用同一逻辑）。
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期或凭证无效，请重新登录");
        }
    }

    private SecretKey signingKey() {
        // HMAC-SHA256 要求密钥至少 256 bit（32 字节）
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public UserVO currentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return toUserVO(user, userMapper.selectRoleCodes(userId));
    }

    public List<UserVO> listUsers() {
        return userMapper.selectList(null).stream()
                .map(u -> toUserVO(u, userMapper.selectRoleCodes(u.getId())))
                .toList();
    }

    private UserVO toUserVO(SysUser user, List<String> roleCodes) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        vo.setRoles(roleCodes);
        return vo;
    }

    /**
     * 生成 BCrypt 哈希（用于初始化数据/改密码）。
     */
    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
