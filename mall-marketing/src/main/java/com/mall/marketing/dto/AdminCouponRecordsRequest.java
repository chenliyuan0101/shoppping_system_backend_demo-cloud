package com.mall.marketing.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 后台领取记录分页请求（{@code POST /internal/v1/marketing/admin/coupon/{id}/records} 的请求体）。
 *
 * <p>与单体后台 controller 的 {@code PageQuery} 入参逐字对应（{@code pageNum}/{@code pageSize}）。
 */
@Data
public class AdminCouponRecordsRequest {

    @PositiveOrZero(message = "页码不能为负")
    private Long pageNum;

    @PositiveOrZero(message = "每页条数不能为负")
    private Long pageSize;
}
