package com.mall.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 券模板领取记录 —— 单体 {@code com.mall.demo.sms.dto.CouponRecordVO} 的契约副本。
 *
 * <p>⚠️ {@link #couponStatus} 与 {@code MyCouponVO} 一样是**投影后的对外值**：
 * 库里 {@code LOCKED(3)} 必须输出 {@code 1}（C1 硬约束，见 {@code CouponStatusProjection}）。
 * 后台的"领取记录"页与本户的"我的券"页如果一处投影、一处不投影，同一张券会在两个页面显示成不同状态。
 */
@Data
public class CouponRecordVO {

    private Long id;
    private Long memberId;
    private String memberUsername;
    private String memberNickname;
    /** 对外状态：0未用 1已用 2已过期（库里的 3=锁定中 已投影成 1） */
    private Integer couponStatus;
    private LocalDateTime receiveTime;
    private LocalDateTime expireTime;
    private String orderNo;
    private LocalDateTime useTime;
}
