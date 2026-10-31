package com.smart.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回体。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code = 0} 表示成功（前端只认 0）</li>
 *   <li>业务异常使用 4xxxx 段（如 40001 参数错误、40901 状态冲突）</li>
 *   <li>系统异常使用 5xxxx 段</li>
 *   <li>HTTP 状态码保持 200，业务错误放在响应体里，便于网关统一处理</li>
 * </ul>
 */
@Data
@Schema(description = "统一返回体")
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_MSG = "success";

    @Schema(description = "业务状态码，0 表示成功", example = "0")
    private int code;

    @Schema(description = "提示信息", example = "success")
    private String message;

    @Schema(description = "业务数据")
    private T data;

    @Schema(description = "链路追踪 ID，便于跨服务排查")
    private String traceId;

    @Schema(description = "服务端时间戳（毫秒）")
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(SUCCESS_CODE);
        r.setMessage(SUCCESS_MSG);
        r.setData(data);
        return r;
    }

    public static <T> Result<T> ok(T data, String message) {
        Result<T> r = ok(data);
        r.setMessage(message);
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public static <T> Result<T> fail(String message) {
        return fail(ErrorCode.BUSINESS_ERROR.getCode(), message);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}
