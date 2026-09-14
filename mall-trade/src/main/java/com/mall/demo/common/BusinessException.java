package com.mall.demo.common;

import lombok.Getter;

/**
 * 业务异常：code 与《接口文档.md》1.4 错误码一致。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
