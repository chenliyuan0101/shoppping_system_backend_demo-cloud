package com.mall.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.mall.common.support.MemberId;

/**
 * {@code POST /internal/v1/marketing/coupon/lock} 的请求体：下单占用这张券（{@code 0 → 3}）。
 *
 * <p>{@code orderNo} 是**锁定归属**：它既写进 {@code order_no}，也决定后续 {@code use}/{@code unlock}
 * 能不能动这张券（不匹配就是"不是本单锁的券"）。因此它不能为空——
 * 空单号会锁出一张**谁都解不开**的券（状态 3 但 order_no 为 NULL，对账也认不出来）。
 *
 * <p>{@code @Size(max = 32)} 与列宽 {@code varchar(32)} 对齐：写超长值在 MySQL 严格模式下会
 * 直接报 1406（进而在本服务变成 500"系统繁忙"），在这里挡下来才是 400 该有的样子。
 */
@Data
public class CouponLockRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "用户券ID不能为空")
    private Long couponMemberId;

    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号长度不能超过32")
    private String orderNo;
}
