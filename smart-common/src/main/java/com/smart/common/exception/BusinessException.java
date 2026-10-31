package com.smart.common.exception;

import com.smart.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>与 {@link RuntimeException} 的区别：业务异常是"可预期的失败"
 * （库存不足、状态不允许、编码重复），必须返回友好提示，不能打 ERROR 堆栈。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.BUSINESS_ERROR.getCode();
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 状态流转被拒绝 */
    public static BusinessException stateDenied(String message) {
        return new BusinessException(ErrorCode.STATE_TRANSITION_DENIED, message);
    }

    /** 库存不足 */
    public static BusinessException stockNotEnough(String message) {
        return new BusinessException(ErrorCode.STOCK_NOT_ENOUGH, message);
    }
}
