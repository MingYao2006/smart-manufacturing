package com.smart.common.result;

import lombok.Getter;

/**
 * 统一错误码。
 *
 * <p>分段约定：
 * <pre>
 *   0       成功
 *   40000段 客户端错误（参数、不存在）
 *   40900段 业务冲突（状态机、并发、库存不足）
 *   50000段 服务端错误
 * </pre>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    /* ---------- 客户端 ---------- */
    PARAM_ERROR(40000, "参数校验失败"),
    NOT_FOUND(40400, "资源不存在"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无访问权限"),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),

    /* ---------- 业务冲突 ---------- */
    BUSINESS_ERROR(40001, "业务处理失败"),
    DUPLICATE_KEY(40002, "数据已存在，编码重复"),
    DATA_VERSION_CONFLICT(40900, "数据已被修改，请刷新后重试"),
    STATE_TRANSITION_DENIED(40901, "当前状态不允许该操作"),
    CONCURRENT_CONFLICT(40902, "并发冲突，请重试"),
    STOCK_NOT_ENOUGH(40903, "物料库存不足"),
    RESERVATION_NOT_ENOUGH(40904, "领用量超过预留量"),
    QUANTITY_EXCEED_PLAN(40905, "累计数量超出计划数量"),
    PROCESS_SEQUENCE_VIOLATION(40906, "前道工序未完成"),
    INSPECTION_NOT_PASSED(40907, "前道工序质检未通过"),
    BOM_NOT_EFFECTIVE(40908, "产品未配置生效的 BOM"),
    ROUTE_NOT_EFFECTIVE(40909, "产品未配置生效的工艺路线"),
    BOM_CYCLE_DETECTED(40910, "BOM 存在循环引用"),
    TRACE_CYCLE_DETECTED(40911, "批次关系存在环路"),
    DEPENDENCY_UNAVAILABLE(40912, "依赖服务不可用"),

    /* ---------- 服务端 ---------- */
    SYSTEM_ERROR(50000, "系统内部错误"),
    DB_ERROR(50001, "数据库操作失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
