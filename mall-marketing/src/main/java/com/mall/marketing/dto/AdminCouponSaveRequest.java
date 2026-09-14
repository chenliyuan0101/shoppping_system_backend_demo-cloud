package com.mall.marketing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台新增/修改券模板请求 —— 单体 {@code com.mall.demo.sms.dto.AdminCouponSaveRequest} 的契约副本。
 *
 * <p><b>P5 步骤 C</b>：后台规则的属主搬进营销域，因此这个请求形状也搬了过来
 * （单体保留同名副本用于 HTTP 签名与转发，两侧字段名/校验注解**逐字相同**——
 * 与 review/content 的"契约靠逐字相同 + 契约测试守"同一口径，不建共享 jar）。
 *
 * <p>校验文案属于对外契约（基线表）：{@code 请输入券名称}、{@code 减免金额必须大于 0}、
 * {@code 门槛金额不能为负}。它们由 {@code RequestValidator} 在 Service 入口触发。
 */
@Data
public class AdminCouponSaveRequest {

    @NotBlank(message = "请输入券名称")
    private String name;

    /** 券类型 1满减券 */
    private Integer type;

    @NotNull(message = "减免金额必须大于 0")
    @Min(value = 1, message = "减免金额必须大于 0")
    private Long discountAmount;

    /** 使用门槛(分)，0=无门槛 */
    @NotNull(message = "门槛金额不能为负")
    @Min(value = 0, message = "门槛金额不能为负")
    private Long thresholdAmount;

    /** 发行总量(NULL=不限) */
    private Integer totalCount;

    /** 每人限领(默认1) */
    private Integer perMemberLimit;

    /** 有效期类型 1固定时间段 2领取后N天 */
    private Integer validType;

    /** 生效开始(validType=1)，格式 yyyy-MM-ddTHH:mm:ss */
    private String validStartTime;

    /** 生效结束(validType=1) */
    private String validEndTime;

    /** 领取后有效天数(validType=2) */
    private Integer validDays;
}
