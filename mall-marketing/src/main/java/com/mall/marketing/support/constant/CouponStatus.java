package com.mall.marketing.support.constant;

/**
 * 优惠券模板状态 {@code sms_coupon.status}：0 启用 / 1 停用。
 *
 * <p><b>极性与 {@code EnableStatus} 相反</b>（这里 0 才是可用），所以单独成类，
 * 禁止用 {@code EnableStatus.ENABLED}(=1) 表达"券可用"——那会让 {@code CouponRules.enabled}
 * 变成"停用的券才可用"，而它决定的是**券能不能抵扣钱**。
 */
public final class CouponStatus {

    public static final int ENABLED = 0;
    public static final int DISABLED = 1;

    private CouponStatus() {
    }
}
