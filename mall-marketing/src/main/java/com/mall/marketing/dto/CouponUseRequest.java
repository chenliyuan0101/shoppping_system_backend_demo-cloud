package com.mall.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code POST /internal/v1/marketing/coupon/{use,unlock}} 的请求体（两个端点**同形**）。
 *
 * <p>三个字段都是必填，且 {@code orderNo} 必须与 {@code lock} 时写下的那个**逐字相同**：
 * 这三条 SQL 的 WHERE 就是 {@code id + member_id + coupon_status + order_no}，
 * 少任何一个条件都会有误伤（见 {@code CouponMemberMapper} 的类注释）。
 *
 * <p>⚠️ {@code memberId} 必填与单体 {@code CouponCommandServiceImpl.useCoupon(memberId, …)}
 * 一致（它当时也要求 memberId 非 null，否则返回 false）——**口径不改**：
 * 传错会员 id 的结果是 {@code changed=false}（不动别人的券），而不是抛异常，
 * 因为调用方（trade 支付回调）拿到的 false 只该记日志，**不得让支付失败**（方案 §4.3.1）。
 */
@Data
public class CouponUseRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "用户券ID不能为空")
    private Long couponMemberId;

    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号长度不能超过32")
    private String orderNo;
}
