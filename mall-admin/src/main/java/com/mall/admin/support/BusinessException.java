package com.mall.admin.support;

import lombok.Getter;

/**
 * 业务异常：code 与《接口文档.md》1.4 错误码一致（本服务自持副本，与单体同构）。
 *
 * <p>管理端的三条文案（逐字，C1）由本异常承载：
 * {@code 401 未登录} / {@code 401 登录已失效，请重新登录} / {@code 403 账号已被禁用}
 * （以及 {@code 401 登录已失效，请使用管理员账号登录}）。
 * 它们**同时**由网关（{@code AdminIdentityFilter}）产出，两侧都必须逐字一致（P7 §2）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
