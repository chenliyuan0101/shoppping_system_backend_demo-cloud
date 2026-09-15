package com.mall.marketing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import com.mall.common.support.MemberId;

/**
 * {@code POST /internal/v1/marketing/coupon/discount} 的请求体：算这张券能抵多少钱（分）。
 *
 * <p>⚠️ {@code couponMemberId} **刻意允许为 null**：单体
 * {@code CouponQueryServiceImpl.discountFor} 的第一行就是
 * {@code if (couponMemberId == null) return 0L;} ——"用户没选券"是**正常路径**（下单必然发生），
 * 不是参数错误。若在这里加 {@code @NotNull}，就会把"不用券下单"变成 400，
 * 那等于让所有不选券的下单全挂（P5 的口径是"校验一字不改"，包括这条 null 语义）。
 */
@Data
public class CouponDiscountRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    /** 用户券 id；null = 本单不使用优惠券（返回 0 抵扣，不报错） */
    private Long couponMemberId;

    @NotNull(message = "商品金额不能为空")
    @PositiveOrZero(message = "商品金额不能为负")
    private Long goodsTotal;
}
