package com.mall.demo.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时区统一入口。
 *
 * <p>为什么需要：订单/统计/看板的"今天"必须落在同一个自然日上。之前
 * {@code StatServiceImpl}、{@code AdminDashboardServiceImpl} 用 `LocalDate.now()`（JVM 默认时区），
 * 而 MQ 消费者用显式 `Asia/Shanghai`，JDBC 又配了 `serverTimezone=Asia/Shanghai`
 * —— JVM 时区一旦不是 +08:00（容器里很常见），"今天"就会错位到不同自然日，
 * 表现为看板当天数据为 0 / 统计落到前一天。
 *
 * <p>统一约定：**业务时间一律按 {@code Asia/Shanghai} 计算**，需要"现在"就从这里取。
 */
public final class MallTime {

    /** 业务时区（与 application-dev.yaml 的 serverTimezone 保持一致） */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private MallTime() {
    }

    /** 业务"今天" */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 业务"当前时间" */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 毫秒时间戳 → 业务时区的日期（MQ 事件按发生时间归类用） */
    public static LocalDate dateOf(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate();
    }
}
