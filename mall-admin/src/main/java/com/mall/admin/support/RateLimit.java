package com.mall.admin.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流(基于 Redis 固定窗口计数)，标注在 Controller 方法上。
 * Redis 不可用时**放行**(fail-open)，不影响业务可用性。
 *
 * <p>切面实现见 {@link com.mall.admin.config.RateLimitAspect}（与单体/user-center 同一套口径）。
 * 本服务目前只有一处：后台登录（与单体 {@code AdminAuthController.login} 的
 * {@code @RateLimit(scope="admin_login", limit=10, windowSeconds=60)} 逐字相同 —— 撞库防护不能因为搬家而变松）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 */
    enum By {
        /** 按客户端 IP */
        IP,
        /** 按登录身份(解析不到时回落 IP；身份来自网关注入的头) */
        USER
    }

    /** 限流场景名(用于 key 与日志)，如 admin_login */
    String scope();

    /** 窗口内允许的最大次数 */
    int limit() default 10;

    /** 窗口长度(秒) */
    int windowSeconds() default 60;

    /** 维度，默认按 IP */
    By by() default By.IP;
}
