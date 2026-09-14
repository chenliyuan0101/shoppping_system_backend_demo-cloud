package com.mall.review.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时区统一入口（单体 {@code com.mall.demo.common.MallTime} 的契约副本，P4-2 复制）。
 *
 * <p>为什么需要：{@code order.finished} 事件里的 {@code finishedTime} 是 <b>epoch 毫秒</b>，
 * 而 {@code review_pending_item.finished_time} 是 {@code datetime}（无时区）。
 * "剩余可评价天数"是从这一列算的，若两端口径不同，JVM 时区一旦不是 +08:00（容器里很常见），
 * 读模型里存下来的时间就会整体偏 8 小时——评价期限因此可能提前或延后一天失效。
 *
 * <p>统一约定（与单体一字不差）：<b>业务时间一律按 {@code Asia/Shanghai} 计算</b>，
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

    /** 毫秒时间戳 → 业务时区的日期时间（MQ 事件落库用） */
    public static LocalDateTime dateTimeOf(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }
}
