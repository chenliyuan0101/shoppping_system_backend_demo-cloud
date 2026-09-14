package com.mall.marketing.support.constant;

/**
 * 优惠券类型 {@code sms_coupon.type}：1 满减券(直减)。
 *
 * <p>与单体 {@code com.mall.demo.sms.support.CouponType} 逐字相同（P5 步骤 C 把它整个搬进营销域：
 * 后台规则的属主是营销域）。
 *
 * <p>现在只有一种类型，但**仍然单独成类、不做成 boolean**：后台校验里
 * 「暂仅支持满减券」这条文案的存在就说明"以后会有别的类型"，把 {@code type} 压成布尔会让
 * 那次扩展变成"改字段语义"（数据库列是 tinyint，改语义要动存量数据）。
 */
public final class CouponType {

    public static final int FULL_REDUCTION = 1;

    private CouponType() {
    }
}
