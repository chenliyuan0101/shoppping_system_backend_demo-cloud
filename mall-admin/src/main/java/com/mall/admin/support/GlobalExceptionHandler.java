package com.mall.admin.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;

/**
 * 全局异常处理：**HTTP 固定 200**，业务 code 见 {@link ApiResponse} / {@link BusinessException}。
 *
 * <p>自持副本，与单体 {@code com.mall.demo.common.GlobalExceptionHandler} 逐字同构（C1）。
 * 管理端登录的 4 条业务文案都从这里出去：
 * 400「请输入账号和密码」/ 401「用户名或密码错误」/ 403「账号已被禁用」/ 401「未登录」/ 401「登录已失效…」。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数/其他框架级校验失败兜底 */
    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int SERVER_ERROR = 500;

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /** Bean Validation 失败兜底（本服务目前统一走显式校验，失败即抛 BusinessException） */
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
     * <p>⚠️ C1 相关：`POST /api/admin/auth/login` 不带体/带坏体时单体返回
     * {@code 400 请求体格式不正确}，本服务必须逐字相同（否则登录页的报错文案会漂移）。
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
