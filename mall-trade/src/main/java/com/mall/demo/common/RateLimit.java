package com.mall.demo.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流(基于 Redis 固定窗口计数)，标注在 Controller 方法上。
 * Redis 不可用时**放行**(fail-open)，不影响业务可用性。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 */
    enum By {
        /** 按客户端 IP */
        IP,
        /** 按登录会员(解析不到时回落 IP) */
        USER
    }

    /** 限流场景名(用于 key 与日志)，如 login/receive/order */
    String scope();

    /** 窗口内允许的最大次数 */
    int limit() default 10;

    /** 窗口长度(秒) */
    int windowSeconds() default 60;

    /** 维度，默认按 IP */
    By by() default By.IP;
}
