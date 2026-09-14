package com.mall.marketing.support;

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
 *
 * <p>本服务自持副本（与 user-center / content / review / 单体同一套口径）。它同时是
 * {@link com.mall.marketing.config.InternalApiAuthInterceptor} 那道闸门的出口：
 * 拦截器抛 {@code BusinessException(403, ...)}，这里转成 HTTP 200 + {@code code=403}
 * ——所以"无令牌 → 403"在测试里断言的是 **body 里的 code**，HTTP 状态码是 200。
 *
 * <p>⚠️ 内部调用方（trade）必须按 {@code code} 判成败，**不能**按 HTTP 状态码判：
 * 券用不了时返回的是 200 + {@code code=409}，若 trade 只看 HTTP 就当成成功，
 * 会把"券已被别人用掉"静默吞掉（那正是 C1 文案要逐字保留的原因）。
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
     * Bean Validation 失败兜底：业务校验统一走 {@link RequestValidator}
     * （失败即抛 {@link BusinessException}，由上面那个处理器返回 400 + 原中文提示）。
     * 本方法保证以后在 Controller 参数上直接加 {@code @Valid} 时也返回同样的 400，
     * 而不是掉进下面的 {@code Exception} 分支变成 500"系统繁忙"。
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

    /** 请求体读不出来（JSON 语法错、类型对不上、body 为空等）→ 400 */
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
