package com.mall.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可领取的券模板(含当前用户领取状态) —— 单体 {@code com.mall.demo.sms.dto.CouponTemplateVO} 的契约副本。
 *
 * <p>字段名与顺序**逐字照抄**（P5 批次 2 把 {@code GET /api/coupon/available} 搬进本服务，
 * 前端按这些名字取值：{@code received} 决定按钮是"领取"还是"已领取"）。
 * 唯一删掉的是单体上的 {@code @Schema(...)} 注解——那是 springdoc 的文档注解，
 * 本服务本批没有 springdoc（不影响任何 JSON 字段）。
 */
@Data
public class CouponTemplateVO {

    private Long id;
    private String name;
    private Integer type;
    private Long thresholdAmount;
    private Long discountAmount;
    private Integer totalCount;
    private Integer perMemberLimit;
    /** 是否已领(一期每券限1张/人) */
    private Boolean received;
    /** 已发总数(展示用) */
    private Integer receivedCount;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    private Integer validDays;
}
