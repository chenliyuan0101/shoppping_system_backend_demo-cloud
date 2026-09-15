package com.mall.trade.oms.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务单号生成(订单号/售后单号共用)：yyyyMMddHHmmss + 6 位随机数。
 * 唯一性由 oms_order / oms_refund 的唯一键兜底。
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderNoGenerator() {
    }

    public static String next() {
        return LocalDateTime.now().format(NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
