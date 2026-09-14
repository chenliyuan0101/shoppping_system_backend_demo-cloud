package com.mall.demo.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;

/**
 * 全局异常处理：HTTP 固定 200，业务 code 见 ApiResponse / BusinessException。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数/其他框架级校验失败兜底 */
    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int SERVER_ERROR = 500;

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /**
     * Bean Validation 失败兜底：业务校验目前统一走 {@link RequestValidator}
     * （失败即抛 {@link BusinessException}，由上面那个处理器返回 400 + 原中文提示）。
     * 本方法保证以后在 Controller 参数上直接加 {@code @Valid} 时也返回同样的 400，
     * 而不是掉进下面的 {@code Exception} 分支变成 500"系统繁忙"。
     *
     * <p>注意：多条违规同时存在时框架不保证顺序，这里只做兜底取第一条，
     * 需要精确顺序的场景请继续用 {@link RequestValidator}。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        return ApiResponse.error(BAD_REQUEST, message);
    }

    /**
     * 请求体读不出来（JSON 语法错、类型对不上、body 为空等）→ 400。
     *
     * <p>以前这种情况会落到 {@code Exception} 兜底，返回 **500「系统繁忙」**：客户端明明是自己发错了，
     * 却看起来像服务端故障，排查时很误导。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.error(BAD_REQUEST, "请求体格式不正确");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.error(BAD_REQUEST, e.getMessage());
    }

    /** 静态资源不存在(如 /favicon.ico)：静默按 404 返回，不打错误日志 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.error(NOT_FOUND, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("unexpected server error", e);
        return ApiResponse.error(SERVER_ERROR, "系统繁忙，请稍后重试");
    }
}
