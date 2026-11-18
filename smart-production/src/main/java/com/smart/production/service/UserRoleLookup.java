package com.smart.production.service;

import java.util.List;

/**
 * 查询用户角色的能力抽象。
 *
 * <p>派工需要判断「被委派的人是什么角色」：普通工序只能派给车间操作工，
 * 质检点工序只能派给质检员。这条校验必须在服务端做（见 {@code RoleGuard} 的设计说明），
 * 所以 {@code WorkTaskService} 需要能反查目标用户的角色。
 *
 * <p>与 {@link ProductCatalogClient} 一样抽成接口：生产实现走 HTTP 调用
 * {@code smart-system}（见 {@link UserRoleClient}），单元测试里可直接用 lambda 注入，
 * 不必为了测一条校验去搭一个假 HTTP 服务。
 */
public interface UserRoleLookup {

    /**
     * 按用户 id 查角色编码列表（如 {@code ["OPERATOR"]}、{@code ["INSPECTOR"]}）。
     */
    List<String> getRoles(Long userId);
}
