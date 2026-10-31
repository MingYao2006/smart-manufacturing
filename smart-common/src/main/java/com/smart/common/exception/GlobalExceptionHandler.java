package com.smart.common.exception;

import com.smart.common.result.ErrorCode;
import com.smart.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>设计要点：
 * <ol>
 *   <li>业务异常打 WARN（不打 ERROR 堆栈，避免日志噪音）</li>
 *   <li>未知异常打 ERROR 并带 traceId，便于跨服务排查</li>
 *   <li>唯一索引冲突必须翻译成"编码重复"而不是 500</li>
 *   <li>参数校验失败要把具体字段名带出去</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("[业务异常] uri={} code={} msg={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return withTrace(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[参数校验失败] {}", msg);
        return withTrace(Result.fail(ErrorCode.PARAM_ERROR, msg));
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return withTrace(Result.fail(ErrorCode.PARAM_ERROR, msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return withTrace(Result.fail(ErrorCode.PARAM_ERROR, "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[请求体解析失败] {}", e.getMessage());
        return withTrace(Result.fail(ErrorCode.PARAM_ERROR, "请求体格式错误"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicate(DuplicateKeyException e) {
        log.warn("[唯一索引冲突] {}", e.getMostSpecificCause().getMessage());
        return withTrace(Result.fail(ErrorCode.DUPLICATE_KEY, "数据已存在（编码或单号重复）"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return withTrace(Result.fail(ErrorCode.METHOD_NOT_ALLOWED, "不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNotFound(NoHandlerFoundException e) {
        return withTrace(Result.fail(ErrorCode.NOT_FOUND, "接口不存在: " + e.getRequestURL()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[非法参数] {}", e.getMessage());
        return withTrace(Result.fail(ErrorCode.PARAM_ERROR, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("[系统异常] uri={}", request.getRequestURI(), e);
        return withTrace(Result.fail(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试"));
    }

    private Result<Void> withTrace(Result<Void> result) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            result.setTraceId(traceId);
        }
        return result;
    }
}
