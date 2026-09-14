package com.mall.marketing.support.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下单可选券(结算页展示) —— 单体 {@code com.mall.demo.common.dto.CouponBriefVO} 的契约副本。
 *
 * <p>P0 批次 5 把它下沉到 {@code common.dto}，是因为当时它是 {@code oms ↔ sms} 的**跨域契约**；
 * P5 之后这个契约变成**跨进程**的（trade 通过
 * {@code POST /internal/v1/marketing/coupon/usable} 拿它），因此字段名与 JSON 形状
 * 必须与单体那一份逐字相同——trade 侧的反序列化是按这些名字写的。
 *
 * <p>⚠️ 刻意**只有 5 个字段**（与单体一致）：结算页只需要"选哪张、减多少、什么时候过期"，
 * 把 {@code member_id}/{@code coupon_status} 之类塞进来会让调用方顺手用上，
 * 于是"券的状态机在营销域"这条边界又会从字段上被穿透。
 */
@Data
public class CouponBriefVO {

    private Long id;
    private String name;
    private Long thresholdAmount;
    private Long discountAmount;
    private LocalDateTime expireTime;
}
