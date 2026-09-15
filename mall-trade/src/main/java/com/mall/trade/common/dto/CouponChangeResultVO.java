package com.mall.trade.common.dto;

import lombok.Data;

/**
 * {@code /internal/v1/marketing/coupon/unlock} 的响应载荷：{@code {"changed": true|false}}。
 *
 * <p>{@code true} = 券现在处于该操作期望的终态（可能是这次真的改了，也可能是本来就已是）；
 * {@code false} = 不是本单锁的券。补偿路径拿 false 只记日志（见 {@code MarketingClient#unlockQuietly}）。
 */
@Data
public class CouponChangeResultVO {

    private boolean changed;
}
