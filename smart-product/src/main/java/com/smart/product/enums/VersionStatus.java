package com.smart.product.enums;

import lombok.Getter;

/**
 * 版本化对象（BOM / 工艺路线）的状态。
 *
 * <p>状态机（"生效"不可逆）：
 * <pre>
 *   DRAFT(草稿) --审核生效--> ACTIVE(生效) --高版本生效时被顶替--> INACTIVE(失效)
 *
 *   DRAFT     → ACTIVE
 *   ACTIVE    → INACTIVE   （仅在另一版本生效时由系统自动触发）
 *   INACTIVE  → （终态，不可再回到生效）
 * </pre>
 *
 * <p>为什么同一产品只能有一个 ACTIVE：如果两个 BOM 同时生效，
 * 工单创建时不知道该用哪个，业务上就乱套了。
 */
@Getter
public enum VersionStatus {

    DRAFT(1, "草稿"),
    ACTIVE(2, "生效"),
    INACTIVE(3, "失效");

    private final int code;
    private final String desc;

    VersionStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static VersionStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (VersionStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    public static String descOf(Integer code) {
        VersionStatus s = of(code);
        return s == null ? "未知" : s.desc;
    }
}
