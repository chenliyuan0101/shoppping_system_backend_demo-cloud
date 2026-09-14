package com.mall.marketing.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时区统一入口（与单体 {@code com.mall.demo.common.MallTime}、review 的副本逐字相同）。
 *
 * <p>为什么需要：券的"是否过期"是**用 {@code LocalDateTime.now()} 与 {@code expire_time} 比**出来的
 * （{@code CouponRules.notExpired}），而 {@code expire_time} 是 MySQL 的 {@code datetime}
 * （无时区，写入方按 JDBC 的 {@code serverTimezone} 解释）。若 JVM 时区不是 +08:00
 * （容器里很常见），"同一张券"在本服务算出来会与旧实现相差 8 小时——
 * 表现为**券提前过期或该过期却还能用**，且只在跨时区的机器上出现。
 *
 * <p>统一约定（与单体一字不差）：业务时间一律按 {@code Asia/Shanghai} 计算，
 * 与 {@code application-dev.yaml} 的 JDBC {@code serverTimezone} 保持一致。
 */
public final class MallTime {

    /** 业务时区（与 application-dev.yaml 的 serverTimezone 保持一致） */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private MallTime() {
    }

    /** 业务"当前时间" */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 毫秒时间戳 → 业务时区的日期时间 */
    public static LocalDateTime dateTimeOf(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }
}
