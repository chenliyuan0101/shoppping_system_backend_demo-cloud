package com.mall.gateway.auth;

/**
 * 令牌非法/过期（网关侧）。
 *
 * <p>刻意不复用单体的 {@code BusinessException}：网关是独立进程，引入单体的异常体系
 * 等于让网关依赖一个它不需要的模块；过滤器只需要知道"验证没过"这一件事实，
 * 对外文案由过滤器统一决定（必须与单体逐字一致）。
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("invalid token");
    }
}
