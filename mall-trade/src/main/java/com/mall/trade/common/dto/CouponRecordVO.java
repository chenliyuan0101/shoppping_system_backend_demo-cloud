package com.mall.trade.common.dto;

import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 券模板领取记录的**响应形状**（{@code GET /api/admin/coupon/{id}/records} 的 list 元素）。
 *
 * <p>P5 步骤 C：字段名与 {@code mall-marketing} 的
 * {@code com.mall.marketing.dto.CouponRecordVO} 逐字相同。
 *
 * <p>⚠️ {@link #couponStatus} 是**投影后的对外值**：库里 {@code LOCKED(3)} 由营销域投影成 {@code 1}
 * （本层只是原样透传它的 JSON，不做任何转换——一旦这里"顺手再投影一次"或"把 3 放过去"，
 * 后台记录页就会与"我的券"页显示成不同状态）。
 */
@Data
public class CouponRecordVO {

    private Long id;
    private Long memberId;
    private String memberUsername;
    private String memberNickname;
    /** 对外状态：0未用 1已用 2已过期（库里的 3=锁定中 已被营销域投影成 1） */
    private Integer couponStatus;
    private LocalDateTime receiveTime;
    private LocalDateTime expireTime;
    private String orderNo;
    private LocalDateTime useTime;
}
