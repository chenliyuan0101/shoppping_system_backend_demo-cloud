package com.mall.marketing.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流(基于 Redis 固定窗口计数)，标注在 Controller 方法上。
 * Redis 不可用时**放行**(fail-open)，不影响业务可用性。
 *
 * <p>切面实现见 {@link com.mall.marketing.config.RateLimitAspect}。
 *
 * <p><b>为什么券服务需要一个和单体逐字同形的限流注解</b>：P5 批次 2 把
 * {@code POST /api/coupon/{templateId}/receive} 从单体搬到本服务，而它在单体上带着
 * {@code @RateLimit(scope="coupon_receive", limit=10, windowSeconds=60, by=USER)}
 * ——「领券 60 秒内最多 10 次」是**对外行为**的一部分（防刷券/防脚本批量领券）。
 * 搬端点不搬限流，等于悄悄放宽对外行为（方案 §4.3 的同一条纪律：搬移 = 行为不变）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 */
    enum By {
        /** 按客户端 IP */
        IP,
        /** 按登录会员(解析不到时回落 IP；身份来自网关注入的头) */
        USER
    }

    /** 限流场景名(用于 key 与日志)，如 coupon_receive */
    String scope();

    /** 窗口内允许的最大次数 */
    int limit() default 10;

    /** 窗口长度(秒) */
    int windowSeconds() default 60;

    /** 维度，默认按 IP */
    By by() default By.IP;
}
