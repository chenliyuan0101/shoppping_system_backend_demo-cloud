package com.mall.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的券 —— 单体 {@code com.mall.demo.sms.dto.MyCouponVO} 的契约副本。
 *
 * <p>字段名与顺序**逐字照抄**（P5 批次 2 把 {@code GET /api/coupon/mine} 搬进本服务）。
 *
 * <p>⚠️ <b>{@link #couponStatus} 是投影后的对外值</b>：库里 {@code LOCKED(3)} 必须输出 {@code 1}
 * （C1 硬约束，见 {@code CouponStatusProjection}）。也就是说本类里的 {@code couponStatus}
 * **不是** {@code sms_coupon_member.coupon_status} 的原样拷贝——谁把实体直接塞进这个 VO，
 * 谁就会把库里的 3 泄漏给前端（"锁定中被显示成已过期/未知"）。
 */
@Data
public class MyCouponVO {

    private Long id;
    private String name;
    private Integer type;
    private Long thresholdAmount;
    private Long discountAmount;
    /** 对外状态：0未用 1已用 2已过期（库里的 3=锁定中 已被投影成 1） */
    private Integer couponStatus;
    private LocalDateTime receiveTime;
    private LocalDateTime expireTime;
    private String orderNo;
}
