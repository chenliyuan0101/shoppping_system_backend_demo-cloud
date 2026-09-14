package com.mall.demo.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下单可选券(结算页展示)。
 */
@Data
public class CouponBriefVO {

    private Long id;
    private String name;
    private Long thresholdAmount;
    private Long discountAmount;
    private LocalDateTime expireTime;
}
