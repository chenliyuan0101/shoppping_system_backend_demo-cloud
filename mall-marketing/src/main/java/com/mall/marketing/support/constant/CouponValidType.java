package com.mall.marketing.support.constant;

/**
 * 优惠券有效期类型 {@code sms_coupon.valid_type}：1 固定时间段 / 2 领取后 N 天有效。
 *
 * <p>{@link #FIXED_RANGE} 必须同时给出起止时间(后台校验)；{@link #DAYS_AFTER_RECEIVE} 的到期时间
 * 在领取时按 {@code valid_days} 计算，并落到用户券的 {@code expire_time} 上
 * ——所以本服务判"券是否过期"读的是 {@code sms_coupon_member.expire_time}，
 * 而 {@link #FIXED_RANGE} 的模板还要再看 {@code sms_coupon.valid_start_time/valid_end_time}
 * （两道都要过，见 {@code CouponRules}）。
 */
public final class CouponValidType {

    public static final int FIXED_RANGE = 1;
    public static final int DAYS_AFTER_RECEIVE = 2;

    private CouponValidType() {
    }
}
