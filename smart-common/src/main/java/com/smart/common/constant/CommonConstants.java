package com.smart.common.constant;

/**
 * 全局常量。
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /** 网关透传用户信息的请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_NAME = "X-User-Name";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 链路追踪 */
    public static final String TRACE_ID = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 逻辑删除标记 */
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;

    /** 启用/停用 */
    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    /** 默认仓库（本期单仓库） */
    public static final long DEFAULT_WAREHOUSE_ID = 1L;

    /** 分页上限，防止恶意大分页拖垮数据库 */
    public static final long MAX_PAGE_SIZE = 200L;

    /** 树形/图遍历最大深度，防环与防爆栈 */
    public static final int MAX_TREE_DEPTH = 10;

    /** 系统默认操作人（未接入网关鉴权时的兜底） */
    public static final long SYSTEM_USER_ID = 0L;
}
