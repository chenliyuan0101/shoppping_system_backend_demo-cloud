package com.mall.common.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时区统一入口（**共享内核版**：v5.2 起由 `mall-common` 提供，各服务不再各存一份）。
 *
 * <p>为什么需要：券的"是否过期"、订单的"今天"、MQ 事件按天归类，全都用
 * {@code LocalDateTime.now()} 与库里的 {@code datetime} 比；而 {@code datetime} **无时区**
 * （写入方按 JDBC 的 {@code serverTimezone} 解释）。若 JVM 时区不是 +08:00（容器里很常见），
 * "同一个时间点"会算出差 8 小时的结果——表现为**券提前过期 / 该过期却还能用 / 统计归错天**，
 * 且只在跨时区的机器上出现。
 *
 * <p>统一约定：业务时间一律按 {@code Asia/Shanghai} 计算，与各服务
 * {@code application-dev.yaml} 的 JDBC {@code serverTimezone} 保持一致。
 *
 * <p>方法集是原来两份副本的**并集**（`today`/`dateOf` 来自 product+trade 版，
 * `dateTimeOf` 来自 marketing+review 版），因此本类可同时替代两份。
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

    /** 业务"今天" */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 毫秒时间戳 → 业务时区的日期（MQ 事件按发生时间归类用） */
    public static LocalDate dateOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate();
    }

    /** 毫秒时间戳 → 业务时区的日期时间 */
    public static LocalDateTime dateTimeOf(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }
}
