package com.smart.system.controller;

import com.smart.common.result.Result;
import com.smart.system.dto.SystemDtos.LoginRequest;
import com.smart.system.dto.SystemDtos.LoginVO;
import com.smart.system.dto.SystemDtos.UserVO;
import com.smart.system.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证与用户接口（system-service）。
 */
@Tag(name = "41-系统：用户与登录")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录（签发 JWT）", description = "演示账号：admin / planner / warehouse / operator / inspector，密码均为 123456")
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req), "登录成功");
    }

    @Operation(summary = "查询用户信息")
    @GetMapping("/user/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        return Result.ok(authService.currentUser(id));
    }

    @Operation(summary = "查询全部用户")
    @GetMapping("/user/list")
    public Result<List<UserVO>> listUsers() {
        return Result.ok(authService.listUsers());
    }

    @Operation(summary = "生成密码哈希（仅用于运维/初始化）")
    @PostMapping("/auth/hash")
    public Result<String> hash(@RequestBody String rawPassword) {
        return Result.ok(authService.hashPassword(rawPassword));
    }
}
