package com.smart.production.support;

import com.smart.production.service.UserRoleLookup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版用户角色查询（测试用 Fake）。
 *
 * <p>派工现在要校验「被委派的人是什么角色」，业务测试不该为此去连 smart-system。
 * 与 {@link FakeProductCatalog} 同一思路：把外部依赖抽成接口，测试用 Fake 替身。
 *
 * <p>默认行为：{@link #defaults()} 让任意用户都返回 {@code [OPERATOR]}，
 * 足以覆盖绝大多数不关心角色的测试用例；需要区分质检点/管理员时用
 * {@link #with(Long, String...)} 显式指定。
 */
public class FakeUserRoles implements UserRoleLookup {

    private final Map<Long, List<String>> roles = new HashMap<>();

    /** 任意用户都被当作车间操作工。 */
    public static FakeUserRoles defaults() {
        return new FakeUserRoles();
    }

    /** 指定某个用户的角色；未指定的用户默认 OPERATOR。 */
    public FakeUserRoles with(Long userId, String... roleCodes) {
        this.roles.put(userId, List.of(roleCodes));
        return this;
    }

    @Override
    public List<String> getRoles(Long userId) {
        return roles.getOrDefault(userId, List.of("OPERATOR"));
    }

    /** 所有人都没有可识别角色（用于验证「角色不符一律拒绝」）。 */
    public static UserRoleLookup none() {
        return userId -> List.of();
    }
}
